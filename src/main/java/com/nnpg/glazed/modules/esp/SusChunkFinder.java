package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BedBlock;
import net.minecraft.state.property.Properties;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import com.nnpg.glazed.GlazedAddon;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SusChunkFinder extends Module {

    private final SettingGroup sgGeneral   = settings.createGroup("General");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgHeuristic = settings.createGroup("AFK Filters");

    // ==========================================
    // CÀI ĐẶT GIAO DIỆN
    // ==========================================
    
    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét (Chunks). Tối đa 10 để chống lag.")
            .defaultValue(4).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Mức độ nhạy (Điểm nghi ngờ).")
            .defaultValue(20).min(1).max(20).sliderRange(1, 20)
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của khối màu đỏ.")
            .defaultValue(40).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    private final Setting<Boolean> filterKelp = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("kelp").description("Quét Tảo bẹ đạt tuổi thọ tối đa.")
            .defaultValue(false).build() 
    );
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("cave-vines").description("Quét Dây leo hang đạt tuổi thọ tối đa.")
            .defaultValue(false).build() 
    );
    private final Setting<Boolean> filterVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("vines").description("Quét sự lan tràn bất thường của Dây leo (>50 khối).")
            .defaultValue(true).build()  
    );
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Quét sự phát triển của cụm Thạch anh.")
            .defaultValue(true).build()  
    );
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate").description("Quét Đá phiến bị xoay sai trục (Do người đặt).")
            .defaultValue(false).build() 
    );

    // ==========================================
    // HỆ THỐNG LÕI
    // ==========================================

    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);
    
    // BIẾN CACHE REFLECTION: Chống lỗi đổi tên hàm của Minecraft và tối ưu siêu tốc
    private static java.lang.reflect.Field cachedBlockEntitiesField = null;

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar siêu cấp quét Base ngầm, NBT và cấu trúc đào.");
    }

    @Override
    public void onActivate() { renderCache.clear(); }

    @Override
    public void onDeactivate() { renderCache.clear(); }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            handleChunkData(packet);
        }
    }

    private void handleChunkData(ChunkDataS2CPacket packet) {
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();

        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        if (renderCache.containsKey(key)) return;

        // BƯỚC 0: Quét Siêu Stash bằng dung lượng gói tin
        try {
            int packetSize = packet.getChunkData().getSectionsDataBuf().readableBytes();
            if (packetSize > 100000) { 
                renderCache.put(key, new int[]{ cx * 16, cz * 16 });
                ChatUtils.warning("🚨 [SIÊU STASH] Phát hiện dung lượng cực lớn ở X:" + (cx*16) + " Z:" + (cz*16));
                return;
            }
        } catch (Exception ignored) {}

        // BƯỚC 1: Quét Rương/NBT bằng Reflection Cache (Bất tử trước mọi bản Update)
        int blockEntityCount = 0;
        try {
            Object chunkData = packet.getChunkData();
            if (chunkData != null) {
                if (cachedBlockEntitiesField == null) {
                    for (java.lang.reflect.Field f : chunkData.getClass().getDeclaredFields()) {
                        if (java.util.List.class.isAssignableFrom(f.getType())) {
                            f.setAccessible(true);
                            cachedBlockEntitiesField = f;
                            break;
                        }
                    }
                }
                if (cachedBlockEntitiesField != null) {
                    blockEntityCount = ((java.util.List<?>) cachedBlockEntitiesField.get(chunkData)).size();
                }
            }
        } catch (Exception ignored) {}

        if (blockEntityCount >= sensitivity.get()) {
            renderCache.put(key, new int[]{ cx * 16, cz * 16 });
            return;
        }

        // BƯỚC 2: Quét Sâu Đa Hình (Dùng ThreadPool chống lag)
        final int finalCx = cx, finalCz = cz;
        EXECUTOR.execute(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                if (chunk == null) return;

                if (computeSusScore(chunk) >= sensitivity.get()) {
                    renderCache.put(key, new int[]{ finalCx * 16, finalCz * 16 });
                }
            });
        });
    }

    // --- Thuật toán tính điểm ---
    private int computeSusScore(WorldChunk chunk) {
        int minY = mc.world.getBottomY();
        int scanMaxY = 40; 
        
        int susScore = 0;
        int vineCount = 0;
        int airCount = 0; 

        ChunkPos cp = chunk.getPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    
                    BlockState state = chunk.getBlockState(bp);
                    Block block = state.getBlock();

                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR) {
                        airCount++;
                        continue; 
                    }

                    // 1. DẤU VẾT BASE NHỎ (Siêu nhạy)
                    if (block == Blocks.CRAFTING_TABLE || block == Blocks.ENDER_CHEST || 
                        block == Blocks.ENCHANTING_TABLE || block == Blocks.ANVIL || 
                        block instanceof BedBlock || block == Blocks.FURNACE ||
                        block == Blocks.TORCH || block == Blocks.WALL_TORCH || block == Blocks.LANTERN ||
                        block == Blocks.SOUL_TORCH || block == Blocks.SOUL_WALL_TORCH) {
                        susScore += 20; 
                    }

                    // 2. VẬT LIỆU LẠ DƯỚI LÒNG ĐẤT 
                    if (block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS || block == Blocks.BIRCH_PLANKS ||
                        block == Blocks.DARK_OAK_PLANKS || block == Blocks.JUNGLE_PLANKS || block == Blocks.ACACIA_PLANKS ||
                        block == Blocks.GLASS || block == Blocks.WHITE_CONCRETE || 
                        block == Blocks.QUARTZ_BLOCK || block == Blocks.SMOOTH_QUARTZ || 
                        block == Blocks.OBSIDIAN) {
                        susScore += 5; 
                    }
                    if (block == Blocks.FARMLAND || block == Blocks.DIRT_PATH || block == Blocks.WHEAT || block == Blocks.POTATOES || block == Blocks.CARROTS) {
                        susScore += 10; 
                    }
                    if (block == Blocks.BEE_NEST) {
                        susScore += 15; 
                    }

                    // 3. ĐÁ PHIẾN XOAY
                    if (filterRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                        if (state.get(Properties.AXIS) != Direction.Axis.Y) {
                            susScore += 2; 
                        }
                    }

                    // 4. BỘ LỌC TÙY CHỌN KHÁC 
                    if (filterKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
                        if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) {
                            susScore += 3; 
                        }
                    }
                    if (filterCaveVines.get() && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
                        if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) {
                            susScore += 3;
                        }
                    }
                    if (filterAmethyst.get() && block == Blocks.AMETHYST_CLUSTER) {
                        susScore += 1;
                    }
                    if (filterVines.get() && block == Blocks.VINE) {
                        vineCount++;
                    }
                }
            }
        }

        if (filterVines.get() && vineCount > 50) {
            susScore += 5;
        }

        // Tính năng HẦM KHỔNG LỒ (Air Density)
        if (airCount > 8000) {
            susScore += 10;
        }

        return susScore;
    }

    // ==========================================
    // RENDER ĐỒ HỌA
    // ==========================================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || renderCache.isEmpty()) return;

        int a = alpha.get();
        Color sideColor = new Color(255, 0, 0, a);
        Color lineColor = new Color(255, 0, 0, Math.min(255, a + 80));

        int targetY = 50;
        pruneDistantChunks();

        for (var entry : renderCache.long2ObjectEntrySet()) {
            int[] coords = entry.getValue();
            int bx = coords[0];
            int bz = coords[1];

            event.renderer.box(
                bx,       targetY,       bz,
                bx + 16,  targetY + 0.1, bz + 16,
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
