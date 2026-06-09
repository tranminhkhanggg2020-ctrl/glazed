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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SusChunkFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ==========================================
    // CÀI ĐẶT GIAO DIỆN (Trùng khớp 100% hình ảnh)
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
            .description("Độ nhạy radar (Krypton Entity Threshold).")
            .defaultValue(3).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của thảm Krypton (Y=62).")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    private final Setting<Boolean> filterKelp = sgGeneral.add(
        new BoolSetting.Builder().name("kelp").defaultValue(false).build()
    );

    private final Setting<Boolean> filterCaveVines = sgGeneral.add(
        new BoolSetting.Builder().name("cave-vines").defaultValue(false).build()
    );

    private final Setting<Boolean> filterVines = sgGeneral.add(
        new BoolSetting.Builder().name("vines").defaultValue(false).build()
    );

    private final Setting<Boolean> filterAmethyst = sgGeneral.add(
        new BoolSetting.Builder().name("amethyst").defaultValue(true).build()
    );

    private final Setting<Boolean> filterBamboo = sgGeneral.add(
        new BoolSetting.Builder().name("bamboo").defaultValue(false).build()
    );

    private final Setting<Boolean> filterBeeNest = sgGeneral.add(
        new BoolSetting.Builder().name("bee-nest").defaultValue(false).build()
    );

    private final Setting<Boolean> filterRotatedDeepslate = sgGeneral.add(
        new BoolSetting.Builder().name("rotated-deepslate").defaultValue(false).build()
    );

    // ==========================================
    // HỆ THỐNG LÕI & CACHE
    // ==========================================

    private final Map<Long, ChunkPos> renderCache = new ConcurrentHashMap<>();
    private final Map<Long, Integer> chunkSusScores = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Dò Base chuẩn Krypton Client: Đếm thực thể chìm & Lọc Heuristic. Trải thảm ở Y=62.");
    }

    @Override
    public void onActivate() { 
        renderCache.clear(); 
        chunkSusScores.clear();
    }

    @Override
    public void onDeactivate() { 
        renderCache.clear(); 
        chunkSusScores.clear();
    }

    // ==========================================
    // VŨ KHÍ 1: KRYPTON DEEP PACKET INSPECTION
    // ==========================================
    
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // BẮT GÓI TIN CHUNK GỐC (Krypton BlockEntity Counter)
        if (event.packet instanceof ChunkDataS2CPacket chunkPacket) {
            int cx = chunkPacket.getChunkX();
            int cz = chunkPacket.getChunkZ();

            if (!isWithinSimulationDistance(cx, cz)) return;

            ChunkPos pos = new ChunkPos(cx, cz);
            long key = pos.toLong();

            if (renderCache.containsKey(key)) return;

            try {
                // Đếm trực tiếp BlockEntity bị giấu (Rương, Lò nung, v.v.)
                List<?> blockEntities = chunkPacket.getBlockEntities();
                int beCount = (blockEntities != null) ? blockEntities.size() : 0;

                // Công thức tính ngưỡng linh động theo Sensitivity của bạn (VD: Sens 3 -> Cần ~80 Entities)
                int threshold = (11 - sensitivity.get()) * 10; 

                if (beCount >= threshold) {
                    renderCache.put(key, pos);
                    return;
                }
            } catch (Exception ignored) {}

            // Chạy phân tích Heuristic theo khối nếu packet không vượt ngưỡng
            runHeuristicScan(cx, cz, key, pos);
        }

        // BẮT GÓI TIN THỰC THỂ (Entity Sniping)
        else if (event.packet instanceof EntitySpawnS2CPacket spawnPacket) {
            EntityType<?> type = spawnPacket.getEntityType();
            // Phát hiện khung vật phẩm, xe mỏ có rương, giá để giáp
            if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME || 
                type == EntityType.CHEST_MINECART || type == EntityType.ARMOR_STAND) {
                
                int cx = ((int) spawnPacket.getX()) >> 4;
                int cz = ((int) spawnPacket.getZ()) >> 4;
                addSusScore(cx, cz, 15); // Tăng điểm nghi ngờ rất cao
            }
        }

        // BẮT MICRO-PACKETS (Dữ liệu Redstone/Lò nung cập nhật lẻ tẻ)
        else if (event.packet instanceof BlockEntityUpdateS2CPacket updatePacket) {
            BlockPos bPos = updatePacket.getPos();
            addSusScore(bPos.getX() >> 4, bPos.getZ() >> 4, 5);
        }
    }

    // ==========================================
    // VŨ KHÍ 2: HEURISTIC BLOCK SCANNING (Các Checkbox)
    // ==========================================

    private void runHeuristicScan(int cx, int cz, long key, ChunkPos pos) {
        // Chỉ chạy các vòng lặp này nếu có ít nhất 1 checkbox được bật
        if (!filterKelp.get() && !filterCaveVines.get() && !filterVines.get() && 
            !filterAmethyst.get() && !filterBamboo.get() && !filterBeeNest.get() && 
            !filterRotatedDeepslate.get()) return;

        mc.execute(() -> {
            if (mc.world == null) return;
            WorldChunk chunk = mc.world.getChunk(cx, cz);
            
            if (chunk != null && !chunk.isEmpty()) {
                EXECUTOR.execute(() -> {
                    try {
                        int score = 0;
                        ChunkSection[] sections = chunk.getSectionArray();

                        for (ChunkSection section : sections) {
                            if (section == null || section.isEmpty()) continue;
                            PalettedContainer<BlockState> container = section.getBlockStateContainer();

                            // Quét nhanh qua Palette trước khi đi vào chi tiết
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

                                        if (filterAmethyst.get() && b == Blocks.AMETHYST_CLUSTER) score += 20;
                                        if (filterBeeNest.get() && b == Blocks.BEE_NEST) score += 10;
                                        if (filterBamboo.get() && b == Blocks.BAMBOO) score += 5;
                                        if (filterVines.get() && b == Blocks.VINE) score += 2;

                                        if (filterRotatedDeepslate.get() && b == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                                            if (state.get(Properties.AXIS) != Direction.Axis.Y) score += 50; 
                                        }
                                        if (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) {
                                            if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) score += 15; 
                                        }
                                        if (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT)) {
                                            if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) score += 15;
                                        }
                                    }
                                }
                            }
                        }

                        // Ngưỡng phát hiện của khối phụ thuộc vào SENSITIVITY
                        if (score >= (11 - sensitivity.get()) * 30) {
                            renderCache.put(key, pos);
                        }
                    } catch (Exception ignored) {}
                });
            }
        });
    }

    private void addSusScore(int cx, int cz, int amount) {
        if (!isWithinSimulationDistance(cx, cz)) return;
        long key = ChunkPos.toLong(cx, cz);
        
        if (renderCache.containsKey(key)) return;

        int currentScore = chunkSusScores.getOrDefault(key, 0) + amount;
        chunkSusScores.put(key, currentScore);

        if (currentScore >= (11 - sensitivity.get()) * 15) {
            renderCache.put(key, new ChunkPos(cx, cz));
            chunkSusScores.remove(key); 
        }
    }

    // ==========================================
    // RENDER ĐỒ HỌA (KRYPTON STYLE: THẢM PHẲNG Y=62)
    // ==========================================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || renderCache.isEmpty()) return;

        int a = alpha.get();
        Color sideColor = new Color(255, 0, 0, a);
        Color lineColor = new Color(255, 0, 0, 255); 

        pruneDistantChunks();

        // Cố định độ cao ở Y = 62 (Mặt nước / Tầm nhìn tối ưu)
        double targetY = 62.0;

        for (ChunkPos pos : renderCache.values()) {
            int bx = pos.getStartX();
            int bz = pos.getStartZ();

            // Vẽ một lớp thảm đỏ phẳng lì bao phủ toàn bộ chunk (16x16)
            event.renderer.box(
                bx,      targetY,       bz,
                bx + 16, targetY + 0.1, bz + 16,
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

        renderCache.entrySet().removeIf(entry -> {
            ChunkPos cp = new ChunkPos(entry.getKey());
            return Math.abs(cp.x - playerCx) > dist || Math.abs(cp.z - playerCz) > dist;
        });
    }

    private boolean isWithinSimulationDistance(int cx, int cz) {
        if (mc.player == null) return false;
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int d = simulationDistance.get();
        return Math.abs(cx - pcx) <= d && Math.abs(cz - pcz) <= d;
    }
}
