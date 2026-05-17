package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
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
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.state.property.Properties;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect  = settings.createGroup("Detection");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder().name("simulation-distance").defaultValue(4).min(1).sliderMax(32).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder().name("sensitivity").defaultValue(3).min(1).sliderMax(20).build());
    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 30, 30, 50)).build());
    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder().name("alpha").defaultValue(50).min(0).sliderMax(255).build());

    private final Setting<Boolean> detectBamboo = sgDetect.add(new BoolSetting.Builder().name("bamboo").defaultValue(true).build());
    private final Setting<Boolean> detectKelp = sgDetect.add(new BoolSetting.Builder().name("kelp").defaultValue(true).build());
    private final Setting<Boolean> detectAmethyst = sgDetect.add(new BoolSetting.Builder().name("amethyst").defaultValue(true).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(true).build());

    // ── DEEP RESEARCH #2: STEALTH MEMORY MANAGER (ZERO-ALLOCATION) ──
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Long2ObjectMap<ChunkData> chunkDataMap = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>(2048));
    private final Color renderColor = new Color();
    private int cleanupTimer = 0;
    private static final long DECAY_TIME_MS = 180000;

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
        int score = 0;
        long lastUpdateTime = System.currentTimeMillis();
        int updatesInLast10Sec = 0;
        long firstUpdateInWindow = 0;
        final StatisticalTickAnalyzer analyzer = new StatisticalTickAnalyzer(); 
    }

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar Deep Research: Welford + EntityLeak + GhostSonar + ZeroAlloc");
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        chunkDataMap.clear();
        cleanupTimer = 0;
    }

    @Override
    public void onDeactivate() {
        suspiciousChunks.clear();
        chunkDataMap.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive() || chunkDataMap.isEmpty()) return;
        cleanupTimer++;
        if (cleanupTimer >= 1200) {
            cleanupTimer = 0;
            long currentTime = System.currentTimeMillis();
            chunkDataMap.long2ObjectEntrySet().removeIf(entry -> currentTime - entry.getValue().lastUpdateTime > DECAY_TIME_MS * 2);
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // Bắt cập nhật khối
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            evaluateBlockThreat(packet.getPos(), packet.getState());
        } 
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::evaluateBlockThreat);
        }
        
        // ── DEEP RESEARCH #1: ENTITY LEAK SCANNER (Bắt Xe Goòng) ──
        else if (event.packet instanceof EntitySpawnS2CPacket packet) {
            EntityType<?> type = packet.getEntityType();
            if (type == EntityType.CHEST_MINECART || type == EntityType.HOPPER_MINECART || type == EntityType.SPAWNER_MINECART) {
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(packet.getX(), packet.getY(), packet.getZ()));
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [SusChunk] BÁO ĐỘNG ĐỎ! Phát hiện Minecart ngầm (Entity Leak) tại: " + cp.x + ", " + cp.z);
                }
            }
        }
    }

    // ── DEEP RESEARCH #3: GHOST MINER SONAR ──
    private void pingSuspiciousCoordinate(BlockPos target) {
        if (mc.getNetworkHandler() != null) {
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, target, Direction.UP));
            mc.getNetworkHandler().sendPacket(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, target, Direction.UP));
        }
    }

    private void evaluateBlockThreat(BlockPos pos, BlockState state) {
        int threatScore = 0;
        boolean isAFKPredictableBlock = false;
        net.minecraft.block.Block block = state.getBlock();

        if (state.isAir()) {
            threatScore = 1; 
        } else if (detectBamboo.get() && block == Blocks.BAMBOO) {
            threatScore = (pos.getY() < 50) ? 20 : 2; 
            isAFKPredictableBlock = true;
        } else if (detectKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
            threatScore = 2;
            isAFKPredictableBlock = true;
        } else if (detectAmethyst.get() && (block == Blocks.AMETHYST_CLUSTER || block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD)) {
            threatScore = 15;
            
            // Kích hoạt Ghost Sonar khi phát hiện Thạch anh để ép Server xác nhận khu vực xung quanh
            pingSuspiciousCoordinate(pos);
            
        } else if (detectRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) {
            threatScore = 15;
        }

        if (threatScore == 0) return;

        ChunkPos cp = new ChunkPos(pos);
        if (suspiciousChunks.contains(cp)) return;

        long key = ChunkPos.toLong(cp.x, cp.z);
        long currentTime = System.currentTimeMillis();
        
        ChunkData data = chunkDataMap.get(key);
        if (data == null) {
            data = new ChunkData();
            chunkDataMap.put(key, data);
        }
        
        // Đo đếm Redstone
        data.analyzer.recordEvent(currentTime);
        if (data.analyzer.isArtificialRedstoneClock()) {
            suspiciousChunks.add(cp);
            ChatUtils.info("⚠️ [SusChunk] BÁO ĐỘNG ĐỎ! Phát hiện Redstone Clock ngầm tại: " + cp.x + ", " + cp.z);
            return;
        }

        if (currentTime - data.lastUpdateTime > DECAY_TIME_MS) {
            data.score = 0; 
            data.updatesInLast10Sec = 0;
        }

        data.score += threatScore;
        data.lastUpdateTime = currentTime;

        if (isAFKPredictableBlock) {
            if (data.updatesInLast10Sec == 0) { data.firstUpdateInWindow = currentTime; }
            if (currentTime - data.firstUpdateInWindow < 10000) { data.updatesInLast10Sec++; } 
            else { data.updatesInLast10Sec = 1; data.firstUpdateInWindow = currentTime; }
            
            if (data.updatesInLast10Sec > 5) {
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [SusChunk] Báo động! Phát hiện AFK Farm mọc đồng loạt tại: " + cp.x + ", " + cp.z);
                    return;
                }
            }
        }

        if (data.score >= sensitivity.get() * 5) { 
            suspiciousChunks.add(cp);
            ChatUtils.info("⚠️ [SusChunk] Báo động hoạt động tại Chunk: " + cp.x + ", " + cp.z);
        }
    }

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (suspiciousChunks.isEmpty() || mc.player == null || mc.world == null) return;

        SettingColor sc = color.get();
        renderColor.set(sc.r, sc.g, sc.b, alpha.get());

        for (ChunkPos cp : suspiciousChunks) {
            if (!isInRange(cp)) continue;

            double x1 = cp.getStartX();
            double z1 = cp.getStartZ();
            event.renderer.box(x1, 50.0, z1, x1 + 16.0, 50.05, z1 + 16.0, renderColor, renderColor, ShapeMode.Sides, 0);
        }
    }

    private boolean isInRange(ChunkPos pos) {
        if (mc.player == null) return false;
        ChunkPos playerChunk = mc.player.getChunkPos();
        int dist = simulationDistance.get();
        return Math.abs(pos.x - playerChunk.x) <= dist && Math.abs(pos.z - playerChunk.z) <= dist;
    }
}
