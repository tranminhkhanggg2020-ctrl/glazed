package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.block.entity.BlockEntity;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.nnpg.glazed.GlazedAddon;

/**
 * KryptonSusChunk - Base Finder Module
 *
 * Phát hiện base ẩn bằng cách phân tích ChunkDataS2CPacket:
 *  1. NBT Analysis  : Đếm BlockEntity trong chunk → ngưỡng = sensitivity * 10
 *  2. Heuristics    : Quét khối tự nhiên bị thay thế (amethyst mất, cave vines mất, v.v.)
 *                     → tính "Sus Score", cờ chunk nếu vượt ngưỡng sensitivity
 *  3. RAM Guard     : Long2ObjectMap lưu chunk đã cờ, tự dọn khi quá xa / đổi server
 */
public class KryptonSusChunk extends Module {

    // -------------------------------------------------------------------------
    // Settings Groups
    // -------------------------------------------------------------------------

    private final SettingGroup sgGeneral   = settings.createGroup("General");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgHeuristic = settings.createGroup("Heuristic Filters");

    // --- General ---
    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét tính bằng chunk (1-32).")
            .defaultValue(4).min(1).max(32).sliderRange(1, 32)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Ngưỡng cảnh báo. NBT threshold = sensitivity × 10. Sus Score threshold = sensitivity.")
            .defaultValue(3).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    // --- Render ---
    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của hộp cảnh báo (0 = trong suốt, 255 = đục).")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    // --- Heuristic Filters ---
    private final Setting<Boolean> filterKelp = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("kelp").description("Phát hiện kelp bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("cave-vines").description("Phát hiện cave vines bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("vines").description("Phát hiện vines bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Phát hiện amethyst geode bị đào phá.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> filterBamboo = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("bamboo").description("Phát hiện bamboo bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterBeeNest = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("bee-nest").description("Phát hiện bee nest bị xóa/di chuyển.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate").description("Phát hiện deepslate bị xoay (dấu hiệu đào thủ công).")
            .defaultValue(false).build()
    );

    // -------------------------------------------------------------------------
    // Internal State
    // -------------------------------------------------------------------------

    /**
     * Map: chunkPos.toLong() → Sus Score (int)
     * Dùng Long key để tránh allocate ChunkPos object → tiết kiệm RAM.
     */
    private final Long2IntOpenHashMap susChunks = new Long2IntOpenHashMap();

    /**
     * Cache tọa độ render: chunkLong → BoxInfo đã tính sẵn để tránh tính lại mỗi frame.
     */
    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public KryptonSusChunk() {
        super(GlazedAddon.CATEGORY, "krypton-sus-chunk",
            "Phát hiện base ẩn qua phân tích ChunkData packet (NBT + Heuristics).");
    }

    // -------------------------------------------------------------------------
    // Enable / Disable lifecycle
    // -------------------------------------------------------------------------

    @Override
    public void onActivate() {
        susChunks.clear();
        renderCache.clear();
    }

    @Override
    public void onDeactivate() {
        susChunks.clear();
        renderCache.clear();
    }

    // -------------------------------------------------------------------------
    // Packet Listener
    // -------------------------------------------------------------------------

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof ChunkDataS2CPacket packet) {
            handleChunkData(packet);
        } else if (event.packet instanceof BlockUpdateS2CPacket packet) {
            handleBlockUpdate(packet);
        }
    }

    /**
     * Phân tích một ChunkDataS2CPacket:
     *  Bước 1 – Nếu chunk quá xa simulationDistance → bỏ qua.
     *  Bước 2 – NBT analysis: đếm BlockEntity.
     *  Bước 3 – Heuristic analysis: quét WorldChunk sau khi nó được load.
     */
    private void handleChunkData(ChunkDataS2CPacket packet) {
        int cx = packet.getX();
        int cz = packet.getZ();

        // Lọc khoảng cách
        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        // Tránh quét lại chunk đã cờ với điểm cao
        if (susChunks.containsKey(key) && susChunks.get(key) >= sensitivity.get() * 10) return;

        int susScore = 0;

        // ------------------------------------------------------------------
        // BƯỚC 2: NBT Analysis – đếm BlockEntity trong chunk data
        // ------------------------------------------------------------------
        int blockEntityCount = packet.getBlockEntities().size();
        int nbtThreshold = sensitivity.get() * 10;

        if (blockEntityCount >= nbtThreshold) {
            // Chắc chắn có base: score = số BE (có thể > threshold rất nhiều)
            susScore += blockEntityCount;
        }

        // ------------------------------------------------------------------
        // BƯỚC 3: Heuristic Analysis – quét WorldChunk (nếu đã loaded)
        // Minecraft load chunk sau khi nhận packet nên ta schedule check
        // ------------------------------------------------------------------
        final int finalCx = cx, finalCz = cz;
        final int capturedScore = susScore;

        // Dùng tick delay nhỏ để chunk kịp load vào world
        scheduleHeuristicCheck(finalCx, finalCz, capturedScore, key);
    }

    /**
     * Lên lịch kiểm tra heuristic sau 1 tick để WorldChunk kịp populate.
     * Tránh race condition giữa packet receive và chunk load.
     */
    private void scheduleHeuristicCheck(int cx, int cz, int baseScore, long key) {
        // Meteor không có built-in scheduler đơn giản,
        // ta dùng Thread ngắn để không block netty thread.
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            // Phải chạy trên main thread để truy cập world an toàn
            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(cx, cz);
                if (chunk == null) return;

                int totalScore = baseScore + computeHeuristicScore(chunk);
                flagChunkIfSus(key, cx, cz, totalScore);
            });
        }, "KryptonSusChunk-Heuristic").start();
    }

    /**
     * Xử lý BlockUpdateS2CPacket: nếu một block tự nhiên (amethyst, bee nest…)
     * bị thay thành AIR ở chunk đã theo dõi → tăng điểm.
     */
    private void handleBlockUpdate(BlockUpdateS2CPacket packet) {
        BlockPos bp = packet.getPos();
        ChunkPos cp = new ChunkPos(bp);
        long key = cp.toLong();

        if (!isWithinSimulationDistance(cp.x, cp.z)) return;

        Block newBlock = packet.getState().getBlock();
        Block oldBlock = (mc.world != null)
            ? mc.world.getBlockState(bp).getBlock()
            : Blocks.AIR;

        // Nếu một khối tự nhiên bị đổi thành Air → nghi ngờ người đào
        if (newBlock == Blocks.AIR && isNaturalTrackedBlock(oldBlock)) {
            int current = susChunks.getOrDefault(key, 0);
            flagChunkIfSus(key, cp.x, cp.z, current + 2);
        }
    }

    // -------------------------------------------------------------------------
    // Heuristic Score Engine
    // -------------------------------------------------------------------------

    /**
     * Tính Sus Score của một WorldChunk dựa trên các heuristic đã bật.
     *
     * Nguyên lý:
     *  - Mỗi loại khối tự nhiên (amethyst, vine…) CÓ XÁC SUẤT XUẤT HIỆN
     *    trong một chunk bình thường. Nếu chunk đó KHÔNG có chúng khi lẽ ra
     *    phải có (dựa vào biome/độ sâu), tức là chúng bị đào → +score.
     *  - Ngược lại, nếu có Air pocket hình hộp ở độ sâu → người đào.
     *
     * @return sus score từ heuristics
     */
    private int computeHeuristicScore(WorldChunk chunk) {
        int score = 0;
        int minY = mc.world.getBottomY();
        int maxY = mc.world.getTopY();

        // Đếm tần suất khối để phát hiện bất thường
        int airCount        = 0;
        int deepslateCount  = 0;
        int amethystCount   = 0;
        int naturalCount    = 0; // kelp/vine/bamboo/bee nest / cave vine

        ChunkPos cp = chunk.getPos();

        // Quét toàn bộ voxel trong chunk (16×(world_height)×16)
        // Giới hạn quét để tránh lag: chỉ quét đến Y=0 (underground)
        int scanMaxY = Math.min(0, maxY);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    Block block = chunk.getBlockState(bp).getBlock();

                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR) {
                        airCount++;
                    } else if (block == Blocks.DEEPSLATE
                            || block == Blocks.COBBLED_DEEPSLATE) {
                        deepslateCount++;
                    } else if (block == Blocks.AMETHYST_CLUSTER
                            || block == Blocks.BUDDING_AMETHYST
                            || block == Blocks.AMETHYST_BLOCK) {
                        amethystCount++;
                    } else if (isNaturalTrackedBlock(block)) {
                        naturalCount++;
                    }
                }
            }
        }

        int totalUnderground = 16 * 16 * Math.abs(scanMaxY - minY);

        // ------------------------------------------------------------------
        // Heuristic A: Amethyst Geode bị đào
        // Nếu bật filter amethyst: một geode bình thường có ~30-100 amethyst block.
        // Nếu chunk có < 5 và nằm ở tầm sâu có deepslate → có khả năng bị đào.
        // ------------------------------------------------------------------
        if (filterAmethyst.get()) {
            boolean hasDeepslate = deepslateCount > 50;
            if (hasDeepslate && amethystCount < 5) {
                // Geode nên ở đây nhưng bị phá → +3 điểm
                score += 3;
            }
        }

        // ------------------------------------------------------------------
        // Heuristic B: Air Pocket hình hộp (người đào tạo phòng ở)
        // Tỷ lệ air cao bất thường trong vùng deepslate → đào hang
        // ------------------------------------------------------------------
        if (totalUnderground > 0) {
            float airRatio = (float) airCount / totalUnderground;
            // Underground bình thường có ~10-20% cave air tự nhiên.
            // Nếu > 35% → khả năng cao có hang nhân tạo.
            if (airRatio > 0.35f) {
                score += (int)((airRatio - 0.35f) * 20); // tỷ lệ vượt càng cao → score càng cao
            }
        }

        // ------------------------------------------------------------------
        // Heuristic C: Khối tự nhiên bị xóa (kelp, cave vines, v.v.)
        // Nếu count = 0 trong chunk mà lẽ ra phải có (biome check đơn giản)
        // ------------------------------------------------------------------
        if (shouldCheckNaturalBlocks() && naturalCount == 0 && deepslateCount > 100) {
            // Deepslate nhiều → vùng underground, lẽ ra có cave feature
            // Nhưng không có bất kỳ khối tự nhiên nào → bị xóa hết
            score += 2;
        }

        // ------------------------------------------------------------------
        // Heuristic D: Rotated Deepslate (dấu hiệu người đào đặt lại)
        // Deepslate tự nhiên luôn xoay dọc. Nếu có deepslate xoay ngang → nhân tạo
        // ------------------------------------------------------------------
        if (filterRotatedDeepslate.get()) {
            score += countRotatedDeepslate(chunk, cp, minY, scanMaxY);
        }

        return score;
    }

    /**
     * Đếm số lượng Deepslate bị xoay ngang (dấu hiệu đặt tay).
     * Deepslate tự nhiên = trục Y. Nếu trục X hoặc Z → nhân tạo.
     */
    private int countRotatedDeepslate(WorldChunk chunk, ChunkPos cp, int minY, int maxY) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    var state = chunk.getBlockState(bp);
                    if (state.getBlock() == Blocks.DEEPSLATE) {
                        var axis = state.get(PillarBlock.AXIS);
                        if (axis != net.minecraft.util.math.Direction.Axis.Y) {
                            count++;
                        }
                    }
                }
            }
        }
        // Normalise: mỗi 5 block deepslate xoay → +1 score
        return count / 5;
    }

    // -------------------------------------------------------------------------
    // Flag / Unflag Helpers
    // -------------------------------------------------------------------------

    private void flagChunkIfSus(long key, int cx, int cz, int score) {
        if (score <= 0) return;
        int existing = susChunks.getOrDefault(key, 0);
        int newScore = existing + score;
        susChunks.put(key, newScore);

        if (newScore >= sensitivity.get()) {
            // Cache render info: [blockX, blockZ]
            renderCache.put(key, new int[]{ cx * 16, cz * 16 });
        }
    }

    // -------------------------------------------------------------------------
    // Render
    // -------------------------------------------------------------------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || renderCache.isEmpty()) return;

        int a = alpha.get();
        // Màu đỏ cảnh báo với alpha tuỳ chỉnh
        Color sideColor   = new Color(255, 0, 0, a);
        Color lineColor   = new Color(255, 0, 0, Math.min(255, a + 80));

        int minY = mc.world.getBottomY();
        int maxY = mc.world.getTopY();

        // Dọn chunk quá xa trước khi render
        pruneDistantChunks();

        for (var entry : renderCache.long2ObjectEntrySet()) {
            int[] coords = entry.getValue();
            int bx = coords[0];
            int bz = coords[1];

            // Vẽ hộp bao phủ toàn bộ chunk từ đáy đến đỉnh thế giới
            event.renderer.box(
                bx,       minY, bz,
                bx + 16,  maxY, bz + 16,
                sideColor, lineColor,
                ShapeMode.Both, 0
            );
        }
    }

    // -------------------------------------------------------------------------
    // Distance & Cleanup Utilities
    // -------------------------------------------------------------------------

    /**
     * Xóa các chunk đã cờ mà hiện nằm ngoài simulationDistance.
     * Gọi mỗi lần render để tránh rò rỉ bộ nhớ.
     */
    private void pruneDistantChunks() {
        if (mc.player == null) return;
        int playerCx = mc.player.getChunkPos().x;
        int playerCz = mc.player.getChunkPos().z;
        int dist = simulationDistance.get();

        renderCache.long2ObjectEntrySet().removeIf(entry -> {
            ChunkPos cp = new ChunkPos(entry.getLongKey());
            boolean tooFar = Math.abs(cp.x - playerCx) > dist
                          || Math.abs(cp.z - playerCz) > dist;
            if (tooFar) susChunks.remove(entry.getLongKey());
            return tooFar;
        });
    }

    private boolean isWithinSimulationDistance(int cx, int cz) {
        if (mc.player == null) return false;
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int d = simulationDistance.get();
        return Math.abs(cx - pcx) <= d && Math.abs(cz - pcz) <= d;
    }

    // -------------------------------------------------------------------------
    // Block Classification Helpers
    // -------------------------------------------------------------------------

    /**
     * Trả về true nếu khối thuộc nhóm tự nhiên đang được theo dõi (filter bật).
     */
    private boolean isNaturalTrackedBlock(Block block) {
        if (filterKelp.get()       && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) return true;
        if (filterCaveVines.get()  && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) return true;
        if (filterVines.get()      && block == Blocks.VINE) return true;
        if (filterAmethyst.get()   && (block == Blocks.AMETHYST_CLUSTER
                                    || block == Blocks.BUDDING_AMETHYST
                                    || block == Blocks.AMETHYST_BLOCK)) return true;
        if (filterBamboo.get()     && (block == Blocks.BAMBOO || block == Blocks.BAMBOO_SAPLING)) return true;
        if (filterBeeNest.get()    && block == Blocks.BEE_NEST) return true;
        return false;
    }

    /**
     * Chỉ kiểm tra natural block count nếu ít nhất 1 filter tự nhiên được bật.
     */
    private boolean shouldCheckNaturalBlocks() {
        return filterKelp.get() || filterCaveVines.get() || filterVines.get()
            || filterBamboo.get() || filterBeeNest.get();
    }
}
