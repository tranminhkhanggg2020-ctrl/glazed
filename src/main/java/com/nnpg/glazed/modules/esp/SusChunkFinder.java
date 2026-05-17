package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
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
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder().name("sensitivity").description("Điểm báo động tổng hợp").defaultValue(50).min(10).sliderMax(200).build());
    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 30, 30, 50)).build());

    // ── BIẾN LƯU TRỮ CHUNG ──
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Color renderColor = new Color();
    private final BlockPos.Mutable visibleAnchor = new BlockPos.Mutable();

    // ── KIẾN TRÚC LMAX DISRUPTOR (ZERO-ALLOC OFF-LOADER) ──
    private static final int BUFFER_SIZE = 8192;
    private static final int INDEX_MASK = BUFFER_SIZE - 1;

    static class PaddedSequence extends AtomicLong { public volatile long p1, p2, p3, p4, p5, p6, p7; }
    
    private final PaddedSequence writeSequence = new PaddedSequence();
    private final PaddedSequence readSequence = new PaddedSequence();
    private final EventPayload[] ringBuffer = new EventPayload[BUFFER_SIZE];
    private Thread workerThread;

    // Dữ liệu nội bộ xử lý logic Welford & Threat Scoring
    private final Long2ObjectMap<ChunkData> chunkDataMap = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>(2048));

    private static class EventPayload {
        long chunkKey;
        double addedThreat;
        boolean isBlockUpdate;
    }

    private static class StatisticalTickAnalyzer {
        private double mean = 0.0;
        private double m2 = 0.0;
        private int count = 0;
        private long lastEventTime = -1;

        public void recordEvent(long currentTimeMillis) {
            if (lastEventTime == -1) { lastEventTime = currentTimeMillis; return; }
            double interval = currentTimeMillis - lastEventTime;
            lastEventTime = currentTimeMillis;
            count++;
            double delta = interval - mean;
            mean += delta / count;
            m2 += delta * (interval - mean);
        }

        public boolean isArtificialRedstoneClock() {
            return count > 8 && (count < 2 || mean == 0 ? 1.0 : Math.sqrt(m2 / (count - 1)) / mean) < 0.1;
        }
    }

    private static class ChunkData {
        double totalThreat = 0;
        long lastUpdateTime = System.currentTimeMillis();
        final StatisticalTickAnalyzer analyzer = new StatisticalTickAnalyzer();
    }

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Dự án Radar Toàn tri: Âm thanh + Đọc trộm NBT + LMAX Offloader");
        for (int i = 0; i < BUFFER_SIZE; i++) ringBuffer[i] = new EventPayload();
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        chunkDataMap.clear();
        writeSequence.set(0);
        readSequence.set(0);
        
        workerThread = new Thread(() -> {
            while (!Thread.interrupted()) {
                long currentRead = readSequence.get();
                while (currentRead >= writeSequence.get()) {
                    LockSupport.parkNanos(1000);
                }

                EventPayload event = ringBuffer[(int) (currentRead & INDEX_MASK)];
                processThreatAsync(event.chunkKey, event.addedThreat, event.isBlockUpdate);
                readSequence.lazySet(currentRead + 1);
            }
        });
        workerThread.setName("SusChunkFinder-Analytics-Worker");
        workerThread.setDaemon(true);
        workerThread.setPriority(Thread.MIN_PRIORITY);
        workerThread.start();
    }

    @Override
    public void onDeactivate() {
        if (workerThread != null) workerThread.interrupt();
        suspiciousChunks.clear();
        chunkDataMap.clear();
    }

    // Luồng Worker Asynchronous xử lý logic Welford (Zero-Lag)
    private void processThreatAsync(long chunkKey, double addedThreat, boolean isBlockUpdate) {
        long currentTime = System.currentTimeMillis();
        ChunkData data = chunkDataMap.get(chunkKey);
        
        if (data == null) {
            data = new ChunkData();
            chunkDataMap.put(chunkKey, data);
        }

        if (currentTime - data.lastUpdateTime > 180000) {
            data.totalThreat = 0; // Reset sau 3 phút
        }

        data.totalThreat += addedThreat;
        data.lastUpdateTime = currentTime;

        if (isBlockUpdate) {
            data.analyzer.recordEvent(currentTime);
            if (data.analyzer.isArtificialRedstoneClock()) {
                flagChunk(chunkKey, "Redstone Clock ngầm");
                return;
            }
        }

        if (data.totalThreat >= sensitivity.get()) {
            flagChunk(chunkKey, "Vượt ngưỡng nguy hiểm (Âm thanh/NBT/Mọc cây)");
        }
    }

    private void flagChunk(long chunkKey, String reason) {
        int chunkX = (int) chunkKey;
        int chunkZ = (int) (chunkKey >> 32);
        ChunkPos cp = new ChunkPos(chunkX, chunkZ);
        
        if (!suspiciousChunks.contains(cp)) {
            suspiciousChunks.add(cp);
            ChatUtils.info("⚠️ [Radar Toàn Tri] Phát hiện: " + reason + " tại " + chunkX + ", " + chunkZ);
        }
    }

    // Gửi dữ liệu từ Netty vào luồng Worker LMAX Disruptor
    private void dispatchToWorker(long chunkKey, double threat, boolean isBlock) {
        long currentWrite = writeSequence.get();
        if (readSequence.get() <= currentWrite - BUFFER_SIZE) return; // Tránh tràn bộ đệm
        
        EventPayload event = ringBuffer[(int) (currentWrite & INDEX_MASK)];
        event.chunkKey = chunkKey;
        event.addedThreat = threat;
        event.isBlockUpdate = isBlock;
        writeSequence.lazySet(currentWrite + 1);
    }

    // ── HỆ THỐNG LẮNG NGHE GÓI TIN CHÍNH ──
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // 1. Phân tích Khối
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            evaluateBlock(packet.getPos(), packet.getState());
        } 
        
        // 2. Phân tích Xe Goòng tàng hình
        else if (event.packet instanceof EntitySpawnS2CPacket packet) {
            EntityType<?> type = packet.getEntityType();
            if (type == EntityType.CHEST_MINECART || type == EntityType.HOPPER_MINECART || type == EntityType.SPAWNER_MINECART) {
                long key = ChunkPos.toLong((int) packet.getX() >> 4, (int) packet.getZ() >> 4);
                dispatchToWorker(key, 100.0, false);
            }
        }
        
        // 3. Phân tích Âm thanh
        else if (event.packet instanceof PlaySoundS2CPacket packet) {
            RegistryEntry<SoundEvent> soundEntry = packet.getSound();
            String soundId = soundEntry.value().getId().getPath();
            double threat = 0;
            switch (soundId) {
                case "block.piston.extend": case "block.piston.contract": threat = 15; break;
                case "block.chest.open": case "block.chest.close": case "block.barrel.open": threat = 25; break;
                case "entity.bat.ambient": threat = 5; break;
            }
            if (threat > 0) {
                long key = ChunkPos.toLong((int) packet.getX() >> 4, (int) packet.getZ() >> 4);
                dispatchToWorker(key, threat, false);
            }
        }
        
        // 4. Phân tích NBT (Đọc trộm Lò nung/Phễu)
        else if (event.packet instanceof BlockEntityUpdateS2CPacket packet) {
            NbtCompound nbt = packet.getNbt();
            if (nbt != null && !nbt.isEmpty()) {
                double threat = 0;
                if (nbt.contains("BurnTime", NbtElement.SHORT_TYPE) && nbt.getShort("BurnTime") > 0) threat += 40;
                if (nbt.contains("Items", NbtElement.LIST_TYPE)) threat += 30;
                if (nbt.contains("SpawnData", NbtElement.COMPOUND_TYPE)) threat += 50;
                
                if (threat > 0) {
                    long key = ChunkPos.toLong(packet.getPos().getX() >> 4, packet.getPos().getZ() >> 4);
                    dispatchToWorker(key, threat, false);
                }
            }
        }
    }

    private void evaluateBlock(BlockPos pos, BlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        double threat = 0;
        boolean isRedstoneUpdate = false;

        if (state.isAir()) threat = 1;
        else if (block == Blocks.BAMBOO) { threat = pos.getY() < 50 ? 20 : 2; isRedstoneUpdate = true; }
        else if (block == Blocks.KELP || block == Blocks.KELP_PLANT) { threat = 2; isRedstoneUpdate = true; }
        else if (block == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) threat = 15;
        else if (block == Blocks.AMETHYST_CLUSTER) {
            threat = 15;
            executeIndirectSonar(pos); // Kích hoạt Ghost Sonar an toàn
        }

        if (threat > 0) {
            long key = ChunkPos.toLong(pos.getX() >> 4, pos.getZ() >> 4);
            dispatchToWorker(key, threat, isRedstoneUpdate);
        }
    }

    // ── GHOST SONAR GIÁN TIẾP CHỐNG GRIM AC ──
    private void executeIndirectSonar(BlockPos hiddenTarget) {
        if (mc.player == null) return;
        Vec3d eyePos = mc.player.getEyePos();
        boolean found = false;

        for (int x = -2; x <= 2 && !found; x++) {
            for (int y = -2; y <= 2 && !found; y++) {
                for (int z = -2; z <= 2 && !found; z++) {
                    visibleAnchor.set(hiddenTarget, x, y, z);
                    if (visibleAnchor.equals(hiddenTarget)) continue;

                    Vec3d targetCenter = Vec3d.ofCenter(visibleAnchor);
                    RaycastContext ctx = new RaycastContext(eyePos, targetCenter, RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player);
                    BlockHitResult hit = mc.player.clientWorld.raycast(ctx);
                    
                    if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(visibleAnchor)) {
                        found = true;
                        float yaw = (float) Math.toDegrees(Math.atan2(targetCenter.z - eyePos.z, targetCenter.x - eyePos.x)) - 90.0F;
                        float pitch = (float) -Math.toDegrees(Math.atan2(targetCenter.y - eyePos.y, Math.sqrt(Math.pow(targetCenter.x - eyePos.x, 2) + Math.pow(targetCenter.z - eyePos.z, 2))));
                        
                        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround()));
                        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, visibleAnchor, Direction.UP));
                        mc.player.networkHandler.sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, visibleAnchor, Direction.UP));
                    }
                }
            }
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (suspiciousChunks.isEmpty() || mc.player == null) return;
        SettingColor sc = color.get();
        renderColor.set(sc.r, sc.g, sc.b, sc.a);

        for (ChunkPos cp : suspiciousChunks) {
            event.renderer.box(cp.getStartX(), 50.0, cp.getStartZ(), cp.getStartX() + 16.0, 50.05, cp.getStartZ() + 16.0, renderColor, renderColor, ShapeMode.Sides, 0);
        }
    }
}
