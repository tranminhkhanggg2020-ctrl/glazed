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
    // CÀI ĐẶT GIAO DIỆN CHÍNH
    // ==========================================

    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét radar (Chunks).")
            .defaultValue(4).min(1).max(12).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Độ nhạy radar Base (Krypton Entity Threshold).")
            .defaultValue(3).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của Thảm Đỏ (Y=62).")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    // ==========================================
    // BỘ LỌC HEURISTIC
    // ==========================================
    
    private final Setting<Boolean> filterKelp = sgGeneral.add(new BoolSetting.Builder().name("kelp").defaultValue(false).build());
    private final Setting<Boolean> filterCaveVines = sgGeneral.add(new BoolSetting.Builder().name("cave-vines").defaultValue(false).build());
    private final Setting<Boolean> filterVines = sgGeneral.add(new BoolSetting.Builder().name("vines").defaultValue(false).build());
    private final Setting<Boolean> filterAmethyst = sgGeneral.add(new BoolSetting.Builder().name("amethyst").defaultValue(true).build());
    private final Setting<Boolean> filterBamboo = sgGeneral.add(new BoolSetting.Builder().name("bamboo").defaultValue(false).build());
    private final Setting<Boolean> filterBeeNest = sgGeneral.add(new BoolSetting.Builder().name("bee-nest").defaultValue(false).build());
    private final Setting<Boolean> filterRotatedDeepslate = sgGeneral.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(false).build());

    // ==========================================
    // HỆ THỐNG LÕI & CACHE
    // ==========================================

    private final Map<Long, ChunkPos> baseCache = new ConcurrentHashMap<>();
    private final Set<Long> newChunkCache = ConcurrentHashMap.newKeySet(); // Chứa các Chunk MỚI TINH
    private final Map<Long, Integer> chunkSusScores = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Dò Base Krypton: Trải thảm ĐỎ khi Dấu vết Base + Tín hiệu Chunk Cũ cộng dồn.");
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

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // 1. BẮT TÍN HIỆU ĐẤT MỚI (Liquid Flow Exploit)
        // Bắt cập nhật dòng chảy để xác định đây là vùng đất mới chưa ai đặt chân tới
        if (event.packet instanceof BlockUpdateS2CPacket updatePacket) {
            BlockState state = updatePacket.getState();
            if (!state.getFluidState().isEmpty()) {
                BlockPos bPos = updatePacket.getPos();
                long key = ChunkPos.toLong(bPos.getX() >> 4, bPos.getZ() >> 4);
                newChunkCache.add(key); // Đưa vào danh sách "Đất Mới Tinh"
            }
        }

        // 2. PHÂN TÍCH CHUNK GỐC
        if (event.packet instanceof ChunkDataS2CPacket chunkPacket) {
            int cx = chunkPacket.getChunkX();
            int cz = chunkPacket.getChunkZ();

            if (!isWithinSimulationDistance(cx, cz)) return;

            ChunkPos pos = new ChunkPos(cx, cz);
            long key = pos.toLong();

            if (baseCache.containsKey(key)) return;

            // [KRYPTON PACKET SNIPER]: Luôn được ưu tiên tuyệt đối
            try {
                int[] counter = {0};
                chunkPacket.getChunkData().getBlockEntities(cx, cz).accept((bPos, bType, bNbt) -> {
                    counter[0]++;
                });
                
                int threshold = (11 - sensitivity.get()) * 10; 
                if (counter[0] >= threshold) {
                    baseCache.put(key, pos);
                    return;
                }
            } catch (Exception ignored) {}

            // Chạy bộ lọc phụ trợ
            runHeuristicScan(cx, cz, key, pos);
        }

        // 3. BẮT THỰC THỂ LẠ (Entity Sniping)
        else if (event.packet instanceof EntitySpawnS2CPacket spawnPacket) {
            EntityType<?> type = spawnPacket.getEntityType();
            if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME || 
                type == EntityType.CHEST_MINECART || type == EntityType.ARMOR_STAND) {
                
                int cx = ((int) spawnPacket.getX()) >> 4;
                int cz = ((int) spawnPacket.getZ()) >> 4;
                addSusScore(cx, cz, 20); 
            }
        }

        // 4. MICRO-PACKETS (Dữ liệu Redstone/Lò nung)
        else if (event.packet instanceof BlockEntityUpdateS2CPacket updatePacket) {
            BlockPos bPos = updatePacket.getPos();
            addSusScore(bPos.getX() >> 4, bPos.getZ() >> 4, 10);
        }
    }

    private void runHeuristicScan(int cx, int cz, long key, ChunkPos pos) {
        if (!filterKelp.get() && !filterCaveVines.get() && !filterVines.get() && 
            !filterAmethyst.get() && !filterBamboo.get() && !filterBeeNest.get() && 
            !filterRotatedDeepslate.get()) return;

        mc.execute(() -> {
            if (mc.world == null) return;
            WorldChunk chunk = mc.world.getChunk(cx, cz);
            
            if (chunk != null && !chunk.isEmpty()) {
                EXECUTOR.execute(() -> {
                    try {
                        // [LOGIC CỘNG DỒN]: Kiểm tra xem đây có phải Chunk Cũ không
                        boolean isOldChunk = !newChunkCache.contains(key);
                        
                        // Đất mới tinh -> Khả năng là base cũ = 0 -> Hủy quét block tự nhiên (chống báo động giả)
                        if (!isOldChunk) return; 

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

                        // Nếu tìm thấy dấu vết block bất thường TRONG một Chunk Cũ -> Đánh dấu Thảm Đỏ ngay
                        if (blockScore > 0) {
                            addSusScore(cx, cz, blockScore + 50); // Cộng dồn điểm thưởng cực lớn vì đây là Chunk Cũ
                        }
                        
                    } catch (Exception ignored) {}
                });
            }
        });
    }

    private void addSusScore(int cx, int cz, int amount) {
        if (!isWithinSimulationDistance(cx, cz)) return;
        long key = ChunkPos.toLong(cx, cz);
        
        if (baseCache.containsKey(key)) return;

        int currentScore = chunkSusScores.getOrDefault(key, 0) + amount;
        chunkSusScores.put(key, currentScore);

        // Ngưỡng bùng nổ thảm đỏ dựa vào Sensitivity
        if (currentScore >= (11 - sensitivity.get()) * 15) {
            baseCache.put(key, new ChunkPos(cx, cz));
            chunkSusScores.remove(key); 
        }
    }

    // ==========================================
    // RENDER THẢM ĐỎ DUY NHẤT (SẠCH MÀN HÌNH)
    // ==========================================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || baseCache.isEmpty()) return;

        pruneDistantChunks();

        int aBase = alpha.get();
        Color baseSideColor = new Color(255, 0, 0, aBase);
        Color baseLineColor = new Color(255, 0, 0, 255); 
        double baseY = 62.0; 

        for (ChunkPos pos : baseCache.values()) {
            int bx = pos.getStartX();
            int bz = pos.getStartZ();
            
            // Chỉ in Thảm Đỏ ở những khu vực khả nghi cao nhất
            event.renderer.box(
                bx,      baseY,       bz,
                bx + 16, baseY + 0.1, bz + 16,
                baseSideColor, baseLineColor,
                ShapeMode.Both, 0 
            );
        }
    }

    private void pruneDistantChunks() {
        if (mc.player == null) return;
        int playerCx = mc.player.getChunkPos().x;
        int playerCz = mc.player.getChunkPos().z;
        int dist = simulationDistance.get();

        baseCache.entrySet().removeIf(entry -> isTooFar(entry.getKey(), playerCx, playerCz, dist));
        
        // Dọn dẹp cache rác để tránh nặng RAM khi bay xa
        newChunkCache.removeIf(key -> isTooFar(key, playerCx, playerCz, dist + 2));
    }

    private boolean isTooFar(long key, int playerCx, int playerCz, int dist) {
        ChunkPos cp = new ChunkPos(key);
        return Math.abs(cp.x - playerCx) > dist || Math.abs(cp.z - playerCz) > dist;
    }

    private boolean isWithinSimulationDistance(int cx, int cz) {
        if (mc.player == null) return false;
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int d = simulationDistance.get();
        return Math.abs(cx - pcx) <= d && Math.abs(cz - pcz) <= d;
    }
}
