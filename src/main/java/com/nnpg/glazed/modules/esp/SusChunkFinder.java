package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.Heightmap;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect  = settings.createGroup("Detection");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính hiển thị vùng đỏ xung quanh người chơi.")
            .defaultValue(4)
            .min(1)
            .sliderMax(32)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Số lượng khối mọc lên/đặt xuống thời gian thực tối thiểu để đánh dấu.")
            .defaultValue(3)
            .min(1)
            .sliderMax(20)
            .build()
    );

    private final Setting<SettingColor> color = sgRender.add(
        new ColorSetting.Builder()
            .name("color")
            .description("Màu sắc của mặt phẳng đánh dấu.")
            .defaultValue(new SettingColor(255, 30, 30, 52))
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của mặt phẳng.")
            .defaultValue(52)
            .min(0)
            .sliderMax(255)
            .build()
    );

    private final Setting<Boolean> detectAmethyst = sgDetect.add(new BoolSetting.Builder().name("amethyst").description("Dò Thạch anh (radar cự ly gần)").defaultValue(true).build());    private final Setting<Boolean> detectKelp = sgDetect.add(new BoolSetting.Builder().name("kelp").defaultValue(true).build());
    private final Setting<Boolean> detectCaveVines = sgDetect.add(new BoolSetting.Builder().name("cave-vines").defaultValue(true).build());
    private final Setting<Boolean> detectVines = sgDetect.add(new BoolSetting.Builder().name("vines").defaultValue(true).build());
    private final Setting<Boolean> detectBamboo = sgDetect.add(new BoolSetting.Builder().name("bamboo").defaultValue(true).build());
    private final Setting<Boolean> detectBeeNest = sgDetect.add(new BoolSetting.Builder().name("bee-nest").defaultValue(true).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(true).build());

    // ── BIẾN LƯU TRỮ MỚI ──
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    
    // Lưu trữ Dữ liệu Chunk (Điểm số và Thời gian cập nhật cuối cùng)
    private final Map<ChunkPos, ChunkData> chunkDataMap = new ConcurrentHashMap<>();
    private final Color renderColor = new Color();

    // Lớp dữ liệu nội bộ để xử lý Time-Decay
    private static class ChunkData {
        int score = 0;
        long lastUpdateTime = System.currentTimeMillis();
    }

    // Thời gian "lãng quên" nếu không có cập nhật mới (Ví dụ: 3 phút = 180,000 ms)
    private static final long DECAY_TIME_MS = 180000;

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Phát hiện hoạt động sinh hoạt, tăng trưởng khối thời gian thực.");
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        chunkDataMap.clear(); // Đã sửa thành biến mới để không bị lỗi
    }

    @Override
    public void onDeactivate() {
        suspiciousChunks.clear();
        chunkDataMap.clear(); // Đã sửa thành biến mới để không bị lỗi
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            evaluateBlockThreat(packet.getPos(), packet.getState());
        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::evaluateBlockThreat);
        }
    }

    // ── HỆ THỐNG ĐÁNH GIÁ TRỌNG SỐ & LỌC CAO ĐỘ ──
    private void evaluateBlockThreat(BlockPos pos, BlockState state) {
        int threatScore = 0;
        net.minecraft.block.Block block = state.getBlock();

        // 1. Phân loại trọng số nguy hiểm
        if (state.isAir()) {
            // Không khí thay đổi -> Có thể ai đó vừa đào khối
            threatScore = 1; 
        } else if (detectBamboo.get() && block == Blocks.BAMBOO) {
            // Tre mọc dưới hang sâu (Y < 50) là 100% farm ngầm!
            threatScore = (pos.getY() < 50) ? 20 : 2; 
        } else if (detectKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
            threatScore = 2;
        } else if (detectAmethyst.get() && (block == Blocks.AMETHYST_CLUSTER || block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD)) {
            // Radar cận chiến phát hiện Thạch Anh
            threatScore = 15; 
        } else if (detectRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) {
            // Deepslate xoay ngang -> Chắc chắn 100% do người chơi đặt
            threatScore = 15; 
        }

        // Nếu khối này không khả nghi, bỏ qua
        if (threatScore == 0) return;

        ChunkPos cp = new ChunkPos(pos);
        if (suspiciousChunks.contains(cp)) return; // Đã báo đỏ rồi thì thôi

        long currentTime = System.currentTimeMillis();

        // 2. Xử lý cơ chế "Lãng quên" (Time-Decay)
        ChunkData data = chunkDataMap.computeIfAbsent(cp, k -> new ChunkData());
        if (currentTime - data.lastUpdateTime > DECAY_TIME_MS) {
            data.score = 0; // Đã quá lâu không có biến động -> Reset điểm về 0
        }

        // 3. Cộng dồn điểm và cập nhật thời gian
        data.score += threatScore;
        data.lastUpdateTime = currentTime;

        // 4. Kiểm tra ngưỡng nhạy cảm (Sensitivity bây giờ là Điểm số)
        if (data.score >= sensitivity.get() * 5) { // Nhân 5 để scale điểm cho hợp lý
            suspiciousChunks.add(cp);
            ChatUtils.info("⚠️ [SusChunk] Báo động đỏ tại: " + cp.x + ", " + cp.z + " (Điểm Sus: " + data.score + ")");
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (suspiciousChunks.isEmpty() || mc.player == null || mc.world == null) return;

        SettingColor sc = color.get();
        renderColor.set(sc.r, sc.g, sc.b, alpha.get());

        for (ChunkPos cp : suspiciousChunks) {
            if (!isInRange(cp)) continue;

            // Lấy toạ độ tuyệt đối đầu góc X và Z của Chunk
            double x1 = cp.getStartX();
            double z1 = cp.getStartZ();
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;

            // Ghim CHẾT toạ độ Y ở mức 50 theo đúng ý bạn
            double fixedY = 50.0;

            // Render ô vuông đỏ phẳng lì tại đúng Y=50
            event.renderer.box(
                x1, fixedY, z1,
                x2, fixedY + 0.05, z2,
                renderColor,
                renderColor,
                ShapeMode.Sides,
                0
            );
        }
    }

    private boolean isSuspicious(BlockState state) {
        if (state.isAir()) return false;
        net.minecraft.block.Block block = state.getBlock();

        if (detectKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) return true;
        if (detectCaveVines.get() && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) return true;
        if (detectVines.get() && block == Blocks.VINE) return true;
        if (detectBamboo.get() && block == Blocks.BAMBOO) return true;
        if (detectBeeNest.get() && (block == Blocks.BEE_NEST || block == Blocks.BEEHIVE)) return true;
        if (detectRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) return true;

        return false;
    }

    private boolean isInRange(ChunkPos pos) {
        if (mc.player == null) return false;
        ChunkPos playerChunk = mc.player.getChunkPos();
        int dist = simulationDistance.get();
        return Math.abs(pos.x - playerChunk.x) <= dist && Math.abs(pos.z - playerChunk.z) <= dist;
    }
}
