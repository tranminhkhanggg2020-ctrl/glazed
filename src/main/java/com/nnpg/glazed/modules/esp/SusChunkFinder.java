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
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;

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
        .description("Chunk scanning radius for Elytra flying")
        .defaultValue(32)
        .min(1)
        .sliderMax(64)
        .build()
    );
    private final Setting<Integer> updateThreshold = sgGeneral.add(new IntSetting.Builder()
        .name("update-threshold")
        .description("Block updates per chunk to trigger alert")
        .defaultValue(15)
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
        .description("Render text labels above chunks (Temporarily disabled due to API changes)")
        .defaultValue(true)
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
    private final Color renderUnderground = new Color(255, 165, 0, 80);

    private int cleanupTimer = 0;
    private ChunkPos lastPlayerChunk = null;

    // Đã fix lỗi access modifier (public static) và dọn dẹp Memory Leak
    public static class ChunkData {
        AtomicLong blockUpdateCount = new AtomicLong(0);
        AtomicLong farmBlockCount = new AtomicLong(0);
        AtomicLong chestCount = new AtomicLong(0);
        AtomicLong beaconBlockCount = new AtomicLong(0);
        AtomicLong obsidianCount = new AtomicLong(0);
        AtomicLong pistonCount = new AtomicLong(0);
        long lastUpdated = System.currentTimeMillis();
        Map<Integer, Integer> blockDistribution = new HashMap<>(); 
    }

    // ──────────────────────────────────────────────────────────────
    // MODULE INIT
    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Optimized Chunk radar — detects AFK farms, hidden bases, suspicious activity");
    }

    @Override
    public void onActivate() {
        clearAllData();
        cleanupTimer = 0;
        ChatUtils.info("Sus Chunk Finder activated — scanning radius: " + scanRange.get() + " chunks");
    }

    @Override
    public void onDeactivate() {
        clearAllData();
    }

    // ──────────────────────────────────────────────────────────────
    // TICK EVENT FOR CLEANUP & SCANNING
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (!isActive()) return;

        if (autoScanWhileFlying.get() && mc.player != null) {
            // Đã fix hàm check bay Elytra cho bản 1.21.4 (isGliding)
            if (mc.player.isGliding()) { 
                ChunkPos currentChunk = mc.player.getChunkPos();
                if (lastPlayerChunk == null || !lastPlayerChunk.equals(currentChunk)) {
                    lastPlayerChunk = currentChunk;
                    performChunkScan(currentChunk);
                }
            }
        }

        cleanupTimer++;
        if (cleanupTimer >= 200) { 
            cleanupTimer = 0;
            long currentTime = System.currentTimeMillis();
            chunkDataMap.entrySet().removeIf(entry -> currentTime - entry.getValue().lastUpdated > 300000); 
        }
    }

    private void performChunkScan(ChunkPos centerChunk) {
        int range = scanRange.get();
        for (int x = -range; x <= range; x++) {
            for (int z = -range; z <= range; z++) {
                ChunkPos chunk = new ChunkPos(centerChunk.x + x, centerChunk.z + z);
                chunkDataMap.putIfAbsent(chunk, new ChunkData());
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // PACKET HANDLING — CORE LOGIC
    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket packet) {
            processBlockUpdate(packet.getPos(), packet.getState());
        } 
        else if (event.packet instanceof ChunkDeltaUpdateS2CPacket packet) {
            packet.visitUpdates(this::processBlockUpdate);
        }
    }

    private void processBlockUpdate(BlockPos pos, BlockState state) {
        ChunkPos chunkPos = new ChunkPos(pos);
        ChunkData data = chunkDataMap.computeIfAbsent(chunkPos, k -> new ChunkData());

        data.blockUpdateCount.incrementAndGet();
        data.lastUpdated = System.currentTimeMillis();

        if (scanUnderground.get() && pos.getY() < scanDepth.get()) {
            data.blockDistribution.put(pos.getY(), data.blockDistribution.getOrDefault(pos.getY(), 0) + 1);
            if (data.blockDistribution.size() > 10) { 
                undergroundChunks.add(chunkPos);
            }
        }

        if (detectFarmBlocks.get()) {
            // Đã fix lỗi sai tên Blocks.SUGAR_CANE
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

        if (detectPortalFrames.get() && state.getBlock() == Blocks.OBSIDIAN) {
            data.obsidianCount.incrementAndGet();
            if (data.obsidianCount.get() > 10) { 
                suspiciousChunks.add(chunkPos);
                if (!silentMode.get()) {
                    ChatUtils.info("🔥 [Nether Portal] Detected at X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ());
                }
            }
        }

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

        if (data.blockUpdateCount.get() > updateThreshold.get() && !suspiciousChunks.contains(chunkPos)) {
            suspiciousChunks.add(chunkPos);
            if (!silentMode.get()) {
                ChatUtils.warning("⚠️ [Suspicious Activity] Chunk X:" + chunkPos.getStartX() + " Z:" + chunkPos.getStartZ() + 
                                " (" + data.blockUpdateCount.get() + " updates)");
            }
        }
    }

    // ──────────────────────────────────────────────────────────────
    // RENDER METHOD 
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;
        
        renderSuspicious.set(suspiciousColor.get());
        renderFarm.set(farmColor.get());
        renderBase.set(baseColor.get());
        
        ChunkPos playerChunk = mc.player.getChunkPos();
        int renderY = renderHeight.get();
        
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
        
        event.renderer.box(x1, y, z1, x2, y + 1, z2, color, color, ShapeMode.Both, 0);
        
        // Đã tắt tính năng render text để tương thích với API Meteor bản mới
        // if (renderLabels.get()) {
        //     Vec3d center = new Vec3d(x1 + 8, y + 2, z1 + 8);
        //     if (mc.gameRenderer.getCamera().getPos().distanceTo(center) < 100) {
        //         // event.renderer.text(label, center.x, center.y, center.z, true);
        //     }
        // }
    }

    // ──────────────────────────────────────────────────────────────
    // UTILITY METHODS
    public Set<ChunkPos> getSuspiciousChunks() { return new HashSet<>(suspiciousChunks); }
    public Set<ChunkPos> getFarmChunks() { return new HashSet<>(farmChunks); }
    public Set<ChunkPos> getBaseChunks() { return new HashSet<>(baseChunks); }
    public Set<ChunkPos> getUndergroundChunks() { return new HashSet<>(undergroundChunks); }
    public Map<ChunkPos, ChunkData> getChunkDataMap() { return new HashMap<>(chunkDataMap); }
    
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
    }
    
    public int getScanRange() { return scanRange.get(); }
    public boolean isSilentMode() { return silentMode.get(); }
    public boolean isAutoScanEnabled() { return autoScanWhileFlying.get(); }
    public int getUpdateThreshold() { return updateThreshold.get(); }
}
