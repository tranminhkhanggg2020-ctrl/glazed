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
    
    private final Setting<Integer> packetThreshold = sgGeneral.add(
        new IntSetting.Builder()
            .name("packet-threshold")
            .description("Ngưỡng byte gói tin để báo Stash (Mặc định: 60000).")
            .defaultValue(60000).min(20000).max(100000).sliderRange(30000, 80000)
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của thảm màu đỏ.")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    private final Setting<Boolean> scanPhysicalBase = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("scan-physical-base")
            .description("Quét Bàn chế tạo, Lò, Kính... (Bật ở server không Anti-cheat).")
            .defaultValue(false).build() 
    );

    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Palette Sniper: Quét thạch anh ẩn (Nên bật ở DonutSMP).")
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

    private final Map<Long, int[]> renderCache = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar siêu việt: Bắt Mega-Base, Stash và phá vỡ Anti-Xray.");
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

            // [VŨ KHÍ 1]: QUÉT DUNG LƯỢNG MẠNG (Bắt Mega-Base / Stash)
            try {
                PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
                int packetSize = buf.readableBytes();
                
                // Sử dụng Setting có thể chỉnh sửa trong GUI để linh hoạt với từng Server
                if (packetSize > packetThreshold.get()) {
                    renderCache.put(key, new int[]{ cx * 16, cz * 16 });
                    return; 
                }
            } catch (Exception ignored) {}

            // [VŨ KHÍ 2 & 3]: QUÉT HEURISTIC VÀ PALETTE
            final int finalCx = cx, finalCz = cz;
            EXECUTOR.execute(() -> {
                try {
                    Thread.sleep(50); // Chờ server map block vào client
                    
                    if (mc.world == null) return;
                    WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                    
                    if (chunk != null && !chunk.isEmpty()) {
                        int score = computeSusScore(chunk);
                        
                        // Độ nhạy 20 -> Ngưỡng 10 điểm | Độ nhạy 1 -> Ngưỡng 200 điểm
                        int threshold = (21 - sensitivity.get()) * 10;

                        if (score >= threshold) {
                            renderCache.put(key, new int[]{ finalCx * 16, finalCz * 16 });
                        }
                    }
                } catch (Exception e) {}
            });
        }
    }

    private int computeSusScore(WorldChunk chunk) {
        int susScore = 0;

        // --- [VŨ KHÍ 2]: PALETTE SNIPER (Vượt Anti-Xray 100%) ---
        if (filterAmethyst.get()) {
            for (ChunkSection section : chunk.getSectionArray()) {
                if (section != null && !section.isEmpty()) {
                    if (section.getBlockStateContainer().hasAny(state -> state.getBlock() == Blocks.AMETHYST_CLUSTER)) {
                        susScore += 100; // Đánh dấu đỏ ngay lập tức
                        break;
                    }
                }
            }
        }

        int startY = mc.world.getBottomY();
        int endY = mc.world.getBottomY() + mc.world.getHeight() - 1;
        
        int vineCount = 0;
        int maxUndergroundAir = 0; 
        int currentSectionAir = 0;

        ChunkPos cp = chunk.getPos();

        for (int y = startY; y <= endY; y++) {
            // Reset bộ đếm khi qua một tầng Section mới (16 block)
            if (y % 16 == 0) {
                // Chỉ chốt sổ kết quả không khí nếu chúng ta đang ở dưới lòng đất (Y < 50)
                if (y <= 50 && currentSectionAir > maxUndergroundAir) {
                    maxUndergroundAir = currentSectionAir;
                }
                currentSectionAir = 0;
            }

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    BlockState state = chunk.getBlockState(bp);
                    Block block = state.getBlock();

                    // [VŨ KHÍ 3]: ĐO HẦM KHÔNG KHÍ NGẦM (Chặn đếm bầu trời)
                    if (y < 50 && (block == Blocks.AIR || block == Blocks.CAVE_AIR)) {
                        currentSectionAir++;
                        continue; 
                    }

                    // Tính năng quét Base vật lý (Dành cho Server thường)
                    if (scanPhysicalBase.get() && y < 100) {
                        if (block == Blocks.CRAFTING_TABLE || block == Blocks.ENDER_CHEST || 
                            block == Blocks.FURNACE || block instanceof BedBlock ||
                            block == Blocks.ENCHANTING_TABLE || block == Blocks.ANVIL ||
                            block == Blocks.BREWING_STAND) {
                            susScore += 50; 
                        }
                        if (block == Blocks.OAK_PLANKS || block == Blocks.SPRUCE_PLANKS || 
                            block == Blocks.GLASS || block == Blocks.WHITE_CONCRETE || 
                            block == Blocks.OBSIDIAN) {
                            susScore += 5; 
                        }
                    }

                    // Heuristics Dấu vết (Tương thích mọi server)
                    if (filterRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                        if (state.get(Properties.AXIS) != Direction.Axis.Y) {
                            susScore += 10; 
                        }
                    }

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
                    
                    if (filterVines.get() && block == Blocks.VINE) {
                        vineCount++;
                        susScore += 1; // Điểm nhỏ, tích tiểu thành đại
                    }
                }
            }
        }
        
        // --- TỔNG KẾT ĐIỂM QUY MÔ ---
        if (filterVines.get() && vineCount > 80) {
            susScore += 30; // Trồng quá nhiều dây leo -> Bất thường
        }

        // Mega-Trench Detection: Nếu một tầng 16x16x16 ngầm có > 3000 khối không khí -> Có người đã đào rỗng!
        if (maxUndergroundAir > 3000) susScore += 50; 
        if (maxUndergroundAir > 3800) susScore += 100; 

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

        pruneDistantChunks();

        double targetY = 50.0;

        for (Map.Entry<Long, int[]> entry : renderCache.entrySet()) {
            int[] coords = entry.getValue();
            int bx = coords[0];
            int bz = coords[1];

            // Vẽ "Thảm đỏ" ở Y=50
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
