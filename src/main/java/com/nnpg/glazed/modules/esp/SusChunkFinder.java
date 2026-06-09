package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BedBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.network.PacketByteBuf;

import com.nnpg.glazed.GlazedAddon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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
            .description("Bán kính quét (Chunks). Tối đa 10.")
            .defaultValue(4).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Độ nhạy (1 = Khó tính, 20 = Cực nhạy).")
            .defaultValue(10).min(1).max(20).sliderRange(1, 20)
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của khung màu đỏ.")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Palette Sniper: Quét thạch anh ẩn (Bypass Anti-Xray).")
            .defaultValue(true).build()  
    );
    
    private final Setting<Boolean> filterKelp = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("kelp").description("Quét Tảo bẹ đạt tuổi thọ tối đa (Do AFK lâu).")
            .defaultValue(false).build() 
    );
    
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("cave-vines").description("Quét Dây leo hang đạt tuổi thọ tối đa.")
            .defaultValue(false).build() 
    );
    
    private final Setting<Boolean> filterVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("vines").description("Quét sự lan tràn bất thường của Dây leo.")
            .defaultValue(false).build()  
    );
    
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate").description("Quét Đá phiến bị xoay sai trục.")
            .defaultValue(true).build() 
    );

    // ==========================================
    // HỆ THỐNG LÕI
    // ==========================================

    // Mảng int[] lưu: {startX, minY, startZ, maxY}
    private final Map<Long, int[]> renderCache = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    private static final int PACKET_STASH_THRESHOLD = 65000;

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar đa địa hình tự động hoàn toàn, tích hợp Packet & Palette Sniping.");
    }

    @Override
    public void onActivate() { renderCache.clear(); }

    @Override
    public void onDeactivate() { renderCache.clear(); }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            int cx = packet.getChunkX();
            int cz = packet.getChunkZ();

            if (!isWithinSimulationDistance(cx, cz)) return;

            ChunkPos pos = new ChunkPos(cx, cz);
            long key = pos.toLong();

            if (renderCache.containsKey(key)) return;

            // [VŨ KHÍ 1]: QUÉT DUNG LƯỢNG MẠNG
            try {
                PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
                int packetSize = buf.readableBytes();
                
                if (packetSize > PACKET_STASH_THRESHOLD) {
                    // Tô đỏ toàn bộ cột Chunk từ đáy lên đỉnh vì không rõ Y
                    renderCache.put(key, new int[]{ cx * 16, mc.world.getBottomY(), cz * 16, mc.world.getTopY() });
                    return; 
                }
            } catch (Exception ignored) {}

            // [VŨ KHÍ 2 & 3]: HEURISTIC VÀ PALETTE
            final int finalCx = cx, finalCz = cz;
            
            // Xử lý Thread-Safe: Lấy Chunk trên Main Thread trước
            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                
                if (chunk != null && !chunk.isEmpty()) {
                    EXECUTOR.execute(() -> {
                        try {
                            // Struct trả về: [0] = Score, [1] = Target Min Y, [2] = Target Max Y
                            int[] result = computeSusScore(chunk);
                            int score = result[0];
                            int threshold = (21 - sensitivity.get()) * 10;

                            if (score >= threshold) {
                                renderCache.put(key, new int[]{ finalCx * 16, result[1], finalCz * 16, result[2] });
                            }
                        } catch (Exception ignored) {}
                    });
                }
            });
        }
    }

    private int[] computeSusScore(WorldChunk chunk) {
        int totalSusScore = 0;
        int highestSectionScore = 0;
        int bestMinY = mc.world.getBottomY();
        int bestMaxY = mc.world.getTopY();
        
        int vineCount = 0;
        int maxUndergroundAir = 0; 
        int artificialBlocks = 0;
        int functionalBlocks = 0;

        ChunkPos cp = chunk.getPos();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            int sectionScore = 0;
            PalettedContainer<BlockState> container = section.getBlockStateContainer();
            int sectionY = (chunk.getBottomSectionCoord() + i) * 16;

            // --- PALETTE SNIPER (FAST-FAIL) ---
            
            // Tối ưu Amethyst: Giảm điểm cộng để tránh báo động giả với Geode tự nhiên
            if (filterAmethyst.get() && container.hasAny(state -> state.getBlock() == Blocks.AMETHYST_CLUSTER)) {
                sectionScore += 15;
            }

            if (container.hasAny(state -> {
                Block b = state.getBlock();
                return b == Blocks.ENDER_CHEST || b == Blocks.ENCHANTING_TABLE || b == Blocks.ANVIL || b == Blocks.BEACON;
            })) {
                sectionScore += 100; 
            }

            boolean hasTier2 = container.hasAny(state -> {
                Block b = state.getBlock();
                return b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b instanceof BedBlock || b == Blocks.BREWING_STAND;
            });
            
            boolean hasTier3 = container.hasAny(state -> {
                Block b = state.getBlock();
                return b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS || b == Blocks.GLASS || b == Blocks.WHITE_CONCRETE || b == Blocks.OBSIDIAN;
            });

            boolean hasHeuristics = container.hasAny(state -> {
                Block b = state.getBlock();
                return (filterRotatedDeepslate.get() && b == Blocks.DEEPSLATE) ||
                       (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) ||
                       (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT)) ||
                       (filterVines.get() && b == Blocks.VINE);
            });

            boolean isUnderground = sectionY < 50;
            int currentSectionAir = 0;

            if (hasTier2 || hasTier3 || hasHeuristics || isUnderground) {
                for (int x = 0; x < 16; x++) {
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            int worldY = sectionY + y;
                            BlockPos bp = new BlockPos(cp.getStartX() + x, worldY, cp.getStartZ() + z);
                            BlockState state = chunk.getBlockState(bp);
                            Block block = state.getBlock();

                            if (isUnderground && (block == Blocks.AIR || block == Blocks.CAVE_AIR)) {
                                currentSectionAir++;
                                continue;
                            }

                            if (hasTier2 && (block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || block instanceof BedBlock || block == Blocks.BREWING_STAND)) {
                                functionalBlocks++;
                            }
                            
                            if (hasTier3 && (block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS || block == Blocks.GLASS || block == Blocks.WHITE_CONCRETE || block == Blocks.OBSIDIAN)) {
                                artificialBlocks++;
                            }

                            if (hasHeuristics) {
                                if (filterRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                                    if (state.get(Properties.AXIS) != Direction.Axis.Y) sectionScore += 10; 
                                }

                                if (filterKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
                                    if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) sectionScore += 3; 
                                }
                                
                                if (filterCaveVines.get() && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
                                    if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) sectionScore += 3;
                                }
                                
                                if (filterVines.get() && block == Blocks.VINE) {
                                    vineCount++;
                                    sectionScore += 1; 
                                }
                            }
                        }
                    }
                }
                
                if (isUnderground && currentSectionAir > maxUndergroundAir) {
                    maxUndergroundAir = currentSectionAir;
                }
            }

            totalSusScore += sectionScore;

            // Xác định vị trí Y nghi ngờ nhất
            if (sectionScore > highestSectionScore) {
                highestSectionScore = sectionScore;
                bestMinY = sectionY;
                bestMaxY = sectionY + 16;
            }
        }
        
        // Cộng thêm điểm thưởng từ tổng thể
        if (functionalBlocks >= 4) totalSusScore += 50; 
        if (artificialBlocks > 400) totalSusScore += 50; 
        if (filterVines.get() && vineCount > 80) totalSusScore += 30; 
        
        if (maxUndergroundAir > 3000) totalSusScore += 50; 
        if (maxUndergroundAir > 3800) totalSusScore += 100; 

        // Nếu điểm cao chỉ nhờ Air, vẽ bounding box bao quanh khu vực có Air
        if (highestSectionScore == 0 && maxUndergroundAir > 3000) {
            bestMinY = mc.world.getBottomY();
            bestMaxY = 50;
        }

        return new int[]{totalSusScore, bestMinY, bestMaxY};
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

        pruneDistantChunks();

        for (Map.Entry<Long, int[]> entry : renderCache.entrySet()) {
            int[] coords = entry.getValue();
            int bx = coords[0];
            int minY = coords[1];
            int bz = coords[2];
            int maxY = coords[3];

            // Render khung 3D chính xác tại độ cao phát hiện
            event.renderer.box(
                bx,      minY, bz,
                bx + 16, maxY, bz + 16,
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
