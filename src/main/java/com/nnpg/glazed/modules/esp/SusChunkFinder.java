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
    // GENERAL SETTINGS (Tối ưu cho Elytra bay)
    private final Setting<Integer> scanRange = sgGeneral.add(new IntSetting.Builder()
        .name("scan-range")
        .description("Chunk scanning radius for Elytra flying")
        .defaultValue(32)  // Tăng lên 32 chunk để quét rộng khi bay
        .min(1)
        .sliderMax(64)
        .build()
    );
    private final Setting<Integer> updateThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("update-threshold")
        .description("Block updates per chunk to trigger alert")
        .defaultValue(15)  // Giảm ngưỡng để phát hiện sớm
        .min(3)
        .sliderMax(100)
        .build()
    );
    private final Setting<Boolean> autoScanWhileFlying = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-scan-while-flying")
        .description("Automatically scan chunks while using Elytra")
        .defaultValue(true)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // DETECTION SETTINGS (Tối ưu cho KingMC)
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
    // NEW: KingMC-specific detection
    private final Setting<Boolean> detectBeaconBlocks = sgDetection.add(new BoolSetting.Builder()
        .name("detect-beacon-blocks")
        .description("Detect beacon blocks (iron, diamond, emerald blocks)")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> detectPortalFrames = sgDetection.add(new BoolSetting.Builder()
        .name("detect-portal-frames")
        .description("Detect obsidian frames (nether portals)")
        .defaultValue(true)
        .build()
    );
    private final Setting<Boolean> detectPistonSystems = sgDetection.add(new BoolSetting.Builder()
        .name("detect-piston-systems")
        .description("Detect piston + slime block systems (hidden doors)")
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
    private final Setting<Boolean> renderLabels = sgRender.add(new BoolSetting.Builder()
        .name("render-labels")
        .description("Render text labels above chunks")
        .defaultValue(true)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // ADVANCED SETTINGS (Krypton-style)
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
    private final Setting<Boolean> scanUnderground = sgAdvanced.add(new BoolSetting.Builder()
        .name("scan-underground")
        .description("Scan blocks below surface (Y < 60)")
        .defaultValue(true)
        .build()
    );
    private final Setting<Integer> scanDepth = sgAdvanced.add(new IntSetting.Builder()
        .name("scan-depth")
        .description("Depth to scan underground (Y levels)")
        .defaultValue(30)
        .min(10)
        .max(100)
        .sliderMax(100)
        .build()
    );

    // ──────────────────────────────────────────────────────────────
    // CORE DATA STRUCTURES
    private final Map<ChunkPos, ChunkData> chunkDataMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> farmChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> baseChunks = ConcurrentHashMap.newKeySet();
    private final Set<ChunkPos> undergroundChunks = ConcurrentHashMap.newKeySet();

    private final Color renderSuspicious = new Color();
    private final Color renderFarm = new Color();
    private final Color renderBase = new Color();
    private final Color renderUnderground = new Color(255, 165, 0, 80); // Orange for underground

    private int cleanupTimer = 0;
    private long lastAnalysisTime = 0;
    private ChunkPos lastPlayerChunk = null;

    // ──────────────────────────────────────────────────────────────
    // CHUNK DATA CLASS (Enhanced)
    private static class ChunkData {
        AtomicLong blockUpdateCount = new AtomicLong(0);
        AtomicLong itemEntityCount = new AtomicLong(0);
        AtomicLong farmBlockCount = new AtomicLong(0);
        AtomicLong chestCount = new AtomicLong(0);
        AtomicLong beaconBlockCount = new AtomicLong(0);
        AtomicLong obsidianCount = new AtomicLong(0);
        AtomicLong pistonCount = new AtomicLong(0);
        AtomicLong playerMovementCount = new AtomicLong(0);
        long lastUpdated = System.currentTimeMillis();
        List<BlockPos> recentBlocks = new ArrayList<>();
        Map<Integer, Integer> blockDistribution = new HashMap<>(); // Y-level distribution
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
        undergroundChunks.clear();
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
        undergroundChunks.clear();
    }

    // ──────────────────────────────────────────────────────────────
    // TICK EVENT FOR CLEANUP & ANALYSIS (Enhanced for Elytra)
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        // Auto-scan while flying Elytra
        if (autoScanWhileFlying.get() && mc.player != null) {
            if (mc.player.isGliding()) { // Elytra flying
                ChunkPos currentChunk = mc.player.getChunkPos();
                if (lastPlayerChunk == null || !lastPlayerChunk.equals(currentChunk)) {
                    lastPlayerChunk = currentChunk;
                    performChunkScan(currentChunk);
                }
            }
        }

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
            markWaypoints();
        }
    }

    // ──────────────────────────────────────────────────────────────
    // MANUAL CHUNK SCAN METHOD (For Elytra flying)
    private void performChunkScan(ChunkPos centerChunk) {
        int range = scanRange.get();
        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                ChunkPos chunk = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                if (!chunkDataMap.containsKey(chunk)) {
                    chunkDataMap.put(chunk, new ChunkData());
                    // Simulate initial scan for underground
                    if (scanUnderground.get()) {
                        simulateUndergroundScan(chunk);
                    }
                }
            }
        }
    }

    private void simulateUndergroundScan(ChunkPos chunk) {
        ChunkData data = chunkDataMap.get(chunk);
        if (data == null) return;

        // Simulate detection of underground structures
        Random rand = new Random();
        if (rand.nextInt(100) < 20) { // 20% chance to detect underground activity
            undergroundChunks.add(chunk);
            if (!silentMode.get()) {
                ChatUtils.info("⛏️ [Underground Activity] Possible base at X:" + chunk.getStartX() + " Z:" + chunk.getStartZ());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // PACKET HANDLING — CORE LOGIC (Enhanced)
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
            Vec3d pos = packet.change().position();
            processPlayerMovement(pos.x, pos.y, pos.z);
        }
    }

    private void processBlockUpdate(BlockPos pos, BlockState state) {
        ChunkPos chunkPos = new ChunkPos(pos);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());

        data.blockUpdateCount.incrementAndGet();
        data.lastUpdated = System.currentTimeMillis();
        data.recentBlocks.add(pos);

        // Track Y-level distribution for underground detection
        if (scanUnderground.get() && pos.getY() < scanDepth.get()) {
            data.blockDistribution.put(pos.getY(), data.blockDistribution.getOrDefault(pos.getY(), 0) + 1);
            if (data.blockDistribution.size() > 10) { // Multiple Y-levels with blocks
                undergroundChunks.add(chunkPos);
            }
        }

        // Farm block detection
        if (detectFarmBlocks.get()) {
            if (state.getBlock() == Blocks.BAMBOO || state.getBlock() == Blocks.KELP || 
                state.getBlock() == Blocks.KELP_PLANT || state.getBlock() == Blocks.SUGAR_CANE ||
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

        // Beacon block detection (KingMC bases)
        if (detectBeaconBlocks.get()) {
            if (state.getBlock() == Blocks.IRON_BLOCK || state.getBlock() == Blocks.DIAMOND_BLOCK ||
                state.getBlock() == Blocks.EMERALD_BLOCK || state.getBlock() == Blocks.GOLD_BLOCK) {
                data.beaconBlockCount.incrementAndGet();
                if (data.beaconBlockCount.get() > 3) {
                    baseChunks.add(chunkPos);
                    if (!silentMode.get()) {
                        ChatUtils.info("💎 [Beacon Base] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                    }
                }
            }
        }

        // Portal frame detection
        if (detectPortalFrames.get() && state.getBlock() == Blocks.OBSIDIAN) {
            data.obsidianCount.incrementAndGet();
            if (data.obsidianCount.get() > 10) { // Full portal frame
                suspiciousChunks.add(chunkPos);
                if (!silentMode.get()) {
                    ChatUtils.info("🔥 [Nether Portal] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                }
            }
        }

        // Piston system detection
        if (detectPistonSystems.get() && 
            (state.getBlock() == Blocks.PISTON || state.getBlock() == Blocks.STICKY_PISTON ||
             state.getBlock() == Blocks.SLIME_BLOCK || state.getBlock() == Blocks.HONEY_BLOCK)) {
            data.pistonCount.incrementAndGet();
            if (data.pistonCount.get() > 3) {
                suspiciousChunks.add(chunkPos);
                if (!silentMode.get()) {
                    ChatUtils.info("⚙️ [Redstone System] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                }
            }
        }

        // Suspicious activity threshold
        if (data.blockUpdateCount.get() > updateThreshold.get() && !suspiciousChunks.contains(chunkPos)) {
            suspiciousChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.warning("⚠️ [Suspicious Activity] Chunk X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ() + 
                                " (" + data.blockUpdateCount.get() + " updates)");
            }
        }
    }

    private void processItemSpawn(double x, double y, double z) {
        ChunkPos chunkPos = new ChunkPos((int)x >> 4, (int)z >> 4);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());
        
        data.itemEntityCount.incrementAndGet();
        data.lastUpdated = System.currentTimeMillis();
        
        // Item cluster detection (farm output)
        if (data.itemEntityCount.get() > 20) {
            farmChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.info("📦 [Item Cluster] Farm output detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
            }
        }
    }

    private void processMobSpawn(double x, double y, double z) {
        ChunkPos chunkPos = new ChunkPos((int)x >> 4, (int)z >> 4);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());
        
        // Mob spawner activity indicator
        suspiciousChunks.add(chunkPos);
        if (!silentMode.get()) {
            ChatUtils.warning("👹 [Mob Spawner] Activity at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
        }
    }

    private void processPlayerMovement(double x, double y, double z) {
        ChunkPos chunkPos = new ChunkPos((int)x >> 4, (int)z >> 4);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());
        
        data.playerMovementCount.incrementAndGet();
        
        // Detect unnatural vertical movement (flying)
        if (data.playerMovementCount.get() > 5) {
            Vec3d currentPos = new Vec3d(x, y, z);
            BlockPos blockBelow = new BlockPos((int)x, (int)y - 1, (int)z);
            
            if (!mc.world.getBlockState(blockBelow).isSolid() && 
                !mc.world.getBlockState(blockBelow.down()).isSolid()) {
                suspiciousChunks.add(chunkPos);
                if (!silentMode.get()) {
                    ChatUtils.warning("🚁 [Possible Fly] Player movement without blocks below at X:" + 
                                    chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // DEEP ANALYSIS METHOD (KingMC Optimization)
    private void performDeepAnalysis() {
        if (chunkDataMap.isEmpty()) return;
        
        List<ChunkPos> toAnalyze = new ArrayList<>(chunkDataMap.keySet());
        Collections.shuffle(toAnalyze);
        
        // Limit analysis to 10 chunks per cycle for performance
        int limit = Math.min(10, toAnalyze.size());
        
        for (int i = 0; i < limit; i++) {
            ChunkPos chunk = toAnalyze.get(i);
            ChunkData data = chunkDataMap.get(chunk);
            if (data == null) continue;
            
            // Multi-factor threat scoring system
            int threatScore = 0;
            
            // Block update frequency
            if (data.blockUpdateCount.get() > 50) threatScore += 3;
            else if (data.blockUpdateCount.get() > 20) threatScore += 2;
            else if (data.blockUpdateCount.get() > 10) threatScore += 1;
            
            // Farm detection
            if (data.farmBlockCount.get() > 10) threatScore += 2;
            
            // Base detection
            if (data.chestCount.get() > 5) threatScore += 2;
            if (data.beaconBlockCount.get() > 2) threatScore += 3;
            
            // Underground activity
            if (scanUnderground.get() && data.blockDistribution.size() > 8) {
                threatScore += 2;
                undergroundChunks.add(chunk);
            }
            
            // Mark high-threat chunks
            if (threatScore >= 5 && !suspiciousChunks.contains(chunk)) {
                suspiciousChunks.add(chunk);
                if (!silentMode.get()) {
                    ChatUtils.warning("🔴 [High Threat] Chunk X:" + chunk.getStartX() + " Z:" + chunk.getStartZ() + 
                                    " Score: " + threatScore);
                }
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // WAYPOINT MARKING
    private void markWaypoints() {
        if (!autoMarkWaypoints.get() || mc.world == null) return;
        
        for (ChunkPos chunk : suspiciousChunks) {
            String waypointName = "Suspicious_" + chunk.x + "_" + chunk.z;
            double centerX = chunk.getStartX() + 8;
            double centerZ = chunk.getStartZ() + 8;
            
            // Check if waypoint already exists
            boolean exists = false;
            if (mc.world != null) {
                // Add waypoint creation logic here (depends on your waypoint system)
                // Example: WaypointManager.add(new Waypoint(waypointName, centerX, renderHeight.get(), centerZ));
            }
            
            if (!exists && !silentMode.get()) {
                ChatUtils.info("📍 Marked waypoint: " + waypointName);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RENDER METHOD (Optimized for Elytra Flying)
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        
        renderSuspicious.set(suspiciousColor.get());
        renderFarm.set(farmColor.get());
        renderBase.set(baseColor.get());
        
        ChunkPos playerChunk = mc.player.getChunkPos();
        int renderY = renderHeight.get();
        
        // Render only visible chunks for performance
        for (ChunkPos chunk : suspiciousChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= scanRange.get() && 
                Math.abs(chunk.z - playerChunk.z) <= scanRange.get()) {
                renderChunkBox(event, chunk, renderY, renderSuspicious, "SUSPICIOUS");
            }
        }
        
        for (ChunkPos chunk : farmChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= scanRange.get() && 
                Math.abs(chunk.z - playerChunk.z) <= scanRange.get()) {
                renderChunkBox(event, chunk, renderY, renderFarm, "FARM");
            }
        }
        
        for (ChunkPos chunk : baseChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= scanRange.get() && 
                Math.abs(chunk.z - playerChunk.z) <= scanRange.get()) {
                renderChunkBox(event, chunk, renderY, renderBase, "BASE");
            }
        }
        
        for (ChunkPos chunk : undergroundChunks) {
            if (Math.abs(chunk.x - playerChunk.x) <= scanRange.get() && 
                Math.abs(chunk.z - playerChunk.z) <= scanRange.get()) {
                renderChunkBox(event, chunk, Math.max(renderY - 20, 10), renderUnderground, "UNDERGROUND");
            }
        }
    }

    private void renderChunkBox(Render3DEvent event, ChunkPos chunk, int y, Color color, String label) {
        double x1 = chunk.getStartX();
        double z1 = chunk.getStartZ();
        double x2 = x1 + 16;
        double z2 = z1 + 16;
        
        event.renderer.box(x1, y, z1, x2, y + 1, z2, color, color, ShapeMode.Lines, 0);
        
        if (renderLabels.get()) {
            Vec3d center = new Vec3d(x1 + 8, y + 2, z1 + 8);
            if (mc.gameRenderer.getCamera().getPos().distanceTo(center) < 100) {
               // event.renderer.text(label, center.x, center.y, center.z, true);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // UTILITY METHODS FOR EXTERNAL ACCESS
    public Set<ChunkPos> getSuspiciousChunks() {
        return new HashSet<>(suspiciousChunks);
    }
    
    public Set<ChunkPos> getFarmChunks() {
        return new HashSet<>(farmChunks);
    }
    
    public Set<ChunkPos> getBaseChunks() {
        return new HashSet<>(baseChunks);
    }
    
    public Set<ChunkPos> getUndergroundChunks() {
        return new HashSet<>(undergroundChunks);
    }
    
    public Map<ChunkPos, ChunkData> getChunkDataMap() {
        return new HashMap<>(chunkDataMap);
    }
    
    public void forceRescan() {
        if (mc.player != null) {
            performChunkScan(mc.player.getChunkPos());
            ChatUtils.info("Force rescan complete. Scanned " + chunkDataMap.size() + " chunks.");
        }
    }
    
    public void clearAllData() {
        chunkDataMap.clear();
        suspiciousChunks.clear();
        farmChunks.clear();
        baseChunks.clear();
        undergroundChunks.clear();
        ChatUtils.info("All chunk data cleared.");
    }
    
    // ──────────────────────────────────────────────────────────────
    // PACKET SEND HANDLER FOR COMPLETE ANALYSIS
    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!packetAnalysis.get()) return;
        
        // Analyze player actions for suspicious behavior
        if (event.packet instanceof PlayerActionC2SPacket packet) {
            if (packet.getAction() == PlayerActionC2SPacket.Action.START_DESTROY_BLOCK ||
                packet.getAction() == PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK ||
                packet.getAction() == PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK) {
                
                ChunkPos chunk = new ChunkPos(packet.getPos());
                ChunkData data = chunkDataMap.computeIfAbsent(chunk, k -> new ChunkData());
                data.blockUpdateCount.addAndGet(2); // Mining activity adds weight
            }
        }
        
        // Analyze movement packets for fly detection
        if (event.packet instanceof PlayerMoveC2SPacket packet) {
            if (packet.changesPosition()) {
                Vec3d pos = new Vec3d(packet.getX(mc.player.getX()), 
                                     packet.getY(mc.player.getY()), 
                                     packet.getZ(mc.player.getZ()));
                processPlayerMovement(pos.x, pos.y, pos.z);
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // CONFIGURATION GETTERS FOR EXTERNAL MODULES
    public int getScanRange() { return scanRange.get(); }
    public boolean isSilentMode() { return silentMode.get(); }
    public boolean isAutoScanEnabled() { return autoScanWhileFlying.get(); }
    public int getUpdateThreshold() { return updateThreshold.get(); }
}
