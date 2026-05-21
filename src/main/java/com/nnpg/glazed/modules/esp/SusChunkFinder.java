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
import net.minecraft.world.chunk.WorldChunk;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import com.nnpg.glazed.GlazedAddon;

public class SusChunkFinder extends Module {

    // -------------------------------------------------------------------------
    // Settings Groups
    // -------------------------------------------------------------------------

    private final SettingGroup sgGeneral = settings.createGroup("General");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // --- General ---
    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét (Chunks). Chunk ngoài vùng này sẽ bị xóa khỏi bộ nhớ.")
            .defaultValue(4).min(1).max(32).sliderRange(1, 32)
            .build()
    );

    private final Setting<Double> airRatioThreshold = sgGeneral.add(
        new DoubleSetting.Builder()
            .name("air-ratio-threshold")
            .description("Tỷ lệ Bọng Không Khí. > 0.35 (35%) thường là hang nhân tạo (Base).")
            .defaultValue(0.35).min(0.1).max(1.0).sliderRange(0.1, 1.0)
            .build()
    );

    private final Setting<Integer> afkGrowthThreshold = sgGeneral.add(
        new IntSetting.Builder()
            .name("afk-growth-threshold")
            .description("Số lượng khối già (Kelp age=25, Amethyst Cluster...) để kết luận có người AFK lâu.")
            .defaultValue(20).min(1).max(200).sliderRange(5, 100)
            .build()
    );

    // --- Render ---
    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của ô vuông đỏ.")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    // -------------------------------------------------------------------------
    // Internal State
    // -------------------------------------------------------------------------

    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Base Finder V2: Quét Bọng không khí & Dấu vết AFK (Khối mọc lâu năm).");
    }

    @Override
    public void onActivate() {
        renderCache.clear();
    }

    @Override
    public void onDeactivate() {
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
        }
    }

    private void handleChunkData(ChunkDataS2CPacket packet) {
        // Trả lại hàm chuẩn của 1.21.4, xóa bỏ cái try-catch gây lỗi
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();

        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        // Đã cắm cờ rồi thì bỏ qua
        if (renderCache.containsKey(key)) return;

        final int finalCx = cx, finalCz = cz;

        // Schedule check cho Heuristic 2 (Không khí & Thực vật già)
        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                if (chunk == null) return;

                if (isChunkSus(chunk)) {
                    renderCache.put(key, new int[]{ finalCx * 16, finalCz * 16 });
                }
            });
        }, "SusChunkFinder-AFK-Scan").start();
    }
    // -------------------------------------------------------------------------
    // Phân tích Mũi nhọn 2: Không Khí (Air Pocket) & Tuổi Thọ Thực Vật (AFK Growth)
    // -------------------------------------------------------------------------

    private boolean isChunkSus(WorldChunk chunk) {
        int minY = mc.world.getBottomY();
        // Chỉ quét từ đáy lên Y=40 (Khu vực ngầm) để tối ưu FPS và không bị nhiễu bởi mặt đất
        int scanMaxY = 40; 
        
        int airCount = 0;
        int solidCount = 0;
        
        int afkGrowthCount = 0;
        int vineCount = 0;

        ChunkPos cp = chunk.getPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    
                    // ULTIMATE BYPASS: Lấy tên khối dưới dạng chuỗi
                    String stateStr = chunk.getBlockState(bp).toString().toLowerCase();

                    // 1. Phân tích Không Khí vs Khối Đặc
                    if (stateStr.contains("minecraft:air") || stateStr.contains("minecraft:cave_air")) {
                        airCount++;
                    } else if (stateStr.contains("stone") || stateStr.contains("deepslate") || stateStr.contains("tuff")) {
                        solidCount++;
                    }

                    // 2. Phân tích Thực Vật "Già" (Bằng chứng có người đứng AFK)
                    // Kelp đạt tuổi thọ tối đa (age=25)
                    if (stateStr.contains("minecraft:kelp") && stateStr.contains("age=25")) {
                        afkGrowthCount++;
                    }
                    // Cave Vines (Glow Berries) đạt tuổi thọ tối đa (age=25)
                    else if (stateStr.contains("minecraft:cave_vines") && stateStr.contains("age=25")) {
                        afkGrowthCount++;
                    }
                    // Thạch anh đã nở to hết cỡ (Amethyst Cluster)
                    else if (stateStr.contains("minecraft:amethyst_cluster")) {
                        afkGrowthCount++;
                    }
                    // Dây leo (Vines) lan tràn cực mạnh
                    else if (stateStr.contains("minecraft:vine")) {
                        vineCount++;
                    }
                }
            }
        }

        // Logic 1: Bọng Không Khí Nhân Tạo
        float totalUnderground = airCount + solidCount;
        if (totalUnderground > 0) {
            float airRatio = (float) airCount / totalUnderground;
            if (airRatio > airRatioThreshold.get()) {
                return true; // Chắc chắn là hang đào tay
            }
        }

        // Logic 2: Phát hiện người chơi AFK lâu dài
        // Vines bình thường trong hang rất ít. Nếu lớn hơn 50 khối -> Lan tràn do AFK
        if (vineCount > 50) {
            afkGrowthCount += (vineCount / 5); 
        }

        if (afkGrowthCount >= afkGrowthThreshold.get()) {
            return true; // Khối già xuất hiện dày đặc -> Base ngầm!
        }

        return false;
    }

    // -------------------------------------------------------------------------
    // Render (Giữ nguyên thảm đỏ ở Y = 50)
    // -------------------------------------------------------------------------

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
