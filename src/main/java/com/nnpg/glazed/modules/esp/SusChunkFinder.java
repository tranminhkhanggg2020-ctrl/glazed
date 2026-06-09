package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockEntityUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.entity.EntityType;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;

import com.nnpg.glazed.GlazedAddon;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SusChunkFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ==========================================
    // CÀI ĐẶT RADAR
    // ==========================================

    private final Setting<Integer> renderDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("render-distance")
            .description("Bán kính VẼ thảm đỏ (Không ảnh hưởng đến khả năng BẮT mạng).")
            .defaultValue(6).min(1).max(16).sliderRange(1, 16)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Độ nhạy Krypton (1 = Khó, 10 = Cực Nhạy). Khuyên dùng: 5-7.")
            .defaultValue(6).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt Thảm Đỏ (Y=62).")
            .defaultValue(70).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    // ==========================================
    // BỘ LỌC HEURISTIC (Phụ trợ)
    // ==========================================
    
    private final Setting<Boolean> filterAmethyst = sgGeneral.add(new BoolSetting.Builder().name("amethyst").defaultValue(true).build());
    private final Setting<Boolean> filterBeeNest = sgGeneral.add(new BoolSetting.Builder().name("bee-nest").defaultValue(false).build());
    private final Setting<Boolean> filterRotatedDeepslate = sgGeneral.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(false).build());
    private final Setting<Boolean> filterKelp = sgGeneral.add(new BoolSetting.Builder().name("kelp").defaultValue(false).build());
    private final Setting<Boolean> filterCaveVines = sgGeneral.add(new BoolSetting.Builder().name("cave-vines").defaultValue(false).build());
    private final Setting<Boolean> filterVines = sgGeneral.add(new BoolSetting.Builder().name("vines").defaultValue(false).build());
    private final Setting<Boolean> filterBamboo = sgGeneral.add(new BoolSetting.Builder().name("bamboo").defaultValue(false).build());

    // ==========================================
    // KRYPTON CACHE & ACCUMULATOR
    // ==========================================

    private final Map<Long, ChunkPos> baseCache = new ConcurrentHashMap<>();
    private final Set<Long> newChunkCache = ConcurrentHashMap.newKeySet(); 
    
    // BỘ NHỚ ĐỆM TÍCH LŨY: Lưu trữ điểm số ngầm, không bị xóa ngay cả khi bay xa
    private final Map<Long, Integer> chunkSusScores = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar Krypton: Tích lũy Packet đa luồng & Phân tích Rương Ngầm. Xóa bỏ giới hạn Ping.");
    }

    @Override
    public void onActivate() { 
        baseCache.clear(); 
        newChunkCache.clear();
        chunkSusScores.clear();
    }

    @Override
    public void onDeactivate() { 
        baseCache.clear(); 
        newChunkCache.clear();
        chunkSusScores.clear();
    }

    // ==========================================
    // TẦNG MẠNG (KHÔNG CÓ GIỚI HẠN KHOẢNG CÁCH)
    // ==========================================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // 1. CHỐNG BÁO ĐỘNG GIẢ (Bắt Đất Mới)
        if (event.packet instanceof BlockUpdateS2CPacket updatePacket) {
            BlockState state = updatePacket.getState();
            if (!state.getFluidState().isEmpty()) {
                BlockPos bPos = updatePacket.getPos();
                long key = ChunkPos.toLong(bPos.getX() >> 4, bPos.getZ() >> 4);
                newChunkCache.add(key); 
            }
        }

        // 2. KRYPTON BLOCK-ENTITY LEAK SNIPER
        if (event.packet instanceof ChunkDataS2CPacket chunkPacket) {
            int cx = chunkPacket.getChunkX();
            int cz = chunkPacket.getChunkZ();
            long key = ChunkPos.toLong(cx, cz);

            if (baseCache.containsKey(key)) return;

            try {
                int[] counter = {0};
                chunkPacket.getChunkData().getBlockEntities(cx, cz).accept((bPos, bType, bNbt) -> {
                    counter[0]++;
                });
                
                if (counter[0] > 0) {
                    // Cú đấm thép: Mỗi rương ngầm = 10 điểm. 
                    // Sens 5 (cần 90 điểm) -> 9 cái rương là kích hoạt Base ngay lập tức.
                    addSusScore(cx, cz, counter[0] * 10);
                }
            } catch (Exception ignored) {}

            // Chạy kiểm tra khối phụ trợ (Chỉ chạy trên Chunk Cũ)
            runHeuristicScan(cx, cz, key);
        }

        // 3. THỰC THỂ LẠ (Xe mỏ rương, Khung vật phẩm)
        else if (event.packet instanceof EntitySpawnS2CPacket spawnPacket) {
            EntityType<?> type = spawnPacket.getEntityType();
            if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME || 
                type == EntityType.CHEST_MINECART || type == EntityType.ARMOR_STAND) {
                int cx = ((int) spawnPacket.getX()) >> 4;
                int cz = ((int) spawnPacket.getZ()) >> 4;
                addSusScore(cx, cz, 40); // Điểm nghi ngờ cực mạnh
            }
        }

        // 4. BỘ HÚT MICRO-PACKETS (Redstone, Lò nung cập nhật lẻ tẻ)
        else if (event.packet instanceof BlockEntityUpdateS2CPacket updatePacket) {
            BlockPos bPos = updatePacket.getPos();
            addSusScore(bPos.getX() >> 4, bPos.getZ() >> 4, 15); // Lò nung đang cháy = +15 điểm/lần
        }
    }

    // ==========================================
    // LOGIC CỘNG DỒN ĐIỂM (ACCUMULATOR)
    // ==========================================

    private void addSusScore(int cx, int cz, int amount) {
        long key = ChunkPos.toLong(cx, cz);
        if (baseCache.containsKey(key)) return;

        // Tích lũy điểm vào bộ nhớ đệm
        int currentScore = chunkSusScores.getOrDefault(key, 0) + amount;
        chunkSusScores.put(key, currentScore);

        // Công thức tính Ngưỡng: Sens càng cao, điểm cần thiết càng ít
        int threshold = (11 - sensitivity.get()) * 15; 
        
        if (currentScore >= threshold) {
            baseCache.put(key, new ChunkPos(cx, cz));
            chunkSusScores.remove(key); // Xóa khỏi bộ nhớ đệm khi đã thành Base
        }
    }

    // ==========================================
    // QUÉT KHỐI PHỤ TRỢ (HEURISTIC)
    // ==========================================

    private void runHeuristicScan(int cx, int cz, long key) {
        if (!filterKelp.get() && !filterCaveVines.get() && !filterVines.get() && 
            !filterAmethyst.get() && !filterBamboo.get() && !filterBeeNest.get() && 
            !filterRotatedDeepslate.get()) return;

        mc.execute(() -> {
            if (mc.world == null) return;
            WorldChunk chunk = mc.world.getChunk(cx, cz);
            
            if (chunk != null && !chunk.isEmpty()) {
                EXECUTOR.execute(() -> {
                    try {
                        // KHIẾN BÁO ĐỘNG GIẢ BẰNG 0: Chỉ quét nếu đây là Đất Cũ
                        if (newChunkCache.contains(key)) return; 

                        int blockScore = 0;
                        for (ChunkSection section : chunk.getSectionArray()) {
                            if (section == null || section.isEmpty()) continue;
                            PalettedContainer<BlockState> container = section.getBlockStateContainer();

                            boolean hasTargetBlocks = container.hasAny(state -> {
                                Block b = state.getBlock();
                                return (filterAmethyst.get() && b == Blocks.AMETHYST_CLUSTER) ||
                                       (filterBeeNest.get() && b == Blocks.BEE_NEST) ||
                                       (filterBamboo.get() && b == Blocks.BAMBOO) ||
                                       (filterRotatedDeepslate.get() && b == Blocks.DEEPSLATE) ||
                                       (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) ||
                                       (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT)) ||
                                       (filterVines.get() && b == Blocks.VINE);
                            });

                            if (!hasTargetBlocks) continue;

                            for (int x = 0; x < 16; x++) {
                                for (int y = 0; y < 16; y++) {
                                    for (int z = 0; z < 16; z++) {
                                        BlockState state = container.get(x, y, z);
                                        Block b = state.getBlock();

                                        if (filterAmethyst.get() && b == Blocks.AMETHYST_CLUSTER) blockScore += 20;
                                        if (filterBeeNest.get() && b == Blocks.BEE_NEST) blockScore += 10;
                                        if (filterBamboo.get() && b == Blocks.BAMBOO) blockScore += 5;
                                        if (filterVines.get() && b == Blocks.VINE) blockScore += 2;

                                        if (filterRotatedDeepslate.get() && b == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                                            if (state.get(Properties.AXIS) != Direction.Axis.Y) blockScore += 50; 
                                        }
                                        if (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) {
                                            if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) blockScore += 15; 
                                        }
                                        if (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT)) {
                                            if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) blockScore += 15;
                                        }
                                    }
                                }
                            }
                        }

                        if (blockScore > 0) {
                            addSusScore(cx, cz, blockScore + 30); // Bơm điểm vào Accumulator
                        }
                    } catch (Exception ignored) {}
                });
            }
        });
    }

    // ==========================================
    // VẼ ĐỒ HỌA (KRYPTON RED CARPET)
    // ==========================================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || baseCache.isEmpty()) return;

        pruneCaches();

        int aBase = alpha.get();
        Color baseSideColor = new Color(255, 0, 0, aBase);
        Color baseLineColor = new Color(255, 0, 0, 255); 
        double baseY = 62.0; 

        if (mc.player == null) return;
        int pCx = mc.player.getChunkPos().x;
        int pCz = mc.player.getChunkPos().z;
        int maxDist = renderDistance.get();

        for (ChunkPos pos : baseCache.values()) {
            // Chỉ VẼ những thảm ở gần để tránh lag FPS
            if (Math.abs(pos.x - pCx) <= maxDist && Math.abs(pos.z - pCz) <= maxDist) {
                int bx = pos.getStartX();
                int bz = pos.getStartZ();
                
                event.renderer.box(
                    bx,      baseY,       bz,
                    bx + 16, baseY + 0.1, bz + 16,
                    baseSideColor, baseLineColor,
                    ShapeMode.Both, 0 
                );
            }
        }
    }

    // Dọn dẹp RAM cực kỳ hào phóng (Cho phép lưu trữ dữ liệu rất xa)
    private void pruneCaches() {
        if (mc.player == null) return;
        int pCx = mc.player.getChunkPos().x;
        int pCz = mc.player.getChunkPos().z;
        
        // Cho phép lưu Base ở khoảng cách lên tới 50 chunk (Gần 1000 blocks)
        int killDistance = 50; 

        baseCache.entrySet().removeIf(e -> isTooFar(e.getKey(), pCx, pCz, killDistance));
        newChunkCache.removeIf(key -> isTooFar(key, pCx, pCz, killDistance));
        chunkSusScores.entrySet().removeIf(e -> isTooFar(e.getKey(), pCx, pCz, killDistance));
    }

    private boolean isTooFar(long key, int pCx, int pCz, int dist) {
        ChunkPos cp = new ChunkPos(key);
        return Math.abs(cp.x - pCx) > dist || Math.abs(cp.z - pCz) > dist;
    }
}
