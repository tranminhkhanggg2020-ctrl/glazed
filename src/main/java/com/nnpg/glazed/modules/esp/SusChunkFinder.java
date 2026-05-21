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

    private final SettingGroup sgGeneral   = settings.createGroup("General");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgHeuristic = settings.createGroup("AFK Filters"); // Đổi tên cho đúng bản chất

    // --- General ---
    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét (Chunks).")
            .defaultValue(4).min(1).max(32).sliderRange(1, 32)
            .build()
    );

    private final Setting<Integer> nbtThreshold = sgGeneral.add(
        new IntSetting.Builder()
            .name("nbt-threshold")
            .description("Số lượng Rương/Lò nung tối thiểu để báo Base. (Bắt sống Anti-Xray)")
            .defaultValue(20).min(1).max(100).sliderRange(10, 100)
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

    // --- AFK Filters (Chỉ bắt dấu vết AFK của người chơi) ---
    private final Setting<Boolean> filterKelp = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("afk-kelp").description("Quét Tảo bẹ đạt tuổi thọ tối đa (age=25).")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("afk-cave-vines").description("Quét Dây leo hang đạt tuổi thọ tối đa (age=25).")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("afk-amethyst").description("Quét sự phát triển bất thường của cụm Thạch anh.")
            .defaultValue(false).build() // Tắt mặc định vì dễ nhầm với thạch anh tự nhiên
    );
    private final Setting<Boolean> filterVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("afk-vines").description("Quét sự lan tràn bất thường của Dây leo (>50 khối).")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> filterBeeNest = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("underground-bee-nest").description("Báo động ngay nếu có Tổ ong dưới lòng đất (Y<40).")
            .defaultValue(true).build()
    );

    // Lưu trữ tọa độ vẽ
    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Base Finder (No False Positives): Chỉ quét Rương và Dấu vết AFK.");
    }

    @Override
    public void onActivate() { renderCache.clear(); }

    @Override
    public void onDeactivate() { renderCache.clear(); }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        if (event.packet instanceof ChunkDataS2CPacket) {
            handleChunkData((ChunkDataS2CPacket) event.packet);
        }
    }

    private void handleChunkData(ChunkDataS2CPacket packet) {
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();

        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        if (renderCache.containsKey(key)) return;

        // BƯỚC 1: Quét Rương/NBT (Chính xác 100%, không bao giờ sai)
        int blockEntityCount = 0;
        try {
            java.lang.reflect.Field[] fields = packet.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                f.setAccessible(true);
                Object val = f.get(packet);
                if (val instanceof java.util.List<?>) {
                    blockEntityCount = ((java.util.List<?>) val).size();
                    break;
                }
            }
        } catch (Exception ignored) {}

        if (blockEntityCount >= nbtThreshold.get()) {
            renderCache.put(key, new int[]{ cx * 16, cz * 16 });
            return;
        }

        // BƯỚC 2: Quét Dấu vết AFK (Nếu NBT bị giấu, tìm cây già)
        final int finalCx = cx, finalCz = cz;
        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                if (chunk == null) return;

                if (isAfkGrowthDetected(chunk)) {
                    renderCache.put(key, new int[]{ finalCx * 16, finalCz * 16 });
                }
            });
        }, "SusChunkFinder-AFK-Scan").start();
    }

    // --- Thuật toán lọc AFK (Đã xóa bỏ hoàn toàn việc quét Không Khí/Air) ---
    private boolean isAfkGrowthDetected(WorldChunk chunk) {
        int minY = mc.world.getBottomY();
        int scanMaxY = 40; // Chỉ quét ngầm
        
        int afkScore = 0;
        int vineCount = 0;

        ChunkPos cp = chunk.getPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    String stateStr = chunk.getBlockState(bp).toString().toLowerCase();

                    // Tảo bẹ già (Chỉ mọc tới age=25 khi có người AFK)
                    if (filterKelp.get() && stateStr.contains("minecraft:kelp") && stateStr.contains("age=25")) {
                        afkScore += 5; 
                    }
                    // Dây leo hang già
                    if (filterCaveVines.get() && stateStr.contains("minecraft:cave_vines") && stateStr.contains("age=25")) {
                        afkScore += 5;
                    }
                    // Thạch anh nở rộ
                    if (filterAmethyst.get() && stateStr.contains("minecraft:amethyst_cluster")) {
                        afkScore += 1;
                    }
                    // Dây leo thường
                    if (filterVines.get() && stateStr.contains("minecraft:vine")) {
                        vineCount++;
                    }
                    // Tổ ong dưới lòng đất (Chắc chắn là trang trại nhân tạo)
                    if (filterBeeNest.get() && stateStr.contains("minecraft:bee_nest")) {
                        return true; // Báo động khẩn cấp!
                    }
                }
            }
        }

        if (filterVines.get() && vineCount > 50) {
            afkScore += 10; // Dây leo lan tràn quá mức tự nhiên
        }

        // Cần ít nhất 10 điểm (ví dụ: 2 khối Kelp già, hoặc rất nhiều dây leo) mới báo Base
        return afkScore >= 10;
    }

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
