package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.entity.EntityType;

import com.nnpg.glazed.GlazedAddon;

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;

import java.util.concurrent.*;

public class SusChunkFinder extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgHeuristic = settings.createGroup("Heuristic Filters");

    // ==========================================
    // CÀI ĐẶT RADAR (TRẢ LẠI GIAO DIỆN CŨ)
    // ==========================================

    private final Setting<Integer> stashThreshold = sgGeneral.add(
        new IntSetting.Builder()
            .name("stash-packet-threshold")
            .description("Ngưỡng byte thô để bắt dính siêu kho đồ bỏ qua mọi quét phụ.")
            .defaultValue(40000).min(10000).sliderMax(100000)
            .build()
    );

    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Độ nhạy Krypton (1 = Khó, 10 = Cực Nhạy).")
            .defaultValue(6).min(1).sliderMax(10)
            .build()
    );

    private final Setting<Integer> renderDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("render-distance")
            .description("Bán kính VẼ thảm đỏ (Tối đa 64 Chunk).")
            .defaultValue(16).min(1).sliderMax(64)
            .build()
    );

    private final Setting<Integer> alpha = sgGeneral.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt Thảm Đỏ (Y=62).")
            .defaultValue(70).min(0).sliderMax(255)
            .build()
    );

    // ==========================================
    // BỘ LỌC HEURISTIC (TRẢ LẠI TICK BOX)
    // ==========================================
    
    private final Setting<Boolean> filterAmethyst = sgHeuristic.add(new BoolSetting.Builder().name("amethyst").defaultValue(true).build());
    private final Setting<Boolean> filterBeeNest = sgHeuristic.add(new BoolSetting.Builder().name("bee-nest").defaultValue(false).build());
    private final Setting<Boolean> filterRotatedDeepslate = sgHeuristic.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(false).build());
    private final Setting<Boolean> filterKelp = sgHeuristic.add(new BoolSetting.Builder().name("kelp").defaultValue(false).build());
    private final Setting<Boolean> filterCaveVines = sgHeuristic.add(new BoolSetting.Builder().name("cave-vines").defaultValue(false).build());
    private final Setting<Boolean> filterVines = sgHeuristic.add(new BoolSetting.Builder().name("vines").defaultValue(false).build());
    private final Setting<Boolean> filterBamboo = sgHeuristic.add(new BoolSetting.Builder().name("bamboo").defaultValue(false).build());

    // ==========================================
    // KRYPTON CACHE BẰNG FASTUTIL (CỦA CLAUDE)
    // ==========================================

    private final ConcurrentHashMap<Long, Byte> confirmedBases = new ConcurrentHashMap<>(256);
    private final Long2IntOpenHashMap accumulator = new Long2IntOpenHashMap(512);
    private final Object accumulatorLock = new Object(); 

    private final LongOpenHashSet freshChunks = new LongOpenHashSet(64);
    private final Object freshLock = new Object();

    // Động cơ 8 luồng chạy ngầm không giật lag
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "SusChunkFinder-scan");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); 
        return t;
    });

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar Krypton Extreme: Giao diện chuẩn mực, Lõi FastUtil siêu việt.");
    }

    @Override
    public void onActivate() { 
        confirmedBases.clear(); 
        synchronized (accumulatorLock) { accumulator.clear(); }
        synchronized (freshLock)       { freshChunks.clear(); }
    }

    @Override
    public void onDeactivate() { 
        synchronized (accumulatorLock) { accumulator.clear(); }
        synchronized (freshLock)       { freshChunks.clear(); }
    }

    // ==========================================
    // TẦNG MẠNG (ĐÓN LÕNG GÓI TIN)
    // ==========================================

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket updatePacket) {
            BlockState state = updatePacket.getState();
            if (!state.getFluidState().isEmpty()) {
                BlockPos pos = updatePacket.getPos();
                long key = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
                synchronized (freshLock) { freshChunks.add(key); }
            }
        }

        else if (event.packet instanceof ChunkDataS2CPacket packet) {
            int cx = packet.getChunkX();
            int cz = packet.getChunkZ();
            long key = chunkKey(cx, cz);

            if (confirmedBases.containsKey(key)) return;

            // [LỚP 1]: TRẠM CÂN (Bắt Siêu Kho Đồ)
            try {
                PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
                if (buf != null && buf.readableBytes() > stashThreshold.get()) {
                    confirmedBases.put(key, (byte) 1);
                    return; 
                }
            } catch (Exception ignored) {}

            // [LỚP 2]: TRỌNG SỐ (Bắt Rương Nhỏ & Bù trừ Làng/Hầm)
            try {
                int[] scoreCounter = {0};
                packet.getChunkData().getBlockEntities(cx, cz).accept((bPos, bType, bNbt) -> {
                    if (bType == BlockEntityType.SHULKER_BOX || bType == BlockEntityType.ENDER_CHEST) scoreCounter[0] += 50; 
                    else if (bType == BlockEntityType.HOPPER) scoreCounter[0] += 10; 
                    else if (bType == BlockEntityType.CHEST || bType == BlockEntityType.BARREL || bType == BlockEntityType.TRAPPED_CHEST) scoreCounter[0] += 2; 
                    else if (bType == BlockEntityType.FURNACE || bType == BlockEntityType.SMOKER || bType == BlockEntityType.BLAST_FURNACE) scoreCounter[0] += 2;
                    else if (bType == BlockEntityType.MOB_SPAWNER) scoreCounter[0] -= 100; 
                    else if (bType == BlockEntityType.BELL || bType == BlockEntityType.CAMPFIRE) scoreCounter[0] -= 50; 
                    else if (bType == BlockEntityType.BED) scoreCounter[0] -= 10; 
                });
                
                if (scoreCounter[0] != 0) { 
                    addSusScore(key, scoreCounter[0]);
                }
            } catch (Exception ignored) {}

            runHeuristicScan(cx, cz, key);
        }

        else if (event.packet instanceof EntitySpawnS2CPacket packet) {
            EntityType<?> type = packet.getEntityType();
            int cx = ((int) packet.getX()) >> 4;
            int cz = ((int) packet.getZ()) >> 4;

            if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME || type == EntityType.ARMOR_STAND) {
                addSusScore(chunkKey(cx, cz), 40); 
            }
            else if (type == EntityType.VILLAGER || type == EntityType.IRON_GOLEM) {
                addSusScore(chunkKey(cx, cz), -20);
            }
        }

        else if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            BlockPos pos = packet.getPos();
            addSusScore(chunkKey(pos.getX() >> 4, pos.getZ() >> 4), 15); 
        }
    }

    private void addSusScore(long key, int delta) {
        if (confirmedBases.containsKey(key)) return;
        
        int threshold = (11 - sensitivity.get()) * 15; 
        synchronized (accumulatorLock) {
            int current = accumulator.getOrDefault(key, 0) + delta;
            accumulator.put(key, current);
            if (current >= threshold) {
                confirmedBases.put(key, (byte) 1);
            }
        }
    }

    // ==========================================
    // BACKGROUND HEURISTICS (ĐÃ FIX LỖI MÙ BASE)
    // ==========================================

    private void runHeuristicScan(int cx, int cz, long key) {
        if (!filterKelp.get() && !filterCaveVines.get() && !filterVines.get() && 
            !filterAmethyst.get() && !filterBamboo.get() && !filterBeeNest.get() && 
            !filterRotatedDeepslate.get()) return;

        EXECUTOR.execute(() -> {
            try {
                boolean isFresh;
                synchronized (freshLock) { isFresh = freshChunks.contains(key); }
                if (isFresh) return; 
                
                // ĐÃ FIX: Ép luồng quét phải ĐỢI 100ms để Minecraft có thời gian tải Chunk ra thế giới 3D
                Thread.sleep(100); 
                if (mc.world == null) return;
                
                WorldChunk chunk = mc.world.getChunk(cx, cz);
                if (chunk == null || chunk.isEmpty()) return; // Nếu không đợi, dòng này sẽ đánh bật mọi Base!

                int blockScore = 0;
                for (ChunkSection section : chunk.getSectionArray()) {
                    if (section == null || section.isEmpty()) continue;
                    PalettedContainer<BlockState> container = section.getBlockStateContainer();

                    boolean hasTargetBlocks = container.hasAny(state -> {
                        Block b = state.getBlock();
                        return (filterAmethyst.get() && b == Blocks.AMETHYST_CLUSTER) ||
                               (filterBeeNest.get() && b == Blocks.BEE_NEST) ||
                               (filterBamboo.get() && b == Blocks.BAMBOO) ||
                               (filterRotatedDeepslate.get() && b == Blocks.DEEPSLATE) ||
                               (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) ||
                               (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT)) ||
                               (filterVines.get() && b == Blocks.VINE);
                    });

                    if (!hasTargetBlocks) continue;

                    for (int x = 0; x < 16; x++) {
                        for (int y = 0; y < 16; y++) {
                            for (int z = 0; z < 16; z++) {
                                BlockState state = container.get(x, y, z);
                                Block b = state.getBlock();

                                if (filterAmethyst.get() && b == Blocks.AMETHYST_CLUSTER) blockScore += 20;
                                else if (filterBeeNest.get() && b == Blocks.BEE_NEST) blockScore += 10;
                                else if (filterBamboo.get() && b == Blocks.BAMBOO) blockScore += 5;
                                else if (filterVines.get() && b == Blocks.VINE) blockScore += 2;
                                else if (filterRotatedDeepslate.get() && b == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                                    if (state.get(Properties.AXIS) != Direction.Axis.Y) blockScore += 50; 
                                }
                                else if (filterKelp.get() && (b == Blocks.KELP || b == Blocks.KELP_PLANT)) {
                                    if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) blockScore += 15; 
                                }
                                else if (filterCaveVines.get() && (b == Blocks.CAVE_VINES || b == Blocks.CAVE_VINES_PLANT)) {
                                    if (state.contains(Properties.AGE_25) && state.get(Properties.AGE_25) == 25) blockScore += 15;
                                }
                            }
                        }
                    }
                }

                if (blockScore > 0) {
                    addSusScore(key, blockScore + 30); 
                }
            } catch (Exception ignored) {}
        });
    }

    // ==========================================
    // VẼ ĐỒ HỌA (KRYPTON RED CARPET)
    // ==========================================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null || confirmedBases.isEmpty()) return;

        int aBase = alpha.get();
        Color baseSideColor = new Color(255, 0, 0, aBase);
        Color baseLineColor = new Color(255, 0, 0, 255); 
        double baseY = 62.0; 

        int playerCX = mc.player.getBlockX() >> 4;
        int playerCZ = mc.player.getBlockZ() >> 4;
        int rd = renderDistance.get();

        var keys = confirmedBases.keySet();

        for (long key : keys) {
            int cx = (int)(key >> 32);
            int cz = (int)(key & 0xFFFFFFFFL);

            if (Math.abs(cx - playerCX) > rd || Math.abs(cz - playerCZ) > rd) continue;

            double x1 = cx << 4; 
            double z1 = cz << 4;
            double x2 = x1 + 16;
            double z2 = z1 + 16;

            event.renderer.box(
                x1, baseY, z1,
                x2, baseY + 0.1, z2,
                baseSideColor, baseLineColor,
                ShapeMode.Both, 0 
            );
        }
    }

    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
