package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.BedBlock;
import net.minecraft.state.property.Properties;
import net.minecraft.network.PacketByteBuf;

import com.nnpg.glazed.GlazedAddon;

import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

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
            .description("Độ trong suốt của thảm màu đỏ.")
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
    // HỆ THỐNG LÕI (Đã gỡ bỏ Executor đa luồng)
    // ==========================================

    private final Map<Long, int[]> renderCache = new ConcurrentHashMap<>();
    private final Queue<ChunkPos> scanQueue = new ConcurrentLinkedQueue<>();

    private static final int PACKET_STASH_THRESHOLD = 65000;

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar đa địa hình, tích hợp Packet, Queue Threading & Palette Sniping.");
    }

    @Override
    public void onActivate() { 
        renderCache.clear(); 
        scanQueue.clear();
    }

    @Override
    public void onDeactivate() { 
        renderCache.clear(); 
        scanQueue.clear();
    }

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

            // [VŨ KHÍ 1]: QUÉT DUNG LƯỢNG MẠNG TỰ ĐỘNG
            try {
                PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
                int packetSize = buf.readableBytes();
                
                if (packetSize > PACKET_STASH_THRESHOLD) {
                    renderCache.put(key, new int[]{ cx * 16, cz * 16 });
                    return; 
                }
            } catch (Exception ignored) {}

            // Đưa vào hàng đợi để chờ Chunk Load an toàn tuyệt đối trên luồng chính
            scanQueue.add(pos);
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        // Dọn dẹp cache ở đây để chống mù packet khi đi xa
        pruneDistantChunks();

        // Xử lý tối đa 5 chunk mỗi Tick (100 chunk/giây) để đảm bảo không rớt Base
        int processed = 0;
        while (!scanQueue.isEmpty() && processed < 5) {
            ChunkPos pos = scanQueue.poll();
            if (pos == null) continue;

            long key = pos.toLong();
            if (renderCache.containsKey(key)) continue;

            // Đảm bảo chắc chắn 100% chunk đã tồn tại vật lý trong Client World
            if (mc.world.getChunkManager().isChunkLoaded(pos.x, pos.z)) {
                WorldChunk chunk = mc.world.getChunk(pos.x, pos.z);
                if (chunk != null && !chunk.isEmpty()) {
                    int score = computeSusScore(chunk);
                    int threshold = (21 - sensitivity.get()) * 10;

                    if (score >= threshold) {
                        renderCache.put(key, new int[]{ pos.x * 16, pos.z * 16 });
                    }
                }
            } else {
                // Nếu chunk chưa load kịp, nhét lại vào hàng đợi để tick sau kiểm tra lại (KHÔNG BAO GIỜ BỎ LỌT)
                scanQueue.add(pos);
            }
            processed++;
        }
    }

    private int computeSusScore(WorldChunk chunk) {
        int susScore = 0;
        int vineCount = 0;
        int maxUndergroundAir = 0; 
        int artificialBlocks = 0; 
        int functionalBlocks = 0; 

        boolean checkAmethyst = filterAmethyst.get();

        for (ChunkSection section : chunk.getSectionArray()) {
            if (section == null || section.isEmpty()) continue;

            int sectionY = section.getYOffset();
            boolean checkAir = sectionY < 50;

            // --- PALETTE SNIPER MỞ RỘNG CẤP ĐỘ SECTION ---
            // Chỉ cần section này có chứa DẤU VẾT KHẢ NGHI, ta mới đào sâu. 
            // Nếu là núi đá đặc tự nhiên, skip toàn bộ 4096 khối -> Nhanh & Nhạy 100%.
            boolean hasSusBlocks = section.getBlockStateContainer().hasAny(state -> {
                Block b = state.getBlock();
                return b == Blocks.ENDER_CHEST || b == Blocks.ENCHANTING_TABLE || b == Blocks.ANVIL || b == Blocks.BEACON ||
                       b == Blocks.CRAFTING_TABLE || b == Blocks.FURNACE || b instanceof BedBlock || b == Blocks.BREWING_STAND ||
                       b == Blocks.OAK_PLANKS || b == Blocks.SPRUCE_PLANKS || b == Blocks.GLASS || b == Blocks.WHITE_CONCRETE || b == Blocks.OBSIDIAN ||
                       b == Blocks.DEEPSLATE || b == Blocks.VINE || (checkAmethyst && b == Blocks.AMETHYST_CLUSTER) ||
                       (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) ||
                       (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT));
            });

            // Nếu Palette sạch bóng VÀ không nằm ở độ sâu cần đếm hầm rỗng ngầm -> Bỏ qua section này
            if (!hasSusBlocks && !checkAir) continue;

            int currentSectionAir = 0;

            // Quét cục bộ section (chạy siêu mượt vì đã được Palette lọc bớt 80% thế giới rác)
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = section.getBlockState(x, y, z);
                        Block block = state.getBlock();

                        if (checkAir && (block == Blocks.AIR || block == Blocks.CAVE_AIR)) {
                            currentSectionAir++;
                            continue;
                        }

                        if (!hasSusBlocks) continue;

                        // TIER 1
                        if (block == Blocks.ENDER_CHEST || block == Blocks.ENCHANTING_TABLE || 
                            block == Blocks.ANVIL || block == Blocks.BEACON) {
                            susScore += 100; 
                        }
                        // TIER 2
                        else if (block == Blocks.CRAFTING_TABLE || block == Blocks.FURNACE || 
                                 block instanceof BedBlock || block == Blocks.BREWING_STAND) {
                            functionalBlocks++;
                        }
                        // TIER 3
                        else if (block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS || 
                                 block == Blocks.GLASS || block == Blocks.WHITE_CONCRETE || 
                                 block == Blocks.OBSIDIAN) {
                            artificialBlocks++;
                        }

                        // HEURISTICS DẤU VẾT
                        if (filterRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                            if (state.get(Properties.AXIS) != Direction.Axis.Y) susScore += 10; 
                        }
                        if (filterKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
                            if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) susScore += 3; 
                        }
                        if (filterCaveVines.get() && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
                            if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) susScore += 3;
                        }
                        if (filterVines.get() && block == Blocks.VINE) {
                            vineCount++;
                            susScore += 1; 
                        }
                        if (checkAmethyst && block == Blocks.AMETHYST_CLUSTER) {
                            susScore += 100;
                        }
                    }
                }
            }

            if (checkAir && currentSectionAir > maxUndergroundAir) {
                maxUndergroundAir = currentSectionAir;
            }
        }
        
        // --- CHỐT TỔNG ĐIỂM NHƯ CŨ ---
        if (functionalBlocks >= 4) susScore += 50; 
        if (artificialBlocks > 400) susScore += 50; 
        if (filterVines.get() && vineCount > 80) susScore += 30; 
        
        // Cập nhật an toàn: Trench/Hầm chỉ báo đỏ nếu có dấu vết can thiệp nhân tạo (chống hầm cheese tự nhiên)
        if (maxUndergroundAir > 3000 && (artificialBlocks > 0 || functionalBlocks > 0)) susScore += 50; 
        if (maxUndergroundAir > 3800 && (artificialBlocks > 0 || functionalBlocks > 0)) susScore += 100; 

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

        double targetY = 50.0;

        for (Map.Entry<Long, int[]> entry : renderCache.entrySet()) {
            int[] coords = entry.getValue();
            int bx = coords[0];
            int bz = coords[1];

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
