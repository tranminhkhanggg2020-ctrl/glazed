package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
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
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
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
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect  = settings.createGroup("Detection");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder().name("simulation-distance").defaultValue(12).min(1).sliderMax(32).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder().name("sensitivity").defaultValue(20).min(1).sliderMax(50).build());
    
    private final Setting<Boolean> detectBamboo = sgDetect.add(new BoolSetting.Builder().name("bamboo").description("Phát hiện farm Tre").defaultValue(true).build());
    private final Setting<Boolean> detectKelp = sgDetect.add(new BoolSetting.Builder().name("kelp").description("Phát hiện farm Tảo").defaultValue(true).build());
    private final Setting<Boolean> detectAmethyst = sgDetect.add(new BoolSetting.Builder().name("amethyst").description("Dò Thạch anh & Ghost Sonar").defaultValue(true).build());
    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(new BoolSetting.Builder().name("rotated-deepslate").defaultValue(true).build());
    private final Setting<Boolean> detectAir = sgDetect.add(new BoolSetting.Builder().name("air-updates").description("Báo khi khối bị đập (Tắt khi ra biển)").defaultValue(false).build());
    private final Setting<Boolean> detectDroppedItems = sgDetect.add(new BoolSetting.Builder().name("dropped-items").description("Vật phẩm rơi (Tắt khi ra biển)").defaultValue(false).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 30, 30, 50)).build());
    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder().name("alpha").defaultValue(50).min(0).sliderMax(255).build());

    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Color renderColor = new Color();
    private int cleanupTimer = 0;

    // ==========================================
    // CLAUDE'S CORE: LOCK-FREE SPATIAL TELEMETRY
    // ==========================================

    private static final long EMPTY_KEY = Long.MIN_VALUE;

    public static final class LockFreeLongMap<V> {
        private final AtomicLongArray keys;
        private final AtomicReferenceArray<V> values;
        private final int mask;

        public LockFreeLongMap(int capacity) {
            int cap = Integer.highestOneBit(capacity - 1) << 1;
            this.keys = new AtomicLongArray(cap);
            this.values = new AtomicReferenceArray<>(cap);
            this.mask = cap - 1;
            for (int i = 0; i < cap; i++) keys.set(i, EMPTY_KEY);
        }

        public V get(long key) {
            int idx = hash(key);
            while (true) {
                long existing = keys.get(idx);
                if (existing == key) return values.get(idx);
                if (existing == EMPTY_KEY) return null;
                idx = (idx + 1) & mask;
            }
        }

        public void put(long key, V value) {
            int idx = hash(key);
            while (true) {
                long existing = keys.get(idx);
                if (existing == key) { values.set(idx, value); return; }
                if (existing == EMPTY_KEY) {
                    if (keys.compareAndSet(idx, EMPTY_KEY, key)) { values.set(idx, value); return; }
                    continue;
                }
                idx = (idx + 1) & mask;
            }
        }

        private int hash(long key) {
            key = (key ^ (key >>> 30)) * 0xbf58476d1ce4e5b9L;
            key = (key ^ (key >>> 27)) * 0x94d049bb133111ebL;
            return (int)(key ^ (key >>> 31)) & mask;
        }
        
        public void clearOldEntries(long nowNano) {
            for (int i = 0; i < keys.length(); i++) {
                if (keys.get(i) != EMPTY_KEY) {
                    V val = values.get(i);
                    if (val instanceof PaddedCellState) {
                        if (nowNano - ((PaddedCellState) val).lastUpdateNano > 180_000_000_000L) {
                            keys.set(i, EMPTY_KEY);
                            values.set(i, null);
                        }
                    }
                }
            }
        }
    }

    public static final class TokenBucketState {
        private static final long SCALE = 1_000_000_000L;
        private static final long MAX_TOKENS = 5L * SCALE;   // Cửa sổ 5 block update
        private static final long REFILL_RATE_NS = SCALE / 5;  // 0.5 khối / giây

        private final AtomicLong tokens;
        private final AtomicLong lastRefillNano;

        public TokenBucketState(long nowNano) {
            this.tokens = new AtomicLong(MAX_TOKENS);
            this.lastRefillNano = new AtomicLong(nowNano);
        }

        public boolean consumeAndCheckBurst(long nowNano) {
            refill(nowNano);
            while (true) {
                long current = tokens.get();
                if (current < SCALE) return true; // Cạn sạch token -> Báo động AFK Farm
                if (tokens.compareAndSet(current, current - SCALE)) return false;
            }
        }

        private void refill(long nowNano) {
            while (true) {
                long last = lastRefillNano.get();
                long elapsed = nowNano - last;
                if (elapsed <= 0) return;

                long toAdd = elapsed / REFILL_RATE_NS * SCALE;
                if (toAdd == 0) return;

                long newLast = last + (toAdd / SCALE) * REFILL_RATE_NS;
                if (!lastRefillNano.compareAndSet(last, newLast)) continue;
                tokens.updateAndGet(t -> Math.min(t + toAdd, MAX_TOKENS));
                return;
            }
        }
    }

    public static final class PaddedCellState {
        protected long p01, p02, p03, p04, p05, p06, p07;
        public volatile long score = 0;
        
        protected long q01, q02, q03, q04, q05, q06, q07;
        protected long s01, s02, s03, s04, s05, s06, s07;
        protected long r01, r02, r03, r04, r05, r06, r07;
        
        public volatile long lastUpdateNano = 0;
        
        protected long t01, t02, t03, t04, t05, t06, t07;

        public final TokenBucketState bucket;
        public PaddedCellState(long nowNano) { this.bucket = new TokenBucketState(nowNano); }
    }

    // Khởi tạo bản đồ 65k ô chứa dữ liệu mượt mà, ko lag
    private final LockFreeLongMap<PaddedCellState> cellMap = new LockFreeLongMap<>(65536);

    // ==========================================
    // LOGIC MODULE METEOR CLIENT
    // ==========================================

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar V2.0: IoT Lock-Free & Leaky Bucket AFK Predictor");
    }

    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        cleanupTimer = 0;
    }

    @Override
    public void onDeactivate() {
        suspiciousChunks.clear();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;
        cleanupTimer++;
        if (cleanupTimer >= 1200) {
            cleanupTimer = 0;
            // Xóa rác 3 phút để tiết kiệm bộ đệm
            cellMap.clearOldEntries(System.nanoTime());
        }
    }

    private static long encodeKey(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            evaluateBlockThreat(packet.getPos(), packet.getState());
        } 
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::evaluateBlockThreat);
        }
        else if (detectDroppedItems.get() && event.packet instanceof EntitySpawnS2CPacket packet) {
            if (packet.getEntityType() == EntityType.ITEM) {
                int chunkX = (int)packet.getX() >> 4;
                int chunkZ = (int)packet.getZ() >> 4;
                ChunkPos cp = new ChunkPos(chunkX, chunkZ);
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [ITEM LEAK] Phát hiện vật phẩm rơi tại X:" + cp.getStartX() + " Z:" + cp.getStartZ());
                }
            }
        }
    }

    private void evaluateBlockThreat(BlockPos pos, BlockState state) {
        int threatScore = 0;
        boolean isAFKPredictableBlock = false;
        net.minecraft.block.Block block = state.getBlock();

        if (detectAir.get() && state.isAir()) {
            threatScore = 1; 
        } else if (detectBamboo.get() && block == Blocks.BAMBOO) {
            threatScore = (pos.getY() < 50) ? 20 : 2; 
            isAFKPredictableBlock = true;
        } else if (detectKelp.get() && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
            threatScore = 2;
            isAFKPredictableBlock = true;
        } else if (detectAmethyst.get() && (block == Blocks.AMETHYST_CLUSTER || block == Blocks.SMALL_AMETHYST_BUD || block == Blocks.MEDIUM_AMETHYST_BUD || block == Blocks.LARGE_AMETHYST_BUD)) {
            threatScore = 15;
            executeIndirectSonar(pos); 
        } else if (detectRotatedDeepslate.get() && block == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) {
            threatScore = 15; 
        }

        if (threatScore == 0) return;

        int chunkX = pos.getX() >> 4;
        int chunkZ = pos.getZ() >> 4;
        ChunkPos cp = new ChunkPos(chunkX, chunkZ);
        if (suspiciousChunks.contains(cp)) return;

        long key = encodeKey(chunkX, chunkZ);
        long nowNano = System.nanoTime();

        PaddedCellState cell = cellMap.get(key);
        if (cell == null) {
            cellMap.put(key, new PaddedCellState(nowNano));
            cell = cellMap.get(key);
        }

        cell.lastUpdateNano = nowNano;
        cell.score += threatScore;

        // ── THUẬT TOÁN LEAKY BUCKET BẮT FARM AFK ──
        if (isAFKPredictableBlock) {
            if (cell.bucket.consumeAndCheckBurst(nowNano)) {
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("🔥 [FARM AFK] Phát hiện luồng cây mọc đồng loạt tại X:" + cp.getStartX() + " Z:" + cp.getStartZ());
                    return;
                }
            }
        }

        if (cell.score >= sensitivity.get()) { 
            suspiciousChunks.add(cp);
            ChatUtils.info("⚠️ [RADAR] Thay đổi khối khả nghi tại X:" + cp.getStartX() + " Z:" + cp.getStartZ());
        }
    }

    private void executeIndirectSonar(BlockPos hiddenTarget) {
        if (mc.player == null) return;
        Vec3d eyePos = mc.player.getEyePos();
        boolean found = false;
        BlockPos.Mutable visibleAnchor = new BlockPos.Mutable();

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
                        
                        mc.player.networkHandler.sendPacket(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
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
        renderColor.set(sc.r, sc.g, sc.b, alpha.get());

        for (ChunkPos cp : suspiciousChunks) {
            if (!isInRange(cp)) continue;
            event.renderer.box(cp.getStartX(), 50.0, cp.getStartZ(), cp.getStartX() + 16.0, 50.05, cp.getStartZ() + 16.0, renderColor, renderColor, ShapeMode.Sides, 0);
        }
    }

    private boolean isInRange(ChunkPos pos) {
        if (mc.player == null) return false;
        ChunkPos playerChunk = mc.player.getChunkPos();
        int dist = simulationDistance.get();
        return Math.abs(pos.x - playerChunk.x) <= dist && Math.abs(pos.z - playerChunk.z) <= dist;
    }
}
