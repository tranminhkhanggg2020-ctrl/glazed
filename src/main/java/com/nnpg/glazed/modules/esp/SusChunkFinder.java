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

    private final SettingGroup sgGeneral   = settings.createGroup("General");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgHeuristic = settings.createGroup("Heuristic Filters");

    // --- General ---
    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét tính bằng chunk (1-32).")
            .defaultValue(4).min(1).max(32).sliderRange(1, 32)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Ngưỡng điểm nghi ngờ (Sus Score). Càng thấp càng nhạy báo động.")
            .defaultValue(3).min(1).max(10).sliderRange(1, 10)
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

    // --- Heuristic Filters (Đã tích hợp logic AFK Growth & Bypass) ---
    private final Setting<Boolean> filterKelp = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("kelp").description("Quét Tảo bẹ đạt tuổi thọ tối đa (age=25) do AFK.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("cave-vines").description("Quét Dây leo hang đạt tuổi thọ tối đa (age=25) do AFK.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("vines").description("Quét sự lan tràn bất thường của Dây leo.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Quét sự phát triển của cụm Thạch anh.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> filterBamboo = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("bamboo").description("Quét Tre mọc ngầm.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterBeeNest = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("bee-nest").description("Quét Tổ ong nhân tạo.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate").description("Quét Deepslate bị người chơi đặt xoay ngang.")
            .defaultValue(false).build()
    );

    // -------------------------------------------------------------------------
    // Internal State
    // -------------------------------------------------------------------------

    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Base Finder V2: GUI Tùy chỉnh + Quét AFK Growth (Y=50).");
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
        // Lấy tọa độ Chunk chuẩn
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();

        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        // Đã cắm cờ rồi thì bỏ qua
        if (renderCache.containsKey(key)) return;

        // VƯỢT RÀO: Quét khối lượng NBT (Rương) ẩn bằng Reflection
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

        // Nếu NBT rất lớn (ví dụ > sensitivity * 10), đó chắc chắn là Base
        if (blockEntityCount >= sensitivity.get() * 10) {
            renderCache.put(key, new int[]{ cx * 16, cz * 16 });
            return;
        }

        // Nếu NBT bị giấu (dưới Y=15), kích hoạt Mũi nhọn số 2 (Quét AFK Growth)
        final int finalCx = cx, finalCz = cz;

        new Thread(() -> {
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}

            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                if (chunk == null) return;

                if (computeSusScore(chunk) >= sensitivity.get() * 5) {
                    renderCache.put(key, new int[]{ finalCx * 16, finalCz * 16 });
                }
            });
        }, "SusChunkFinder-Scan").start();
    }

    // -------------------------------------------------------------------------
    // Heuristic Score Engine: AFK Growth & Air Pockets
    // -------------------------------------------------------------------------

    private int computeSusScore(WorldChunk chunk) {
        int score = 0;
        int minY = mc.world.getBottomY();
        int scanMaxY = 40; // Giới hạn quét dưới lòng đất để chống nhiễu
        
        int airCount = 0;
        int solidCount = 0;
        int vineCount = 0;

        ChunkPos cp = chunk.getPos();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    String stateStr = chunk.getBlockState(bp).toString().toLowerCase();

                    // 1. Phân tích bọng Không khí (Luôn chạy ngầm)
                    if (stateStr.contains("minecraft:air") || stateStr.contains("minecraft:cave_air")) {
                        airCount++;
                    } else if (stateStr.contains("stone") || stateStr.contains("deepslate") || stateStr.contains("tuff")) {
                        solidCount++;
                    }

                    // 2. Phân tích mọc AFK theo Toggles của người chơi
                    if (filterKelp.get() && stateStr.contains("minecraft:kelp") && stateStr.contains("age=25")) {
                        score += 5; // Kelp già cỗi = điểm Sus cực cao
                    }
                    if (filterCaveVines.get() && stateStr.contains("minecraft:cave_vines") && stateStr.contains("age=25")) {
                        score += 5;
                    }
                    if (filterAmethyst.get() && stateStr.contains("minecraft:amethyst_cluster")) {
                        score += 2;
                    }
                    if (filterVines.get() && stateStr.contains("minecraft:vine")) {
                        vineCount++;
                    }
                    if (filterBamboo.get() && stateStr.contains("minecraft:bamboo")) {
                        score += 2; // Tre mọc sâu dưới lòng đất là phi lý
                    }
                    if (filterBeeNest.get() && stateStr.contains("minecraft:bee_nest")) {
                        score += 10; // Có tổ ong dưới hang đá thì 100% là trang trại ngầm
                    }
                    if (filterRotatedDeepslate.get() && stateStr.contains("minecraft:deepslate") && stateStr.contains("axis=") && !stateStr.contains("axis=y")) {
                        score += 1;
                    }
                }
            }
        }

        // Tính điểm Bọng Không Khí Nhân Tạo
        float totalUnderground = airCount + solidCount;
        if (totalUnderground > 0) {
            float airRatio = (float) airCount / totalUnderground;
            if (airRatio > 0.35f) { // > 35% là hầm rỗng
                score += (int) ((airRatio - 0.35f) * 100);
            }
        }

        // Tính điểm Dây leo lan tràn
        if (filterVines.get() && vineCount > 30) {
            score += (vineCount / 5);
        }

        return score;
    }

    // -------------------------------------------------------------------------
    // Render: Ô Vuông Phẳng Y=50
    // -------------------------------------------------------------------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || renderCache.isEmpty()) return;

        int a = alpha.get();
        Color sideColor = new Color(255, 0, 0, a);
        Color lineColor = new Color(255, 0, 0, Math.min(255, a + 80));

        // CHỐT TỌA ĐỘ: Hộp vuông phẳng lì ở Y=50
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
