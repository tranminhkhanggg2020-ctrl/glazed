package com.nnpg.glazed.modules;
 
// ── Glazed addon ──────────────────────────────────────────────────────────────
import com.nnpg.glazed.Glazed;
 
// ── Meteor Client — events ────────────────────────────────────────────────────
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
 
// ── Meteor Client — renderer ──────────────────────────────────────────────────
import meteordevelopment.meteorclient.renderer.ShapeMode;
 
// ── Meteor Client — settings ──────────────────────────────────────────────────
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
 
// ── Meteor Client — module base ───────────────────────────────────────────────
import meteordevelopment.meteorclient.systems.modules.Module;
 
// ── Meteor Client — color utilities ───────────────────────────────────────────
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
 
// ── Orbit event bus ───────────────────────────────────────────────────────────
import meteordevelopment.orbit.EventHandler;
 
// ── Minecraft — block registry ────────────────────────────────────────────────
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
 
// ── Minecraft — block-state properties ───────────────────────────────────────
import net.minecraft.state.property.Properties;
 
// ── Minecraft — world utilities ───────────────────────────────────────────────
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
 
// ── Minecraft — chunk types ───────────────────────────────────────────────────
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;
 
// ── Java standard library ─────────────────────────────────────────────────────
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
 
/**
 * <b>SusChunkFinder</b> — Glazed Meteor Client module for Minecraft 1.21.4 (Fabric).
 *
 * <p>Scans loaded chunks for "signs of human activity" by counting blocks that
 * only grow, accumulate, or change orientation when a player is nearby to trigger
 * random-ticks or has manually placed them. When the per-chunk count reaches the
 * configured {@code sensitivity} threshold, the chunk is flagged and a translucent
 * flat plane is painted over it in the world renderer.</p>
 *
 * <h3>Typical targets</h3>
 * <ul>
 *   <li>Kelp / bamboo farms (rapid vertical growth)</li>
 *   <li>Bee farms (honey accumulation in nests / hives)</li>
 *   <li>Cave-vine or regular-vine corridors (grow toward light/ground)</li>
 *   <li>Amethyst geode farms (cluster / bud growth requires chunk loading)</li>
 *   <li>Player-placed rotated Deepslate (axis ≠ Y — impossible in natural terrain)</li>
 * </ul>
 */
public class SusChunkFinder extends Module {
 
    // =========================================================================
    //  Setting Groups
    // =========================================================================
 
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgDetect  = settings.createGroup("Detection");
    private final SettingGroup sgRender  = settings.createGroup("Render");
 
    // =========================================================================
    //  General Settings
    // =========================================================================
 
    /**
     * Chunk radius (in chunks) scanned around the player each cycle.
     * A value of 4 covers a 9×9 grid (81 chunks) — sensible for most server
     * simulation distances.
     */
    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Chunk radius around the player to scan for suspicious activity.")
            .defaultValue(4)
            .min(1)
            .sliderMax(32)
            .build()
    );
 
    /**
     * Minimum number of suspicious blocks in a single chunk before it is
     * flagged. Lower values increase false-positive rate; higher values reduce
     * sensitivity to small farms.
     */
    private final Setting<Integer> sensitivity = sgGeneral.add(
        new IntSetting.Builder()
            .name("sensitivity")
            .description("Minimum sus-block count to flag a chunk as suspicious.")
            .defaultValue(3)
            .min(1)
            .sliderMax(100)
            .build()
    );
 
    // =========================================================================
    //  Render Settings
    // =========================================================================
 
    /** RGB colour used for the filled overlay plane. Alpha is controlled separately. */
    private final Setting<SettingColor> color = sgRender.add(
        new ColorSetting.Builder()
            .name("color")
            .description("Fill colour for the suspicious-chunk overlay plane.")
            .defaultValue(new SettingColor(255, 30, 30, 52))
            .build()
    );
 
    /**
     * Alpha (transparency) of the rendered plane, decoupled from the colour
     * picker so it can be adjusted on a simple 0–255 slider without opening
     * the colour wheel.
     */
    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Transparency of the rendered plane. 0 = invisible, 255 = fully opaque.")
            .defaultValue(52)
            .min(0)
            .sliderMax(255)
            .build()
    );
 
    // =========================================================================
    //  Detection Feature Toggles
    // =========================================================================
 
    /** Kelp (upright stalk) and Kelp Plant (the body block below the tip). */
    private final Setting<Boolean> detectKelp = sgDetect.add(
        new BoolSetting.Builder()
            .name("kelp")
            .description("Detect Kelp and Kelp Plant blocks.")
            .defaultValue(true)
            .build()
    );
 
    /** Cave Vines tip and Cave Vines Plant (the hanging body block). */
    private final Setting<Boolean> detectCaveVines = sgDetect.add(
        new BoolSetting.Builder()
            .name("cave-vines")
            .description("Detect Cave Vines and Cave Vines Plant blocks.")
            .defaultValue(true)
            .build()
    );
 
    /**
     * Regular climbing Vine (Blocks.VINE).
     * NOTE: The Minecraft registry key is {@code vine} (singular), not {@code vines}.
     */
    private final Setting<Boolean> detectVines = sgDetect.add(
        new BoolSetting.Builder()
            .name("vines")
            .description("Detect Vine blocks.")
            .defaultValue(true)
            .build()
    );
 
    /** All four amethyst growth stages: cluster and three bud sizes. */
    private final Setting<Boolean> detectAmethyst = sgDetect.add(
        new BoolSetting.Builder()
            .name("amethyst")
            .description("Detect Amethyst Cluster, Small Bud, Medium Bud, and Large Bud blocks.")
            .defaultValue(true)
            .build()
    );
 
    /** Bamboo stalk — grows very fast when chunk-loaded, common in XP farms. */
    private final Setting<Boolean> detectBamboo = sgDetect.add(
        new BoolSetting.Builder()
            .name("bamboo")
            .description("Detect Bamboo blocks.")
            .defaultValue(true)
            .build()
    );
 
    /** Bee Nests (natural + player-moved) and Beehives (player-crafted). */
    private final Setting<Boolean> detectBeeNest = sgDetect.add(
        new BoolSetting.Builder()
            .name("bee-nest")
            .description("Detect Bee Nest and Beehive blocks.")
            .defaultValue(true)
            .build()
    );
 
    /**
     * Deepslate blocks ({@link Blocks#DEEPSLATE}) whose {@code AXIS} property
     * is <em>not</em> {@link Direction.Axis#Y}.
     *
     * <p>Natural Deepslate generates exclusively with {@code AXIS=Y}. Any
     * Deepslate block oriented on the X or Z axis was deliberately placed (or
     * rotated) by a player — a reliable indicator of underground construction.</p>
     */
    private final Setting<Boolean> detectRotatedDeepslate = sgDetect.add(
        new BoolSetting.Builder()
            .name("rotated-deepslate")
            .description(
                "Detect Deepslate blocks with AXIS ≠ Y. "
                + "Natural deepslate only generates Y-aligned; off-axis placement is player-made.")
            .defaultValue(true)
            .build()
    );
 
    // =========================================================================
    //  Runtime State
    // =========================================================================
 
    /**
     * Thread-safe set of chunk positions that exceeded the sensitivity threshold.
     * <p>Written from the main thread (ChunkDataEvent) and read from the render
     * thread (Render3DEvent); using a {@link ConcurrentHashMap}-backed set avoids
     * ConcurrentModificationException without heavyweight synchronisation.</p>
     */
    private final Set<ChunkPos> suspiciousChunks = ConcurrentHashMap.newKeySet();
 
    /**
     * Reusable {@link Color} instance for per-frame render calls.
     * Avoids allocating a new object on every frame.
     */
    private final Color renderColor = new Color();
 
    // =========================================================================
    //  Constructor
    // =========================================================================
 
    public SusChunkFinder() {
        super(
            Glazed.CATEGORY,
            "sus-chunk-finder",
            "Flags loaded chunks containing growth/accumulation blocks that suggest a hidden base, farm, or AFK player."
        );
    }
 
    // =========================================================================
    //  Module Lifecycle
    // =========================================================================
 
    /**
     * On activation, clear stale state and immediately scan every chunk that is
     * already loaded within the configured simulation distance — otherwise the
     * user would see nothing until new chunk packets arrive.
     */
    @Override
    public void onActivate() {
        suspiciousChunks.clear();
        if (mc.world != null && mc.player != null) {
            fullRescan();
        }
    }
 
    /**
     * Clean up on deactivation so the overlay disappears immediately and
     * no stale data is held in memory.
     */
    @Override
    public void onDeactivate() {
        suspiciousChunks.clear();
    }
 
    // =========================================================================
    //  Event Handlers
    // =========================================================================
 
    /**
     * Primary chunk scanning trigger.
     *
     * <p>Fired by Meteor's event bus whenever the client receives a
     * {@code LevelChunkPacket} from the server. We cast the chunk to
     * {@link WorldChunk} (guaranteed on the client side) and hand it off to
     * the optimised scan routine.</p>
     *
     * @param event Meteor's {@link ChunkDataEvent}; {@code event.chunk} is the
     *              freshly loaded {@link net.minecraft.world.chunk.Chunk}.
     */
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        if (mc.player == null) return;
 
        // The client world only ever holds WorldChunk instances.
        // The cast is safe; the instanceof guard is defensive for API changes.
        if (!(event.chunk instanceof WorldChunk worldChunk)) return;
 
        ChunkPos pos = worldChunk.getPos();
 
        // Ignore chunks outside our configured simulation distance
        if (!isInRange(pos)) return;
 
        scanChunk(worldChunk, pos);
    }
 
    /**
     * Render handler — draws a flat, filled 2D plane over every suspicious chunk.
     *
     * <p>The plane is rendered as an extremely thin filled box (height = 0.05 m)
     * sitting at the player's current block-floor Y level. Using
     * {@link ShapeMode#Fills} suppresses all edge lines, satisfying the
     * "no outlines / no tracers" requirement.</p>
     *
     * @param event Meteor's {@link Render3DEvent}; provides the {@link
     *              meteordevelopment.meteorclient.renderer.Renderer3D} instance.
     */
    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (suspiciousChunks.isEmpty() || mc.player == null) return;
 
        // Build the effective fill colour: RGB from the colour picker, alpha
        // overridden by the dedicated alpha slider.
        SettingColor sc = color.get();
        renderColor.set(sc.r, sc.g, sc.b, alpha.get());
 
        // Place the plane at the player's current block floor (eye Y − 1 for feet).
        double planeY = Math.floor(mc.player.getY());
 
        for (ChunkPos cp : suspiciousChunks) {
            // Prune chunks the player has moved away from since they were flagged.
            if (!isInRange(cp)) continue;
 
            // World-space block coordinates for this chunk column
            double x1 = cp.getStartX();
            double z1 = cp.getStartZ();
            double x2 = cp.getStartX() + 16.0;
            double z2 = cp.getStartZ() + 16.0;
 
            /*
             * Render a filled flat box (0.05 blocks tall) as the 2D plane.
             * ShapeMode.Fills draws only the triangulated faces — no wire lines.
             * The lineColor argument is intentionally set equal to renderColor
             * because ShapeMode.Fills ignores it, but we avoid passing null.
             */
            event.renderer.box(
                x1, planeY,        z1,
                x2, planeY + 0.05, z2,
                renderColor,
                renderColor,    // ignored by ShapeMode.Fills
                ShapeMode.Fills,
                0               // excludeDir = 0: render all faces of the thin box
            );
        }
    }
 
    // =========================================================================
    //  Core Scan Routine
    // =========================================================================
 
    /**
     * Scans the given {@link WorldChunk} for suspicious blocks using an
     * <strong>optimised section-skipping loop</strong>.
     *
     * <h3>Performance rationale</h3>
     * <p>A Minecraft 1.21.4 overworld chunk spans Y = −64 to +320, split into
     * <b>24 {@link ChunkSection}s</b> of 16 × 16 × 16 = 4,096 blocks each
     * (total: 98,304 blocks). In a typical underground chunk, most sections
     * below the surface are nearly empty stone — but stone is <em>not</em>
     * suspicious and does not need to be examined.</p>
     *
     * <p>The key optimisation: <b>call {@link ChunkSection#isEmpty()} before
     * entering the inner triple-loop.</b> {@code isEmpty()} returns {@code true}
     * when the section's internal palette consists solely of air (i.e. the
     * entire 4,096-block section was never written to). Skipping an empty
     * section saves exactly 4,096 {@link BlockState} lookups — effectively zero
     * cost compared with examining them.</p>
     *
     * <p>On a typical survival world the cave system leaves many air-heavy
     * sections, and the sky above the surface is usually all-air too, so we
     * often skip 6–14 out of 24 sections outright. The early-exit on threshold
     * hit further caps worst-case work.</p>
     *
     * @param chunk the chunk to examine (never {@code null} before calling)
     * @param pos   the {@link ChunkPos} key used to update the suspicious set
     */
    private void scanChunk(WorldChunk chunk, ChunkPos pos) {
        if (chunk == null) return;
 
        int susCount          = 0;
        int targetSensitivity = sensitivity.get();
 
        // Retrieve the flat array of 16-block-tall sections.
        // Index 0 corresponds to the bottommost section of the world (Y = bottomY).
        ChunkSection[] sections = chunk.getSectionArray();
 
        // The absolute Y coordinate of the very first block row in sections[0].
        // For a normal overworld chunk this is −64.
        int worldBottomY = chunk.getBottomY();
 
        for (int sIdx = 0; sIdx < sections.length; sIdx++) {
            ChunkSection section = sections[sIdx];
 
            // ── OPTIMISATION: skip entirely empty (all-air) sections ──────────
            //
            // ChunkSection.isEmpty() checks whether the section's non-air block
            // count is zero. If so, there is nothing suspicious here — skip the
            // entire 4,096-block inner loop for free.
            if (section == null || section.isEmpty()) continue;
 
            // Absolute world Y of the bottom row of this section.
            // Kept here for clarity / future BlockPos construction if needed.
            // int sectionBaseY = worldBottomY + (sIdx << 4); // sIdx * 16
 
            // ── Inner loop: 16 × 16 × 16 block states ────────────────────────
            //
            // We use section-local coordinates (0–15 on each axis).
            // ChunkSection.getBlockState() is a direct palette lookup —
            // very fast compared with World.getBlockState().
            for (int lx = 0; lx < 16; lx++) {
                for (int ly = 0; ly < 16; ly++) {
                    for (int lz = 0; lz < 16; lz++) {
 
                        BlockState state = section.getBlockState(lx, ly, lz);
 
                        if (isSuspicious(state)) {
                            susCount++;
 
                            // ── Early exit on threshold hit ───────────────────
                            // Once we reach the sensitivity count there is no
                            // benefit in continuing — flag and return immediately.
                            if (susCount >= targetSensitivity) {
                                suspiciousChunks.add(pos);
                                return;
                            }
                        }
                    }
                }
            }
        }
 
        // Finished scanning — chunk did not reach the threshold.
        // Remove it in case it was previously flagged (e.g. after a settings change).
        suspiciousChunks.remove(pos);
    }
 
    /**
     * Returns {@code true} if the given {@link BlockState} matches any of the
     * configured suspicious block types.
     *
     * <p>An air fast-path is checked first because air is by far the most
     * common non-empty block state returned by non-empty sections (pockets,
     * caves, etc.).</p>
     *
     * <p><b>Rotated Deepslate logic:</b> {@link Blocks#DEEPSLATE} naturally
     * generates with {@code AXIS=Y} only. Any block where
     * {@code Properties.AXIS ≠ Direction.Axis.Y} was placed by a player, making
     * it a reliable indicator of underground construction regardless of depth.</p>
     *
     * @param state a non-null {@link BlockState} to evaluate
     * @return {@code true} if this block should increment the suspicious counter
     */
    private boolean isSuspicious(BlockState state) {
        // Fast-path: air accounts for the majority of blocks in non-empty sections
        if (state.isAir()) return false;
 
        net.minecraft.block.Block block = state.getBlock();
 
        // ── Kelp (vertical underwater growth, found in player kelp farms) ─────
        if (detectKelp.get()
                && (block == Blocks.KELP || block == Blocks.KELP_PLANT)) {
            return true;
        }
 
        // ── Cave Vines (hang from ceilings, grow downward when chunk-loaded) ──
        if (detectCaveVines.get()
                && (block == Blocks.CAVE_VINES || block == Blocks.CAVE_VINES_PLANT)) {
            return true;
        }
 
        // ── Regular Vines (climbing, spread when chunk-loaded) ────────────────
        // NOTE: The Minecraft registry name is Blocks.VINE (singular), not VINES.
        if (detectVines.get() && block == Blocks.VINE) {
            return true;
        }
 
        // ── Amethyst (all four growth stages, requires chunk loading) ─────────
        if (detectAmethyst.get()
                && (block == Blocks.AMETHYST_CLUSTER
                    || block == Blocks.SMALL_AMETHYST_BUD
                    || block == Blocks.MEDIUM_AMETHYST_BUD
                    || block == Blocks.LARGE_AMETHYST_BUD)) {
            return true;
        }
 
        // ── Bamboo (extremely fast grower near players, common in XP farms) ───
        if (detectBamboo.get() && block == Blocks.BAMBOO) {
            return true;
        }
 
        // ── Bee Nest / Beehive (honey fills only when bees are active nearby) ─
        if (detectBeeNest.get()
                && (block == Blocks.BEE_NEST || block == Blocks.BEEHIVE)) {
            return true;
        }
 
        // ── Rotated Deepslate (AXIS ≠ Y → definitely player-placed) ──────────
        //
        // We guard with state.contains(Properties.AXIS) to be defensive against
        // any future Deepslate variant that might lack the property, even though
        // Blocks.DEEPSLATE always has it in 1.21.4.
        if (detectRotatedDeepslate.get()
                && block == Blocks.DEEPSLATE
                && state.contains(Properties.AXIS)
                && state.get(Properties.AXIS) != Direction.Axis.Y) {
            return true;
        }
 
        return false;
    }
 
    // =========================================================================
    //  Helper Utilities
    // =========================================================================
 
    /**
     * Returns {@code true} if the given chunk position is within the configured
     * {@link #simulationDistance} of the player's current chunk.
     *
     * @param pos the chunk to test
     * @return {@code true} if it lies inside the square scan radius
     */
    private boolean isInRange(ChunkPos pos) {
        if (mc.player == null) return false;
        ChunkPos playerChunk = mc.player.getChunkPos();
        int dist = simulationDistance.get();
        return Math.abs(pos.x - playerChunk.x) <= dist
            && Math.abs(pos.z - playerChunk.z) <= dist;
    }
 
    /**
     * Iterates over all chunks within the simulation distance that are already
     * present in the client-side chunk cache and submits them for scanning.
     *
     * <p>Called on {@link #onActivate()} so that chunks loaded before the module
     * was enabled are not silently ignored.</p>
     *
     * <p>On the client, {@link net.minecraft.client.world.ClientWorld#getChunk(int, int)}
     * always returns a {@link WorldChunk} (the client never holds ProtoChunks).
     * We still guard with an {@code instanceof} check for robustness.</p>
     */
    private void fullRescan() {
        if (mc.world == null || mc.player == null) return;
 
        suspiciousChunks.clear();
 
        ChunkPos playerChunk = mc.player.getChunkPos();
        int dist = simulationDistance.get();
 
        for (int dx = -dist; dx <= dist; dx++) {
            for (int dz = -dist; dz <= dist; dz++) {
                ChunkPos cp = new ChunkPos(playerChunk.x + dx, playerChunk.z + dz);
 
                // Retrieve the chunk from the client chunk cache.
                // On a ClientWorld this is always a WorldChunk if the chunk is loaded.
                net.minecraft.world.chunk.Chunk raw = mc.world.getChunk(cp.x, cp.z);
                if (raw instanceof WorldChunk wc) {
                    scanChunk(wc, cp);
                }
            }
        }
    }
}
 
