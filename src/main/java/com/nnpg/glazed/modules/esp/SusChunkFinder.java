package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
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

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import com.nnpg.glazed.GlazedAddon;

public class SusChunkFinder extends Module {

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
            .description("Ngưỡng cảnh báo. NBT threshold = sensitivity * 10. Sus Score threshold = sensitivity.")
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

    private final Long2IntOpenHashMap susChunks = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Phát hiện base ẩn qua phân tích ChunkData packet (NBT + Heuristics).");
    }

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

        if (event.packet instanceof ChunkDataS2CPacket) {
            handleChunkData((ChunkDataS2CPacket) event.packet);
        } else if (event.packet instanceof BlockUpdateS2CPacket) {
            handleBlockUpdate((BlockUpdateS2CPacket) event.packet);
        }
    }

    private void handleChunkData(ChunkDataS2CPacket packet) {
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();

        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        if (susChunks.containsKey(key) && susChunks.get(key) >= sensitivity.get() * 10) return;

        int susScore = 0;
        int blockEntityCount = packet.getBlockEntityData().size();
        int nbtThreshold = sensitivity.get() * 10;

        if (blockEntityCount >= nbtThreshold) {
            susScore += blockEntityCount;
        }

        final int finalCx = cx, finalCz = cz;
        final int capturedScore = susScore;

        scheduleHeuristicCheck(finalCx, finalCz, capturedScore, key);
    }

    private void scheduleHeuristicCheck(int cx, int cz, int baseScore, long key) {
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(cx, cz);
                if (chunk == null) return;

                int totalScore = baseScore + computeHeuristicScore(chunk);
                flagChunkIfSus(key, cx, cz, totalScore);
            });
        }, "SusChunkFinder-Heuristic").start();
    }

    private void handleBlockUpdate(BlockUpdateS2CPacket packet) {
        BlockPos bp = packet.getPos();
        ChunkPos cp = new ChunkPos(bp);
        long key = cp.toLong();

        if (!isWithinSimulationDistance(cp.x, cp.z)) return;

        Block newBlock = packet.getState().getBlock();
        Block oldBlock = (mc.world != null) ? mc.world.getBlockState(bp).getBlock() : Blocks.AIR;

        if (newBlock == Blocks.AIR && isNaturalTrackedBlock(oldBlock)) {
            int current = susChunks.getOrDefault(key, 0);
            flagChunkIfSus(key, cp.x, cp.z, current + 2);
        }
    }

    // -------------------------------------------------------------------------
    // Heuristic Score Engine
    // -------------------------------------------------------------------------

    private int computeHeuristicScore(WorldChunk chunk) {
        int score = 0;
        int minY = mc.world.getBottomY();
        int maxY = mc.world.getBottomY() + mc.world.getHeight();

        int airCount        = 0;
        int deepslateCount  = 0;
        int amethystCount   = 0;
        int naturalCount    = 0;

        ChunkPos cp = chunk.getPos();
        int scanMaxY = Math.min(0, maxY);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    Block block = chunk.getBlockState(bp).getBlock();

                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR) {
                        airCount++;
                    } else if (block == Blocks.DEEPSLATE || block == Blocks.COBBLED_DEEPSLATE) {
                        deepslateCount++;
                    } else if (block == Blocks.AMETHYST_CLUSTER || block == Blocks.BUDDING_AMETHYST || block == Blocks.AMETHYST_BLOCK) {
                        amethystCount++;
                    } else if (isNaturalTrackedBlock(block)) {
                        naturalCount++;
                    }
                }
            }
        }

        int totalUnderground = 16 * 16 * Math.abs(scanMaxY - minY);

        if (filterAmethyst.get()) {
            boolean hasDeepslate = deepslateCount > 50;
            if (hasDeepslate && amethystCount < 5) {
                score += 3;
            }
        }

        if (totalUnderground > 0) {
            float airRatio = (float) airCount / totalUnderground;
            if (airRatio > 0.35f) {
                score += (int)((airRatio - 0.35f) * 20);
            }
        }

        if (shouldCheckNaturalBlocks() && naturalCount == 0 && deepslateCount > 100) {
            score += 2;
        }

        if (filterRotatedDeepslate.get()) {
            score += countRotatedDeepslate(chunk, cp, minY, scanMaxY);
        }

        return score;
    }

    private int countRotatedDeepslate(WorldChunk chunk, ChunkPos cp, int minY, int maxY) {
        int count = 0;
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < maxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    var state = chunk.getBlockState(bp);
                    if (state.getBlock() == Blocks.DEEPSLATE) {
                        // [FIX]: Gọi thẳng đường dẫn tuyệt đối của Properties.AXIS để không cần import
                        var axis = state.get(net.minecraft.state.property.Properties.AXIS);
                        if (axis != net.minecraft.util.math.Direction.Axis.Y) {
                            count++;
                        }
                    }
                }
            }
        }
        return count / 5;
    }

    private void flagChunkIfSus(long key, int cx, int cz, int score) {
        if (score <= 0) return;
        int existing = susChunks.getOrDefault(key, 0);
        int newScore = existing + score;
        susChunks.put(key, newScore);

        if (newScore >= sensitivity.get()) {
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
        Color sideColor   = new Color(255, 0, 0, a);
        Color lineColor   = new Color(255, 0, 0, Math.min(255, a + 80));

        int minY = mc.world.getBottomY();
        int maxY = mc.world.getBottomY() + mc.world.getHeight();

        pruneDistantChunks();

        for (var entry : renderCache.long2ObjectEntrySet()) {
            int[] coords = entry.getValue();
            int bx = coords[0];
            int bz = coords[1];

            event.renderer.box(
                bx,       minY, bz,
                bx + 16,  maxY, bz + 16,
                sideColor, lineColor,
                ShapeMode.Both, 0
            );
        }
    }

    private void pruneDistantChunks() {
        if (mc.player == null) return;
        int playerCx = mc.player.getChunkPos().x;
        int playerCz = mc.player.getChunkPos().z;
        int dist = simulationDistance.get();

        renderCache.long2ObjectEntrySet().removeIf(entry -> {
            ChunkPos cp = new ChunkPos(entry.getLongKey());
            boolean tooFar = Math.abs(cp.x - playerCx) > dist || Math.abs(cp.z - playerCz) > dist;
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

    private boolean isNaturalTrackedBlock(Block block) {
        if (filterKelp.get()       && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) return true;
        if (filterCaveVines.get()  && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) return true;
        if (filterVines.get()      && block == Blocks.VINE) return true;
        if (filterAmethyst.get()   && (block == Blocks.AMETHYST_CLUSTER || block == Blocks.BUDDING_AMETHYST || block == Blocks.AMETHYST_BLOCK)) return true;
        if (filterBamboo.get()     && (block == Blocks.BAMBOO || block == Blocks.BAMBOO_SAPLING)) return true;
        if (filterBeeNest.get()    && block == Blocks.BEE_NEST) return true;
        return false;
    }

    private boolean shouldCheckNaturalBlocks() {
        return filterKelp.get() || filterCaveVines.get() || filterVines.get() || filterBamboo.get() || filterBeeNest.get();
    }
}
