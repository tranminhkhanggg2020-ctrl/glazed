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

    private final Setting<Boolean> detectKelp = sgDetect.add(new BoolSetting.Builder().name("kelp").defaultValue(true).build());
    private final Setting<Boolean> detectCaveVines = sgDetect.add(new BoolSetting.Builder().name("cave-vines").defaultValue(true).build());
    private final Setting<Boolean> detectVines = sgDetect.add(new BoolSetting.Builder().name("vines").defaultValue(true).build());
    private final Setting<Boolean> detectBamboo = sgDetect.add(new BoolSetting.Builder().name("bamboo").defaultValue(true).build());
    private final Setting<Boolean> detectBeeNest = sgDetect.add(new BoolSetting.Builder().name("bee-nest").defaultValue(true).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(true).build());

    // Set chứa danh sách chunk đã xác nhận có hoạt động sinh hoạt/mọc cây thực tế
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    
    // Bản đồ đếm số lượng khối thay đổi thời gian thực cho từng chunk
    private final Map<ChunkPos, Integer> realTimeGrowthCount = new ConcurrentHashMap<>();
    private final Color renderColor = new Color();

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Phát hiện hoạt động sinh hoạt, tăng trưởng khối thời gian thực.");
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        realTimeGrowthCount.clear();
    }

    @Override
    public void onDeactivate() {
        suspiciousChunks.clear();
        realTimeGrowthCount.clear();
    }

    // ── ĐÓN ĐẦU BIẾN CỐ THỜI GIAN THỰC QUA GÓI TIN MẠNG (TẬP TRUNG CHÍNH XÁC 100%) ──
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // Trường hợp 1: Server cập nhật 1 khối đơn lẻ (Cây mọc thêm 1 đốt hoặc người đặt khối)
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            checkAndTrackGrowth(packet.getPos(), packet.getState());
        } 
        // Trường hợp 2: Server cập nhật nhiều khối trong 1 vùng (Chuỗi farm tự động chạy hoặc piston đẩy liên tục)
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::checkAndTrackGrowth);
        }
    }

    private void checkAndTrackGrowth(BlockPos pos, BlockState state) {
        if (isSuspicious(state)) {
            ChunkPos cp = new ChunkPos(pos);

            // Nếu chunk này đã đạt ngưỡng và bị đánh dấu đỏ rồi thì không cần đếm tiếp nữa
            if (suspiciousChunks.contains(cp)) return;

            // Cộng dồn số khối mọc thực tế trong phiên chơi hiện tại
            int currentCount = realTimeGrowthCount.getOrDefault(cp, 0) + 1;
            realTimeGrowthCount.put(cp, currentCount);

            // Khi số lượng biến động mọc thực tế đạt hoặc vượt ngưỡng cấu hình Sensitivity
            if (currentCount >= sensitivity.get()) {
                suspiciousChunks.add(cp);
                ChatUtils.info("Detected ACTIVE growth/placement at chunk: " + cp.x + ", " + cp.z);
            }
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
