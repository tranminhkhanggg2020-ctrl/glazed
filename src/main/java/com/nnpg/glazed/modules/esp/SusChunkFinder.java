package com.nnpg.glazed.modules.esp;

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
            .description("Chunk radius around the player to scan for suspicious activity.")
            .defaultValue(4)
            .min(1)
            .sliderMax(32)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Minimum sus-block count to flag a chunk as suspicious.")
            .defaultValue(3)
            .min(1)
            .sliderMax(100)
            .build()
    );

    private final Setting<SettingColor> color = sgRender.add(
        new ColorSetting.Builder()
            .name("color")
            .description("Fill colour for the suspicious-chunk overlay plane.")
            .defaultValue(new SettingColor(255, 30, 30, 52))
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Transparency of the rendered plane. 0 = invisible, 255 = fully opaque.")
            .defaultValue(52)
            .min(0)
            .sliderMax(255)
            .build()
    );

    private final Setting<Boolean> detectKelp = sgDetect.add(
        new BoolSetting.Builder()
            .name("kelp")
            .description("Detect Kelp and Kelp Plant blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectCaveVines = sgDetect.add(
        new BoolSetting.Builder()
            .name("cave-vines")
            .description("Detect Cave Vines and Cave Vines Plant blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectVines = sgDetect.add(
        new BoolSetting.Builder()
            .name("vines")
            .description("Detect Vine blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectAmethyst = sgDetect.add(
        new BoolSetting.Builder()
            .name("amethyst")
            .description("Detect Amethyst Cluster, Small Bud, Medium Bud, and Large Bud blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectBamboo = sgDetect.add(
        new BoolSetting.Builder()
            .name("bamboo")
            .description("Detect Bamboo blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectBeeNest = sgDetect.add(
        new BoolSetting.Builder()
            .name("bee-nest")
            .description("Detect Bee Nest and Beehive blocks.")
            .defaultValue(true)
            .build()
    );

    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate")
            .description("Detect Deepslate blocks with AXIS ≠ Y.")
            .defaultValue(true)
            .build()
    );

    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Color renderColor = new Color();

    public SusChunkFinder() {
        super(
            GlazedAddon.CATEGORY,
            "sus-chunk-finder",
            "Flags loaded chunks containing growth/accumulation blocks that suggest a hidden base."
        );
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
        if (suspiciousChunks.isEmpty() || mc.player == null) return;

        SettingColor sc = color.get();
        renderColor.set(sc.r, sc.g, sc.b, alpha.get());

        net.minecraft.client.render.Camera camera = mc.gameRenderer.getCamera();
        double camX = camera.getPos().x;
        double camY = camera.getPos().y;
        double camZ = camera.getPos().z;

        double planeY = Math.floor(mc.player.getY());

        for (ChunkPos cp : suspiciousChunks) {
            if (!isInRange(cp)) continue;

            double x1 = cp.getStartX() - camX;
            double z1 = cp.getStartZ() - camZ;
            double x2 = (cp.getStartX() + 16.0) - camX;
            double z2 = (cp.getStartZ() + 16.0) - camZ;

            double y1 = planeY - camY;
            double y2 = (planeY + 0.05) - camY;

            event.renderer.box(
                x1, y1, z1,
                x2, y2, z2,
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
                                suspiciousChunks.add(pos);
                                return;
                            }
                        }
                    }
                }
            }
        }
        suspiciousChunks.remove(pos);
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
