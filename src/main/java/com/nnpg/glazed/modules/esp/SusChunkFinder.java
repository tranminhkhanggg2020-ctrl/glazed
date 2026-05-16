package com.nnpg.glazed.modules.esp;

import net.minecraft.world.Heightmap;
import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
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
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect  = settings.createGroup("Detection");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét chunk xung quanh người chơi.")
            .defaultValue(4)
            .min(1)
            .sliderMax(32)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Số lượng khối sus tối thiểu để đánh dấu chunk.")
            .defaultValue(3)
            .min(1)
            .sliderMax(100)
            .build()
    );

    private final Setting<SettingColor> color = sgRender.add(
        new ColorSetting.Builder()
            .name("color")
            .description("Màu sắc hiển thị của chunk khả nghi.")
            .defaultValue(new SettingColor(255, 30, 30, 52))
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của mặt phẳng render.")
            .defaultValue(52)
            .min(0)
            .sliderMax(255)
            .build()
    );

    private final Setting<Boolean> detectKelp = sgDetect.add(new BoolSetting.Builder().name("kelp").defaultValue(true).build());
    private final Setting<Boolean> detectCaveVines = sgDetect.add(new BoolSetting.Builder().name("cave-vines").defaultValue(true).build());
    private final Setting<Boolean> detectVines = sgDetect.add(new BoolSetting.Builder().name("vines").defaultValue(true).build());
    private final Setting<Boolean> detectAmethyst = sgDetect.add(new BoolSetting.Builder().name("amethyst").defaultValue(true).build());
    private final Setting<Boolean> detectBamboo = sgDetect.add(new BoolSetting.Builder().name("bamboo").defaultValue(true).build());
    private final Setting<Boolean> detectBeeNest = sgDetect.add(new BoolSetting.Builder().name("bee-nest").defaultValue(true).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(true).build());

    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Color renderColor = new Color();

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Dự đoán vị trí căn cứ ngầm dựa trên sự tăng trưởng khối.");
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        if (mc.world != null && mc.player != null) {
            fullRescan();
        }
    }

    @Override
    public void onDeactivate() {
        suspiciousChunks.clear();
    }

    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null) return;
        if (!(event.chunk() instanceof WorldChunk worldChunk)) return;

        ChunkPos pos = worldChunk.getPos();
        if (!isInRange(pos)) return;

        scanChunk(worldChunk, pos);
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (suspiciousChunks.isEmpty() || mc.player == null || mc.world == null) return;

        SettingColor sc = color.get();
        renderColor.set(sc.r, sc.g, sc.b, alpha.get());

        for (ChunkPos cp : suspiciousChunks) {
            if (!isInRange(cp)) continue;

            // Lấy toạ độ tuyệt đối đầu góc X và Z của Chunk
            int blockX = cp.getStartX();
            int blockZ = cp.getStartZ();

            // DÙNG MC.WORLD.GETTOPY CHUẨN 100% ĐỂ LẤY ĐỘ CAO MẶT ĐẤT TUYỆT ĐỐI CỐ ĐỊNH
            int surfaceY = mc.world.getTopY(net.minecraft.world.Heightmap.Type.WORLD_SURFACE, blockX, blockZ);

            // Toạ độ thế giới tuyệt đối giúp ô đỏ đứng im cố định tại vị trí quét
            double x1 = blockX;
            double z1 = blockZ;
            double x2 = x1 + 16.0;
            double z2 = z1 + 16.0;

            event.renderer.box(
                x1, surfaceY, z1,
                x2, surfaceY + 0.05, z2,
                renderColor,
                renderColor,
                ShapeMode.Sides,
                0
            );
        }
    }

    private void scanChunk(WorldChunk chunk, ChunkPos pos) {
        if (chunk == null) return;

        int susCount = 0;
        int targetSensitivity = sensitivity.get();
        ChunkSection[] sections = chunk.getSectionArray();

        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            ChunkSection section = sections[sIdx];
            if (section == null || section.isEmpty()) continue;

            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
                        BlockState state = section.getBlockState(lx, ly, lz);

                        if (isSuspicious(state)) {
                            susCount++;
                            if (susCount >= targetSensitivity) {
                                if (!suspiciousChunks.contains(pos)) {
                                    suspiciousChunks.add(pos);
                                    // ── CẢM BIẾN CHAT: Báo hiệu ngay lập tức khi phát hiện ra base ──
                                    ChatUtils.info("Detected sus chunk at: " + pos.x + ", " + pos.z);
                                }
                                return;
                            }
                        }
                    }
                }
            }
        }
    }

    private boolean isSuspicious(BlockState state) {
        if (state.isAir()) return false;
        net.minecraft.block.Block block = state.getBlock();

        if (detectKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) return true;
        if (detectCaveVines.get() && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) return true;
        if (detectVines.get() && block == Blocks.VINE) return true;
        if (detectAmethyst.get() && (block == Blocks.AMETHYST_CLUSTER || block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD)) return true;
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

    private void fullRescan() {
        if (mc.world == null || mc.player == null) return;
        suspiciousChunks.clear();
        ChunkPos playerChunk = mc.player.getChunkPos();
        int dist = simulationDistance.get();

        for (int dx = -dist; dx <= dist; dx++) {
            for (int dz = -dist; dz <= dist; dz++) {
                ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
                net.minecraft.world.chunk.Chunk raw = mc.world.getChunk(cp.x, cp.z);
                if (raw instanceof WorldChunk wc) {
                    scanChunk(wc, cp);
                }
            }
        }
    }
}
