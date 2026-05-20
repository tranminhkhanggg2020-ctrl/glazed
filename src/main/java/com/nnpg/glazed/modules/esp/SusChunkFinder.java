package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.*;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
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
            .description("Ngưỡng cảnh báo. NBT threshold = sensitivity × 10. Sus Score threshold = sensitivity.")
            .defaultValue(3).min(1).max(10).sliderRange(1, 10)
            .build()
    );

    // --- Render ---
    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của hộp cảnh báo (0 = trong suốt, 255 = đục).")
            .defaultValue(52).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    // --- Heuristic Filters ---
    private final Setting<Boolean> filterKelp = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("kelp").description("Phát hiện kelp bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("cave-vines").description("Phát hiện cave vines bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterVines = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("vines").description("Phát hiện vines bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("amethyst").description("Phát hiện amethyst geode bị đào phá.")
            .defaultValue(true).build()
    );
    private final Setting<Boolean> filterBamboo = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("bamboo").description("Phát hiện bamboo bị xóa bất thường.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterBeeNest = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("bee-nest").description("Phát hiện bee nest bị xóa/di chuyển.")
            .defaultValue(false).build()
    );
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate").description("Phát hiện deepslate bị xoay (dấu hiệu đào thủ công).")
            .defaultValue(false).build()
    );

    // -------------------------------------------------------------------------
    // Internal State
    // -------------------------------------------------------------------------

    private final Long2IntOpenHashMap susChunks = new Long2IntOpenHashMap();
    private final Long2ObjectOpenHashMap<int[]> renderCache = new Long2ObjectOpenHashMap<>();

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Phát hiện base ẩn qua phân tích ChunkData packet (NBT + Heuristics).");
    }

    @Override
    public void onActivate() {
        susChunks.clear();
        renderCache.clear();
    }

    @Override
    public void onDeactivate() {
        susChunks.clear();
        renderCache.clear();
    }

    // -------------------------------------------------------------------------
    // Packet Listener
    // -------------------------------------------------------------------------

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof ChunkDataS2CPacket packet) {
            handleChunkData(packet);
        } else if (event.packet instanceof BlockUpdateS2CPacket packet) {
            handleBlockUpdate(packet);
        }
    }

    private void handleChunkData(ChunkDataS2CPacket packet) {
        // [FIX 1.21.4] getX() -> getChunkX(), getZ() -> getChunkZ()
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();

        if (!isWithinSimulationDistance(cx, cz)) return;

        ChunkPos pos = new ChunkPos(cx, cz);
        long key = pos.toLong();

        if (susChunks.containsKey(key) && susChunks.get(key) >= sensitivity.get() * 10) return;

        int susScore = 0;
        // [FIX 1.21.4] getBlockEntities() -> getBlockEntityData()
        int blockEntityCount = packet.getBlockEntityData().size();
        int nbtThreshold = sensitivity.get() * 10;

        if (blockEntityCount >= nbtThreshold) {
            susScore += blockEntityCount;
        }

        final int finalCx = cx, finalCz = cz;
        final int capturedScore = susScore;

        scheduleHeuristicCheck(finalCx, finalCz, capturedScore, key);
    }

    private void scheduleHeuristicCheck(int cx, int cz, int baseScore, long key) {
        new Thread(() -> {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}

            mc.execute(() -> {
                if (mc.world == null) return;
                WorldChunk chunk = mc.world.getChunk(cx, cz);
                if (chunk == null) return;

                int totalScore = baseScore + computeHeuristicScore(chunk);
                flagChunkIfSus(key, cx, cz, totalScore);
            });
        }, "SusChunkFinder-Heuristic").start();
    }

    private void handleBlockUpdate(BlockUpdateS2CPacket packet) {
        BlockPos bp = packet.getPos();
        ChunkPos cp = new ChunkPos(bp);
        long key = cp.toLong();

        if (!isWithinSimulationDistance(cp.x, cp.z)) return;

        Block newBlock = packet.getState().getBlock();
        Block oldBlock = (mc.world != null) ? mc.world.getBlockState(bp).getBlock() : Blocks.AIR;

        if (newBlock == Blocks.AIR && isNaturalTrackedBlock(oldBlock)) {
            int current = susChunks.getOrDefault(key, 0);
            flagChunkIfSus(key, cp.x, cp.z, current + 2);
        }
    }

    // -------------------------------------------------------------------------
    // Heuristic Score Engine
    // -------------------------------------------------------------------------

    private int computeHeuristicScore(WorldChunk chunk) {
        int score = 0;
        int minY = mc.world.getBottomY();
        // [FIX 1.21.4] Tính toán an toàn TopY bằng BottomY + Height
        int maxY = mc.world.getBottomY() + mc.world.getHeight();

        int airCount        = 0;
        int deepslateCount  = 0;
        int amethystCount   = 0;
        int naturalCount    = 0;

        ChunkPos cp = chunk.getPos();
        int scanMaxY = Math.min(0, maxY);

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = minY; y < scanMaxY; y++) {
                    BlockPos bp = new BlockPos(cp.getStartX() + x, y, cp.getStartZ() + z);
                    Block block = chunk.getBlockState(bp).getBlock();

                    if (block == Blocks.AIR || block == Blocks.
