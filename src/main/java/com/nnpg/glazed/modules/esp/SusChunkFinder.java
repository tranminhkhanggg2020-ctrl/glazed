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
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.EntityType;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.registry.Registries;
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
import meteordevelopment.meteorclient.events.world.TickEvent;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");

    private final Setting<Integer> simulationDistance = sgGeneral.add(new IntSetting.Builder().name("simulation-distance").defaultValue(16).min(1).sliderMax(32).build());
    private final Setting<Integer> sensitivity = sgGeneral.add(new IntSetting.Builder().name("sensitivity").description("Ngưỡng báo động (Âm thanh/Realtime)").defaultValue(15).min(5).sliderMax(100).build());
    private final Setting<SettingColor> color = sgRender.add(new ColorSetting.Builder().name("color").defaultValue(new SettingColor(255, 30, 30, 50)).build());

    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Color renderColor = new Color();
    private final Long2ObjectMap<ChunkData> chunkDataMap = Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>(2048));
    private int cleanupTimer = 0;

    // ── OMEGA ANOMALY DICTIONARY (Bảng tra cứu O(1)) ──
    private static final byte[] ANOMALY_TABLE = new byte[65536];
    private static boolean dictionaryInitialized = false;
    
    private static final byte TYPE_NONE = 0;
    private static final byte TYPE_CHEST = 1;
    private static final byte TYPE_BARREL = 2;
    private static final byte TYPE_HOPPER = 3;
    private static final byte TYPE_SHULKER = 4;
    private static final byte TYPE_FURNACE = 5;
    private static final byte TYPE_CRAFTING = 6;
    private static final byte TYPE_ENDER_CHEST = 7;

    private final AnomalyScorer anomalyScorer = new AnomalyScorer();

    private static class ChunkData {
        double score = 0;
        long lastUpdateTime = System.currentTimeMillis();
    }

    // ── HỆ THỐNG ĐÁNH GIÁ TRỌNG SỐ DỊ THƯỜNG (SYNERGY SCORING) ──
    private class AnomalyScorer {
        private int currentChunkX, currentSectionY, currentChunkZ;
        private int presenceFlags;

        public void beginSection(int chunkX, int sectionY, int chunkZ) {
            this.currentChunkX = chunkX;
            this.currentSectionY = sectionY;
            this.currentChunkZ = chunkZ;
            this.presenceFlags = 0;
        }

        public void evaluateBlock(int chunkX, int sectionY, int chunkZ, int rawStateId) {
            if (rawStateId >= 0 && rawStateId < 65536) {
                byte type = ANOMALY_TABLE[rawStateId];
                if (type != TYPE_NONE) {
                    this.currentChunkX = chunkX;
                    this.currentSectionY = sectionY;
                    this.currentChunkZ = chunkZ;
                    presenceFlags |= (1 << type);
                }
            }
        }

        public void finalizeSection() {
            if (presenceFlags == 0) return;

            double baseScore = 0;
            boolean hasChest      = (presenceFlags & (1 << TYPE_CHEST)) != 0;
            boolean hasBarrel     = (presenceFlags & (1 << TYPE_BARREL)) != 0;
            boolean hasHopper     = (presenceFlags & (1 << TYPE_HOPPER)) != 0;
            boolean hasShulker    = (presenceFlags & (1 << TYPE_SHULKER)) != 0;
            boolean hasFurnace    = (presenceFlags & (1 << TYPE_FURNACE)) != 0;
            boolean hasCrafting   = (presenceFlags & (1 << TYPE_CRAFTING)) != 0;
            boolean hasEnderChest = (presenceFlags & (1 << TYPE_ENDER_CHEST)) != 0;

            if (hasChest) baseScore += 15;
            if (hasBarrel) baseScore += 30;
            if (hasCrafting) baseScore += 40;
            if (hasEnderChest) baseScore += 70;
            if (hasHopper) baseScore += 80;
            if (hasShulker) baseScore += 100;

            double synergy = 1.0;
            if (hasChest && hasCrafting) synergy = Math.max(synergy, 2.5);
            if (hasHopper && (hasChest || hasBarrel)) synergy = Math.max(synergy, 3.0);
            if (hasChest && hasCrafting && hasFurnace) synergy = Math.max(synergy, 5.0);
            if (hasShulker) synergy = Math.max(synergy, 5.0);

            int absoluteY = currentSectionY * 16;
            double heightMultiplier = 1.0;
            if (absoluteY > 64) heightMultiplier = 0.5;
            else if (absoluteY < 0) heightMultiplier = 2.5;

            double finalScore = baseScore * heightMultiplier * synergy;

            if (finalScore >= 100.0) {
                ChunkPos cp = new ChunkPos(currentChunkX, currentChunkZ);
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [PRIME CHUNK] Bắt được tín hiệu Stash ngầm! (Tọa độ X:" + (currentChunkX * 16) + " Y:" + absoluteY + " Z:" + (currentChunkZ * 16) + ") | Tỷ lệ Base: 99.9%");
                }
            }
        }
    }

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Radar OMEGA: Prime Chunk Finder (Đọc Palette) + Entity Leak + Ghost Sonar");
    }

    private void initializeDictionary() {
        if (dictionaryInitialized) return;
        for (BlockState state : Registries.BLOCK) {
            int stateId = Block.getRawIdFromState(state);
            if (stateId < 0 || stateId >= 65536) continue;
            Block block = state.getBlock();
            
            if (block == Blocks.CHEST || block == Blocks.TRAPPED_CHEST) ANOMALY_TABLE[stateId] = TYPE_CHEST;
            else if (block == Blocks.BARREL) ANOMALY_TABLE[stateId] = TYPE_BARREL;
            else if (block == Blocks.HOPPER) ANOMALY_TABLE[stateId] = TYPE_HOPPER;
            else if (block == Blocks.SHULKER_BOX) ANOMALY_TABLE[stateId] = TYPE_SHULKER;
            else if (block == Blocks.FURNACE || block == Blocks.BLAST_FURNACE || block == Blocks.SMOKER) ANOMALY_TABLE[stateId] = TYPE_FURNACE;
            else if (block == Blocks.CRAFTING_TABLE) ANOMALY_TABLE[stateId] = TYPE_CRAFTING;
            else if (block == Blocks.ENDER_CHEST) ANOMALY_TABLE[stateId] = TYPE_ENDER_CHEST;
        }
        dictionaryInitialized = true;
    }

    @Override
    public void onActivate() {
        initializeDictionary();
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
            chunkDataMap.long2ObjectEntrySet().removeIf(entry -> currentTime - entry.getValue().lastUpdateTime > 180000);
        }
    }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // ── PROJECT OMEGA: ĐỌC TRỘM CHUNK DATA (ZERO-ALLOCATION) ──
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
            int originalIndex = buf.readerIndex();
            
            try {
                int chunkX = packet.getChunkX();
                int chunkZ = packet.getChunkZ();

                // Duyệt 24 ChunkSections (Y: -64 -> 320)
                for (int sectionIndex = 0; sectionIndex < 24; sectionIndex++) {
                    if (buf.readerIndex() >= buf.writerIndex()) break;
                    int sectionY = sectionIndex - 4;
                    buf.readShort(); // Bỏ qua BlockCount
                    
                    // Xử lý BlockStates Palette
                    byte bitsPerEntry = buf.readByte();
                    if (bitsPerEntry == 0) {
                        int stateId = buf.readVarInt();
                        anomalyScorer.evaluateBlock(chunkX, sectionY, chunkZ, stateId);
                        int dataLen = buf.readVarInt();
                        buf.skipBytes(dataLen * 8);
                    } else if (bitsPerEntry >= 1 && bitsPerEntry <= 8) {
                        int paletteSize = buf.readVarInt();
                        anomalyScorer.beginSection(chunkX, sectionY, chunkZ);
                        for (int i = 0; i < paletteSize; i++) {
                            int stateId = buf.readVarInt();
                            anomalyScorer.evaluateBlock(chunkX, sectionY, chunkZ, stateId);
                        }
                        anomalyScorer.finalizeSection();
                        int dataLen = buf.readVarInt();
                        buf.skipBytes(dataLen * 8);
                    } else {
                        // Global Palette - Tua qua
                        int dataLen = buf.readVarInt();
                        buf.skipBytes(dataLen * 8);
                    }
                    
                    // Tua qua Biomes Palette
                    byte biomeBits = buf.readByte();
                    if (biomeBits == 0) {
                        buf.readVarInt();
                        int dataLen = buf.readVarInt();
                        buf.skipBytes(dataLen * 8);
                    } else if (biomeBits >= 1 && biomeBits <= 3) {
                        int paletteSize = buf.readVarInt();
                        for (int i = 0; i < paletteSize; i++) buf.readVarInt();
                        int dataLen = buf.readVarInt();
                        buf.skipBytes(dataLen * 8);
                    } else {
                        int dataLen = buf.readVarInt();
                        buf.skipBytes(dataLen * 8);
                    }
                }
            } catch (Exception ignored) {
            } finally {
                // Đưa con trỏ đọc về vị trí ban đầu để Minecraft tiếp tục xử lý, không gây crash!
                buf.readerIndex(originalIndex);
            }
        }
        
        // ── MODULE CŨ: NGHE LÉN ÂM THANH & CẬP NHẬT ĐỘNG ──
        else if (event.packet instanceof PlaySoundS2CPacket packet) {
            RegistryEntry<SoundEvent> soundEntry = packet.getSound();
            String soundId = soundEntry.getKey().map(k -> k.getValue().getPath()).orElse("");
            double threat = 0;
            switch (soundId) {
                case "block.piston.extend": case "block.piston.contract": threat = 15; break;
                case "block.chest.open": case "block.chest.close": case "block.barrel.open": threat = 25; break;
                case "entity.bat.ambient": threat = 5; break;
            }
            if (threat > 0) processDynamicThreat((int)packet.getX() >> 4, (int)packet.getZ() >> 4, threat, "Phát hiện âm thanh nhân tạo");
        }
        else if (event.packet instanceof BlockUpdateS2CPacket packet) {
            evaluateBlockDynamic(packet.getPos(), packet.getState());
        } 
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::evaluateBlockDynamic);
        }
        else if (event.packet instanceof EntitySpawnS2CPacket packet) {
            EntityType<?> type = packet.getEntityType();
            if (type == EntityType.CHEST_MINECART || type == EntityType.HOPPER_MINECART || type == EntityType.SPAWNER_MINECART) {
                ChunkPos cp = new ChunkPos(BlockPos.ofFloored(packet.getX(), packet.getY(), packet.getZ()));
                if (!suspiciousChunks.contains(cp)) {
                    suspiciousChunks.add(cp);
                    ChatUtils.info("⚠️ [ENTITY LEAK] Phát hiện Minecart ngầm tại: " + cp.x + ", " + cp.z);
                }
            }
        }
    }

    private void evaluateBlockDynamic(BlockPos pos, BlockState state) {
        net.minecraft.block.Block block = state.getBlock();
        double threat = 0;
        if (state.isAir()) threat = 1;
        else if (block == Blocks.BAMBOO && pos.getY() < 50) threat = 20;
        else if (block == Blocks.DEEPSLATE && state.contains(Properties.AXIS) && state.get(Properties.AXIS) != Direction.Axis.Y) threat = 15;
        else if (block == Blocks.AMETHYST_CLUSTER) {
            threat = 15;
            executeIndirectSonar(pos);
        }
        if (threat > 0) processDynamicThreat(pos.getX() >> 4, pos.getZ() >> 4, threat, "Biến động khối bất thường");
    }

    private void processDynamicThreat(int chunkX, int chunkZ, double threat, String reason) {
        long key = ChunkPos.toLong(chunkX, chunkZ);
        long currentTime = System.currentTimeMillis();
        ChunkData data = chunkDataMap.computeIfAbsent(key, k -> new ChunkData());
        
        if (currentTime - data.lastUpdateTime > 180000) data.score = 0;
        data.score += threat;
        data.lastUpdateTime = currentTime;

        if (data.score >= sensitivity.get()) {
            ChunkPos cp = new ChunkPos(chunkX, chunkZ);
            if (!suspiciousChunks.contains(cp)) {
                suspiciousChunks.add(cp);
                ChatUtils.info("⚠️ [RADAR] Đã chốt vị trí khả nghi! (X:" + (chunkX * 16) + " Z:" + (chunkZ * 16) + ") | Nguyên nhân: " + reason);
            }
        }
    }

    // ── GHOST SONAR CHỐNG GRIM AC ──
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
        renderColor.set(sc.r, sc.g, sc.b, sc.a);

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
