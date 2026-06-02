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
            .description("Độ nhạy của Radar (1 = Thấp, 20 = Rất nhạy).")
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
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Quét Bảng màu (Palette) tìm Thạch anh ẩn (Bypass Anti-Xray).")
            .defaultValue(true).build()  // Bật mặc định giống trick của Krypton
    );
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate").description("Quét Đá phiến bị xoay sai trục (Do người đặt).")
            .defaultValue(false).build() 
    );

    // ==========================================
    // HỆ THỐNG LÕI
    // ==========================================

    private final Map<Long, int[]> renderCache = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar tích hợp Palette Sniper phân tích mạng và địa hình.");
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

            // [VŨ KHÍ 1]: QUÉT DUNG LƯỢNG MẠNG CHỐNG ANTI-XRAY
            try {
                PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
                int packetSize = buf.readableBytes();
                
                if (packetSize > 60000) {
                    renderCache.put(key, new int[]{ cx * 16, cz * 16 });
                    return; 
                }
            } catch (Exception ignored) {}

            // [VŨ KHÍ 2]: QUÉT HEURISTIC & PALETTE SNIPER
            final int finalCx = cx, finalCz = cz;
            EXECUTOR.execute(() -> {
                try {
                    Thread.sleep(50); 
                    
                    if (mc.world == null) return;
                    WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                    
                    if (chunk != null && !chunk.isEmpty()) {
                        int score = computeSusScore(chunk);
                        
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

        // --- [VŨ KHÍ TỐI THƯỢNG]: PALETTE SNIPER (Bắn tỉa Bảng màu) ---
        // Vượt qua hoàn toàn Anti-Xray bằng cách đọc danh sách khối vật liệu thay vì nhìn vào map.
        if (filterAmethyst.get()) {
            boolean foundHiddenAmethyst = false;
            for (ChunkSection section : chunk.getSectionArray()) {
                if (section != null && !section.isEmpty()) {
                    // hasAny() quét thẳng vào Bảng từ điển (Palette) của Section.
                    // Tốc độ ánh sáng (chỉ check vài từ) và không thể bị đánh lừa bởi Anti-Xray.
                    if (section.getBlockStateContainer().hasAny(state -> state.getBlock() == Blocks.AMETHYST_CLUSTER)) {
                        foundHiddenAmethyst = true;
                        break;
                    }
                }
            }
            if (foundHiddenAmethyst) {
                // Điểm cực cao (100). Nếu độ nhạy (Sensitivity) > 10, sẽ lập tức báo đỏ!
                susScore += 100; 
            }
        }

        // --- QUÉT HEURISTIC VẬT LÝ VÀ HẦM NGẦM ---
        int startY = mc.world.getBottomY();
        int endY = mc.world.getBottomY() + mc.world.getHeight() - 1;
        
        int vineCount = 0;
        int maxAirInSection = 0; 
        int currentSectionAir = 0;

        ChunkPos cp = chunk.getPos();

        for (int y = startY; y <= endY; y++) {
            if (y % 16 == 0) {
                if (currentSectionAir > maxAirInSection) maxAirInSection = currentSectionAir;
                currentSectionAir = 0;
            }

            for (int x = 0; x < 16; x++) {
                for (int z = 0; z < 16; z++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    BlockState state = chunk.getBlockState(bp);
                    Block block = state.getBlock();

                    if (block == Blocks.AIR || block == Blocks.CAVE_AIR) {
                        currentSectionAir++;
                        continue; 
                    }

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
                        susScore += 1;
                    }
                }
            }
        }
        
        if (currentSectionAir > maxAirInSection) maxAirInSection = currentSectionAir;

        // TÍNH ĐIỂM QUY MÔ 
        if (filterVines.get() && vineCount > 80) {
            susScore += 30; 
        }

        if (maxAirInSection > 3000) {
            susScore += 50; 
        }
        if (maxAirInSection > 3800) {
            susScore += 100; 
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

        pruneDistantChunks();

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
