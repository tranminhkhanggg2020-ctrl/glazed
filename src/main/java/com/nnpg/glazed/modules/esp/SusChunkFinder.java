package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color; // Đã thêm import Color
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
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

    // -------------------------------------------------------------------------
    // Cài đặt Giao diện (Settings)
    // -------------------------------------------------------------------------

    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender     = settings.createGroup("Render");

    private final Setting<Integer> stashThreshold = sgDetection.add(
        new IntSetting.Builder()
            .name("stash-threshold")
            .description("Ngưỡng dung lượng byte thô của gói tin để kích nổ thảm đỏ lập tức (Layer 1).")
            .defaultValue(40000).min(10000).sliderMax(100000)
            .build());

    private final Setting<Integer> sensitivity = sgDetection.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Độ nhạy của bình tích lũy 1-10. Càng cao càng dễ báo động.")
            .defaultValue(6).min(1).sliderMax(10)
            .build());

    private final Setting<Integer> renderDistance = sgDetection.add(
        new IntSetting.Builder()
            .name("render-distance")
            .description("Bán kính hiển thị thảm đỏ (Mở rộng tối đa lên 64 chunk).")
            .defaultValue(16).min(1).sliderMax(64)
            .build());

    private final Setting<SettingColor> colorBase = sgRender.add(
        new ColorSetting.Builder()
            .name("base-color")
            .description("Màu sắc của thảm đỏ khi xác nhận có căn cứ.")
            .defaultValue(new SettingColor(225, 0, 0, 70))
            .build());

    // -------------------------------------------------------------------------
    // Bộ nhớ đệm tối ưu hiệu năng của Claude AI
    // -------------------------------------------------------------------------

    private final ConcurrentHashMap<Long, Byte> confirmedBases = new ConcurrentHashMap<>(256);
    private final Long2IntOpenHashMap accumulator = new Long2IntOpenHashMap(512);
    private final Object accumulatorLock = new Object(); 

    private final LongOpenHashSet freshChunks = new LongOpenHashSet(64);
    private final Object freshLock = new Object();

    // Động cơ 8 luồng ẩn (Daemon Threads) giải phóng hoàn toàn gánh nặng cho Game Thread
    private final ExecutorService scanPool = Executors.newFixedThreadPool(8, r -> {
        Thread t = new Thread(r, "SusChunkFinder-scan");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1); 
        return t;
    });

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar Krypton Extreme: Tối ưu cấu trúc hạt FastUtil & Đa luồng độc lập bởi Claude AI.");
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

    // -------------------------------------------------------------------------
    // Tầng mạng (Netty I/O Thread)
    // -------------------------------------------------------------------------

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof ChunkDataS2CPacket p)           handleChunkData(p);
        else if (event.packet instanceof EntitySpawnS2CPacket p)      handleEntitySpawn(p);
        else if (event.packet instanceof BlockEntityUpdateS2CPacket p) handleBlockEntityUpdate(p);
        else if (event.packet instanceof BlockUpdateS2CPacket p)       handleBlockUpdate(p);
    }

    // -------------------------------------------------------------------------
    // Layer 1 & 2 — Xử lý Chunk nguyên khối
    // -------------------------------------------------------------------------

    private void handleChunkData(ChunkDataS2CPacket packet) {
        // ĐÃ FIX LỖI: Sử dụng getChunkX() và getChunkZ() thay vì getX() / getZ()
        int cx = packet.getChunkX();
        int cz = packet.getChunkZ();
        long key = chunkKey(cx, cz);

        if (confirmedBases.containsKey(key)) return;

        // [LỚP 1]: TRẠM CÂN PACKET (Gia cố chống crash bằng null-check)
        try {
            PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
            if (buf != null && buf.readableBytes() >= stashThreshold.get()) {
                confirmedBases.put(key, (byte) 1);
                return; 
            }
        } catch (Exception ignored) {}

        // [LỚP 2]: TRỌNG SỐ BLOCK-ENTITY (Sửa lỗi cú pháp accept chuẩn Fabric)
        try {
            int[] scoreCounter = {0};
            packet.getChunkData().getBlockEntities(cx, cz).accept((bPos, bType, bNbt) -> {
                scoreCounter[0] += blockEntityWeight(bType);
            });

            if (scoreCounter[0] != 0) {
                addAccumulatorScore(key, scoreCounter[0]);
            }
        } catch (Exception ignored) {}

        // [LỚP 4 GATE]: Khóa mỏm địa hình mới tinh
        boolean isFresh;
        synchronized (freshLock) { isFresh = freshChunks.contains(key); }
        if (isFresh) return;

        // Đẩy lệnh quét khối phụ trợ xuống bể 8 luồng chạy ngầm
        scanPool.submit(() -> heuristicScan(cx, cz, key));
    }

    private static int blockEntityWeight(BlockEntityType<?> type) {
        if (type == BlockEntityType.SHULKER_BOX || type == BlockEntityType.ENDER_CHEST) return 50;
        if (type == BlockEntityType.HOPPER) return 10;
        if (type == BlockEntityType.CHEST || type == BlockEntityType.TRAPPED_CHEST || type == BlockEntityType.BARREL) return 2;
        if (type == BlockEntityType.FURNACE || type == BlockEntityType.BLAST_FURNACE || type == BlockEntityType.SMOKER) return 2;
        
        // Khắc tinh cấu trúc tự nhiên (Làng & Hầm mỏ)
        if (type == BlockEntityType.MOB_SPAWNER) return -100;
        if (type == BlockEntityType.BELL || type == BlockEntityType.CAMPFIRE) return -50;
        if (type == BlockEntityType.BED) return -10;
        return 0;
    }

    // -------------------------------------------------------------------------
    // Layer 3 — Bình tích lũy Micro-Packets & Thực thể hành động
    // -------------------------------------------------------------------------

    private void handleEntitySpawn(EntitySpawnS2CPacket packet) {
        var entityType = packet.getEntityType();
        int delta = entitySpawnWeight(entityType);
        if (delta == 0) return;

        int cx = ((int) packet.getX()) >> 4; // Toán tử dịch bit siêu tốc thay cho phép chia
        int cz = ((int) packet.getZ()) >> 4;
        addAccumulatorScore(chunkKey(cx, cz), delta);
    }

    private void handleBlockEntityUpdate(BlockEntityUpdateS2CPacket packet) {
        BlockPos pos = packet.getPos();
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        addAccumulatorScore(chunkKey(cx, cz), 15); // Bắt tín hiệu máy redstone/lò nung hoạt động
    }

    private static int entitySpawnWeight(EntityType<?> type) {
        if (type == EntityType.ITEM_FRAME || type == EntityType.GLOW_ITEM_FRAME || type == EntityType.ARMOR_STAND) return 40;
        if (type == EntityType.VILLAGER || type == EntityType.IRON_GOLEM) return -20; // Phạt âm điểm dân làng
        return 0;
    }

    private void addAccumulatorScore(long key, int delta) {
        int threshold = (11 - sensitivity.get()) * 15; 
        synchronized (accumulatorLock) {
            int current = accumulator.getOrDefault(key, 0) + delta;
            accumulator.put(key, current);
            if (current >= threshold) {
                confirmedBases.put(key, (byte) 1);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Layer 4 — Bộ lọc dòng chảy chống báo động giả
    // -------------------------------------------------------------------------

    private void handleBlockUpdate(BlockUpdateS2CPacket packet) {
        var state = packet.getState();
        if (!state.getFluidState().isEmpty()) { 
            BlockPos pos = packet.getPos();
            long key = chunkKey(pos.getX() >> 4, pos.getZ() >> 4);
            synchronized (freshLock) { freshChunks.add(key); }
        }
    }

    // -------------------------------------------------------------------------
    // Sửa đổi tối ưu của Glazed: Cấy ghép PalettedContainer nâng cao
    // -------------------------------------------------------------------------

    private void heuristicScan(int cx, int cz, long key) {
        if (mc.world == null) return;
        var chunk = mc.world.getChunk(cx, cz);
        if (chunk == null || chunk.isEmpty()) return;

        int score = 0;
        for (var section : chunk.getSectionArray()) {
            if (section == null || section.isEmpty()) continue;
            PalettedContainer<BlockState> container = section.getBlockStateContainer();

            // BƯỚC NHẢY THẦN TỐC: Hỏi thẳng bảng màu xem có block bất thường không, bỏ qua 4096 vòng lặp vô nghĩa
            boolean hasTargetBlocks = container.hasAny(state -> {
                Block b = state.getBlock();
                return b == Blocks.AMETHYST_CLUSTER || b == Blocks.BEE_NEST || b == Blocks.DEEPSLATE;
            });

            if (!hasTargetBlocks) continue;

            // Nếu bảng màu xác nhận có, luồng phụ mới bóc tách chi tiết khối
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        BlockState state = container.get(x, y, z);
                        Block b = state.getBlock();

                        if (b == Blocks.AMETHYST_CLUSTER) score += 20;
                        else if (b == Blocks.BEE_NEST) score += 10;
                        else if (b == Blocks.DEEPSLATE && state.contains(Properties.AXIS)) {
                            if (state.get(Properties.AXIS) != Direction.Axis.Y) score += 50; // Quặng deepslate bị xoay ngang dọc do người đào rương
                        }
                    }
                }
            }
        }

        if (score > 0) addAccumulatorScore(key, score);
    }

    // -------------------------------------------------------------------------
    // Đồ họa 3D — Ép phẳng trục Y=62 bền vững
    // -------------------------------------------------------------------------

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null || mc.world == null || confirmedBases.isEmpty()) return;

        int playerCX = mc.player.getBlockX() >> 4;
        int playerCZ = mc.player.getBlockZ() >> 4;
        int rd = renderDistance.get();

        var keys = confirmedBases.keySet();
        SettingColor color = colorBase.get();
        Color lineColor = new Color(color.r, color.g, color.b, 255); // ĐÃ FIX LỖI: nhận diện class Color bình thường

        for (long key : keys) {
            int cx = (int)(key >> 32);
            int cz = (int)(key & 0xFFFFFFFFL);

            // Thuật toán khoảng cách Chebyshev lọc thảm đỏ ngoài tầm nhìn siêu tốc
            if (Math.abs(cx - playerCX) > rd || Math.abs(cz - playerCZ) > rd) continue;

            double x1 = cx << 4; 
            double z1 = cz << 4;
            double x2 = x1 + 16;
            double z2 = z1 + 16;
            double y  = 62.0;

            event.renderer.box(
                x1, y,     z1,
                x2, y + 0.1, z2,
                color, lineColor,
                ShapeMode.Both, 0
            );
        }
    }

    // Tích hợp thuật toán nén tọa độ X-Z gọn nhẹ không sinh rác bộ nhớ của Claude AI
    private static long chunkKey(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }
}
