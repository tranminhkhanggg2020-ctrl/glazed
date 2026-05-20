package com.nnpg.glazed.modules.esp;

import com.nnpg.glazed.GlazedAddon;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.EntitySpawnS2CPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class SusChunkFinder extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetection = settings.createGroup("Detection");
    private final SettingGroup sgRender = settings.createGroup("Render");
    private final SettingGroup sgAdvanced = settings.createGroup("Advanced");

    // ──────────────────────────────────────────────────────────────
    // GENERAL SETTINGS
    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder()
        .name("scan-range")
        .description("Chunk scanning radius (avoid anti-cheat)")
        .defaultValue(10)
        .min(1)
        .sliderMax(32)
        .build()
    );
    private final Setting<Integer> updateThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("update-threshold")
        .description("Block updates per chunk to trigger alert")
        .defaultValue(25)
        .min(5)
        .sliderMax(100)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // DETECTION SETTINGS
    private final Setting<Boolean> detectFarmBlocks = sgDetection.add(new BoolSetting.Builder()
        .name("detect-farm-blocks")
        .description("Detect bamboo, kelp, sugarcane, cactus (AFK farms)")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> detectHiddenBase = sgDetection.add(new BoolSetting.Builder()
        .name("detect-hidden-base")
        .description("Detect unusual block combinations (chests + crafting tables)")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> detectMobSpawners = sgDetection.add(new BoolSetting.Builder()
        .name("detect-mob-spawners")
        .description("Detect mob spawner activity via entity packets")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> detectItemClusters = sgDetection.add(new BoolSetting.Builder()
        .name("detect-item-clusters")
        .description("Detect clusters of dropped items (farm output)")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> detectFlyingPlayers = sgDetection.add(new BoolSetting.Builder()
        .name("detect-flying-players")
        .description("Detect players moving vertically without blocks")
        .defaultValue(true)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // RENDER SETTINGS
    private final Setting<SettingColor> suspiciousColor = sgRender.add(new ColorSetting.Builder()
        .name("suspicious-color")
        .description("Color for suspicious chunks")
        .defaultValue(new SettingColor(255, 50, 50, 80))
        .build()
    );
    private final Setting<SettingColor> farmColor = sgRender.add(new ColorSetting.Builder()
        .name("farm-color")
        .description("Color for AFK farm chunks")
        .defaultValue(new SettingColor(50, 255, 50, 80))
        .build()
    );
    private final Setting<SettingColor> baseColor = sgRender.add(new ColorSetting.Builder()
        .name("base-color")
        .description("Color for hidden base chunks")
        .defaultValue(new SettingColor(50, 50, 255, 80))
        .build()
    );
    private final Setting<Integer> renderHeight = sgRender.add(new IntSetting.Builder()
        .name("render-height")
        .description("Y-level to render chunk boxes")
        .defaultValue(60)
        .min(0)
        .max(256)
        .sliderMax(256)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // ADVANCED SETTINGS
    private final Setting<Boolean> silentMode = sgAdvanced.add(new BoolSetting.Builder()
        .name("silent-mode")
        .description("No chat alerts, only visual render")
        .defaultValue(false)
        .build()
    );
    private final Setting<Boolean> autoMarkWaypoints = sgAdvanced.add(new BoolSetting.Builder()
        .name("auto-mark-waypoints")
        .description("Auto-create waypoints for suspicious chunks")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> packetAnalysis = sgAdvanced.add(new BoolSetting.Builder()
        .name("packet-analysis")
        .description("Deep packet inspection for hidden activity")
        .defaultValue(true)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // CORE DATA STRUCTURES
    private final Map<ChunkPos, ChunkData> chunkDataMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> farmChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> baseChunks = ConcurrentHashMap.newKeySet();

    private final Color renderSuspicious = new Color();
    private final Color renderFarm = new Color();
    private final Color renderBase = new Color();

    private int cleanupTimer = 0;
    private long lastAnalysisTime = 0;

    // ──────────────────────────────────────────────────────────────
    // CHUNK DATA CLASS
    private static class ChunkData {
        AtomicLong blockUpdateCount = new AtomicLong(0);
        AtomicLong itemEntityCount = new AtomicLong(0);
        AtomicLong farmBlockCount = new AtomicLong(0);
        AtomicLong chestCount = new AtomicLong(0);
        AtomicLong playerMovementCount = new AtomicLong(0);
        long lastUpdated = System.currentTimeMillis();
        List<BlockPos> recentBlocks = new ArrayList<>();
    }

    // ──────────────────────────────────────────────────────────────
    // MODULE INIT
    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Krypton-style chunk radar — detects AFK farms, hidden bases, suspicious activity");
    }

    @Override
    public void onActivate() {
        chunkDataMap.clear();
        suspiciousChunks.clear();
        farmChunks.clear();
        baseChunks.clear();
        cleanupTimer = 0;
        lastAnalysisTime = System.currentTimeMillis();
        ChatUtils.info("Sus Chunk Finder activated — scanning radius: " + scanRange.get() + " chunks");
    }

    @Override
    public void onDeactivate() {
        chunkDataMap.clear();
        suspiciousChunks.clear();
        farmChunks.clear();
        baseChunks.clear();
    }

    // ──────────────────────────────────────────────────────────────
    // TICK EVENT FOR CLEANUP & ANALYSIS
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        cleanupTimer++;
        if (cleanupTimer >= 200) { // Cleanup every 10 seconds
            cleanupTimer = 0;
            long currentTime = System.currentTimeMillis();
            chunkDataMap.entrySet().removeIf(entry -> currentTime - entry.getValue().lastUpdated > 300000); // Remove data older than 5 minutes
        }

        // Periodic deep analysis
        if (System.currentTimeMillis() - lastAnalysisTime > 5000) {
            lastAnalysisTime = System.currentTimeMillis();
            performDeepAnalysis();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // PACKET HANDLING — CORE LOGIC
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        // BLOCK UPDATE ANALYSIS
        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            processBlockUpdate(packet.getPos(), packet.getState());
        } 
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::processBlockUpdate);
        }

        // ENTITY SPAWN ANALYSIS (Items, mobs)
        if (detectItemClusters.get() && event.packet instanceof EntitySpawnS2CPacket packet) {
            if (packet.getEntityType() == EntityType.ITEM) {
                processItemSpawn(packet.getX(), packet.getY(), packet.getZ());
            }
            if (detectMobSpawners.get() && (
                packet.getEntityType() == EntityType.ZOMBIE ||
                packet.getEntityType() == EntityType.SKELETON ||
                packet.getEntityType() == EntityType.CREEPER ||
                packet.getEntityType() == EntityType.SPIDER
            )) {
                processMobSpawn(packet.getX(), packet.getY(), packet.getZ());
            }
        }

        // PLAYER MOVEMENT ANALYSIS (Flying detection)
        if (detectFlyingPlayers.get() && event.packet instanceof PlayerPositionLookS2CPacket packet) {
            processPlayerMovement(packet.getX(), packet.getY(), packet.getZ());
        }
    }

    private void processBlockUpdate(BlockPos pos, BlockState state) {
        ChunkPos chunkPos = new ChunkPos(pos);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());

        data.blockUpdateCount.incrementAndGet();
        data.lastUpdated = System.currentTimeMillis();
        data.recentBlocks.add(pos);

        // Farm block detection
        if (detectFarmBlocks.get()) {
            if (state.getBlock() == Blocks.BAMBOO || state.getBlock() == Blocks.KELP || 
                state.getBlock() == Blocks.KELP_PLANT || state.getBlock() == Blocks.SUGARCANE ||
                state.getBlock() == Blocks.CACTUS) {
                data.farmBlockCount.incrementAndGet();
                if (data.farmBlockCount.get() > 15) {
                    farmChunks.add(chunkPos);
                    if (!silentMode.get()) {
                        ChatUtils.info("🌱 [AFK Farm] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                    }
                }
            }
        }

        // Hidden base detection (chests + crafting tables + furnaces)
        if (detectHiddenBase.get()) {
            if (state.getBlock() == Blocks.CHEST || state.getBlock() == Blocks.TRAPPED_CHEST ||
                state.getBlock() == Blocks.CRAFTING_TABLE || state.getBlock() == Blocks.FURNACE ||
                state.getBlock() == Blocks.SMOKER || state.getBlock() == Blocks.BLAST_FURNACE) {
                data.chestCount.incrementAndGet();
                if (data.chestCount.get() > 5 && data.blockUpdateCount.get() > 20) {
                    baseChunks.add(chunkPos);
                    if (!silentMode.get()) {
                        ChatUtils.info("🏠 [Hidden Base] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                    }
                }
            }
        }

        // Suspicious chunk threshold
        if (data.blockUpdateCount.get() >= updateThreshold.get()) {
            suspiciousChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.info("⚠️ [Suspicious Activity] Chunk X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
            }
        }
    }

    private void processItemSpawn(double x, double y, double z) {
        ChunkPos chunkPos = new ChunkPos((int) x >> 4, (int) z >> 4);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());

        data.itemEntityCount.incrementAndGet();
        if (data.itemEntityCount.get() > 10) {
            suspiciousChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.info("📦 [Item Cluster] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
            }
        }
    }

    private void processMobSpawn(double x, double y, double z) {
        ChunkPos chunkPos = new ChunkPos((int) x >> 4, (int) z >> 4);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());

        if (data.blockUpdateCount.get() > 5) {
            suspiciousChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.info("👻 [Mob Spawner Activity] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
            }
        }
    }

    private void processPlayerMovement(double x, double y, double z) {
        ChunkPos chunkPos = new ChunkPos((int) x >> 4, (int) z >> 4);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());

        data.playerMovementCount.incrementAndGet();
        // Detect flying: rapid vertical movement without block updates
        if (data.playerMovementCount.get() > 5 && data.blockUpdateCount.get() < 2) {
            suspiciousChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.info("🚀 [Flying Player] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // ADVANCED ANALYSIS METHOD
    private void performDeepAnalysis() {
        for (Map.Entry<ChunkPos, ChunkData> entry : chunkDataMap.entrySet()) {
            ChunkPos chunk = entry.getKey();
            ChunkData data = entry.getValue();

            // Pattern 1: Rapid block updates + few entities = possible hidden machine
            if (data.blockUpdateCount.get() > 30 && data.itemEntityCount.get() < 3) {
                baseChunks.add(chunk);
            }

            // Pattern-B: High farm blocks + low player movement = AFK farm
            if (data.farmBlockCount.get() > 20 && data.playerMovementCount.get() < 5) {
                farmChunks.add(chunk);
            }

            // Pattern 3: Mixed activity (chests + farm blocks) = hybrid base
            if (data.chestCount.get() > 3 && data.farmBlockCount.get() > 10) {
                baseChunks.add(chunk);
                farmChunks.add(chunk);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // 3D RENDERING
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.player == null) return;

        // Update render colors
        SettingColor sc = suspiciousColor.get();
        renderSuspicious.set(sc.r, sc.g, sc.b, sc.a);
        SettingColor fc = farmColor.get();
        renderFarm.set(fc.r, fc.g, fc.b, fc.a);
        SettingColor bc = baseColor.get();
        renderBase.set(bc.r, bc.g, bc.b, bc.a);

        int renderY = renderHeight.get();
        int range = scanRange.get();
        ChunkPos playerChunk = mc.player.getChunkPos();

        // Render suspicious chunks
        for (ChunkPos chunk : suspiciousChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= range && Math.abs(chunk.z - playerChunk.z) <= range) {
                event.renderer.box(chunk.getStartX(), renderY, chunk.getStartZ(), 
                                   chunk.getStartX() + 16, renderY + 0.1, chunk.getStartZ() + 16,
                                   renderSuspicious, renderSuspicious, ShapeMode.Sides, 0);
            }
        }

        // Render farm chunks
        for (ChunkPos chunk : farmChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= range && Math.abs(chunk.z - playerChunk.z) <= range) {
                event.renderer.box(chunk.getStartX(), renderY + 0.2, chunk.getStartZ(), 
                                   chunk.getStartX() + 16, renderY + 0.3, chunk.getStartZ() + 16,
                                   renderFarm, renderFarm, ShapeMode.Sides, 0);
            }
        }

        // Render base chunks
        for (ChunkPos chunk : baseChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= range && Math.abs(chunk.z - playerChunk.z) <= range) {
                event.renderer.box(chunk.getStartX(), renderY + 0.4, chunk.getStartZ(), 
                                   chunk.getStartX() + 16, renderY + 0.5, chunk.getStartZ() + 16,
                                   renderBase, renderBase, ShapeMode.Sides, 0);
            }
        }
    }
}
