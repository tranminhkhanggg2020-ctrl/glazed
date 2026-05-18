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
import meteordevelopment.meteorclient.events.world.TickEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
    private final Setting<Boolean> detectDroppedItems = sgDetect.add(new BoolSetting.Builder().name("dropped-items").description("Báo ngay khi có vật phẩm rơi trên đất").defaultValue(true).build());

    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 30, 30, 50)).build());
    private final Setting<Integer> alpha = sgRender.add(new IntSetting.Builder().name("alpha").defaultValue(50).min(0).sliderMax(255).build());

    // ── LƯU TRỮ ZERO-ALLOCATION ──
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Long2ObjectMap<ChunkData> chunkDataMap = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>(2048));
    private final Color renderColor = new Color();
    private int cleanupTimer = 0;
    private static final long DECAY_TIME_MS = 180000; // 3 phút lãng quên

    private static class ChunkData {
        int score = 0;
        long lastUpdateTime = System.currentTimeMillis();
        int updatesInLast10Sec = 0;
        long firstUpdateInWindow = 0;
    }

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar Tinh Gọn: Săn Farm AFK & Vật phẩm vứt lại.");
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

        // 1. Bắt cập nhật khối (Mọc cây/Đặt khối)
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            evaluateBlockThreat(packet.getPos(), packet.getState());
        } 
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::evaluateBlockThreat);
        }
        
        // 2. TÍNH NĂNG MỚI: ĐÓN ĐẦU VẬT PHẨM RƠI (ITEM ENTITY LEAK)
        else if (detectDroppedItems.get() && event.packet instanceof EntitySpawnS2CPacket packet) {
            if (packet.getEntityType() == EntityType.ITEM) {
                // Trích xuất tọa độ thực thể vật phẩm rơi
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(packet.getX(), packet.getY(), packet.getZ()));
                
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [ITEM FOUND] Phát hiện vật phẩm rơi chưa biến mất tại Chunk: X:" + cp.x + " Z:" + cp.z);
                }
            }
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
            executeIndirectSonar(pos); 
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

        if (currentTime - data.lastUpdateTime > DECAY_TIME_MS) {
            data.score = 0; 
            data.updatesInLast10Sec = 0;
        }

        data.score += threatScore;
        data.lastUpdateTime = currentTime;

        if (isAFKPredictableBlock) {
            if (data.updatesInLast10Sec == 0) {
                data.firstUpdateInWindow = currentTime;
            }
            if (currentTime - data.firstUpdateInWindow < 10000) {
                data.updatesInLast10Sec++;
            } else {
                data.updatesInLast10Sec = 1;
                data.firstUpdateInWindow = currentTime;
            }
            if (data.updatesInLast10Sec > 5) {
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [AFK RADAR] Báo động! Phát hiện cây mọc ĐỒNG LOẠT tại: X:" + cp.x + " Z:" + cp.z);
                    return;
                }
            }
        }

        if (data.score >= sensitivity.get()) { 
            suspiciousChunks.add(cp);
            ChatUtils.info("⚠️ [RADAR] Thay đổi khối bất thường tại: X:" + cp.x + " Z:" + cp.z);
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
