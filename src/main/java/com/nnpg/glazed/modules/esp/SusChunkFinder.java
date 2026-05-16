package your.addon.modules; // <- đổi thành package của addon bạn

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.ChunkDataEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Categories;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.entity.*;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.Chunk;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class SusChunkFinder extends Module {

    // ============================================================
    //                   SETTING GROUPS
    // ============================================================
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender  = settings.createGroup("Render");
    private final SettingGroup sgColors  = settings.createGroup("Colors");

    // ============================================================
    //                   GENERAL SETTINGS
    // ============================================================
    private final Setting<Integer> minimumBlocks = sgGeneral.add(new IntSetting.Builder()
        .name("minimum-blocks")
        .description("Ngưỡng tối thiểu storage block để đánh dấu chunk là khả nghi.")
        .defaultValue(15)
        .min(1).sliderRange(1, 100)
        .build()
    );

    private final Setting<Boolean> countChests = sgGeneral.add(new BoolSetting.Builder()
        .name("chests").description("Đếm Chest & Trapped Chest.").defaultValue(true).build());

    private final Setting<Boolean> countBarrels = sgGeneral.add(new BoolSetting.Builder()
        .name("barrels").description("Đếm Barrel.").defaultValue(true).build());

    private final Setting<Boolean> countShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("shulker-boxes").description("Đếm Shulker Box (mọi màu).").defaultValue(true).build());

    private final Setting<Boolean> countHoppers = sgGeneral.add(new BoolSetting.Builder()
        .name("hoppers").description("Đếm Hopper.").defaultValue(true).build());

    private final Setting<Boolean> countDroppersDispensers = sgGeneral.add(new BoolSetting.Builder()
        .name("droppers-dispensers").description("Đếm Dropper & Dispenser.").defaultValue(true).build());

    private final Setting<Boolean> notify = sgGeneral.add(new BoolSetting.Builder()
        .name("chat-notify").description("Thông báo trong chat khi tìm thấy stash.").defaultValue(true).build());

    // ============================================================
    //                   RENDER SETTINGS
    // ============================================================
    private final Setting<Boolean> renderChunkBox = sgRender.add(new BoolSetting.Builder()
        .name("chunk-box").description("Vẽ hộp bao quanh chunk khả nghi.").defaultValue(true).build());

    private final Setting<Boolean> renderBlockEsp = sgRender.add(new BoolSetting.Builder()
        .name("block-esp").description("Highlight từng storage block.").defaultValue(true).build());

    private final Setting<Boolean> renderTracer = sgRender.add(new BoolSetting.Builder()
        .name("tracer").description("Vẽ tracer từ camera đến tâm cụm storage.").defaultValue(true).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Lines).build());

    private final Setting<Integer> maxRenderDistance = sgRender.add(new IntSetting.Builder()
        .name("max-render-distance")
        .description("Khoảng cách tối đa (block) để render chunk khả nghi.")
        .defaultValue(512).min(32).sliderRange(32, 2048).build()
    );

    // ============================================================
    //                   COLOR SETTINGS
    // ============================================================
    private final Setting<SettingColor> chunkSideColor = sgColors.add(new ColorSetting.Builder()
        .name("chunk-side-color").defaultValue(new SettingColor(255, 0, 0, 30)).build());

    private final Setting<SettingColor> chunkLineColor = sgColors.add(new ColorSetting.Builder()
        .name("chunk-line-color").defaultValue(new SettingColor(255, 0, 0, 200)).build());

    private final Setting<SettingColor> blockSideColor = sgColors.add(new ColorSetting.Builder()
        .name("block-side-color").defaultValue(new SettingColor(255, 165, 0, 40)).build());

    private final Setting<SettingColor> blockLineColor = sgColors.add(new ColorSetting.Builder()
        .name("block-line-color").defaultValue(new SettingColor(255, 165, 0, 255)).build());

    private final Setting<SettingColor> tracerColor = sgColors.add(new ColorSetting.Builder()
        .name("tracer-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());

    // ============================================================
    //                   INTERNAL STATE
    // ============================================================
    /** Lưu các chunk khả nghi đã phát hiện, key = ChunkPos. */
    private final Map<ChunkPos, SusChunk> susChunks = new ConcurrentHashMap<>();

    /** Đại diện cho một chunk khả nghi: chứa vị trí từng block và tâm cụm. */
    private static class SusChunk {
        final List<BlockPos> blocks = new ArrayList<>();
        Vec3d center = Vec3d.ZERO;
        int count = 0;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
    }

    public SusChunkFinder() {
        super(Categories.Misc, "sus-chunk-finder",
            "Phát hiện căn cứ ẩn bằng cách đếm storage blocks trong chunk.");
    }

    @Override
    public void onDeactivate() {
        susChunks.clear();
    }

    // ============================================================
    //                   CHUNK SCAN LOGIC
    // ============================================================
    @EventHandler
    private void onChunkData(ChunkDataEvent event) {
        Chunk chunk = event.chunk();
        if (chunk == null) return;

        ChunkPos pos = chunk.getPos();
        SusChunk sus = new SusChunk();

        // Duyệt qua tất cả block entities trong chunk
        for (Map.Entry<BlockPos, BlockEntity> entry : chunk.getBlockEntities().entrySet()) {
            BlockEntity be = entry.getValue();
            if (be == null) continue;
            if (!isStorageBlock(be)) continue;

            BlockPos bp = entry.getKey();
            sus.blocks.add(bp);
            sus.count++;
            if (bp.getY() < sus.minY) sus.minY = bp.getY();
            if (bp.getY() > sus.maxY) sus.maxY = bp.getY();
        }

        // Nếu vượt ngưỡng -> đánh dấu khả nghi
        if (sus.count >= minimumBlocks.get()) {
            // Tính tâm cụm (trung bình cộng tọa độ) để dùng cho tracer
            double sx = 0, sy = 0, sz = 0;
            for (BlockPos bp : sus.blocks) {
                sx += bp.getX() + 0.5;
                sy += bp.getY() + 0.5;
                sz += bp.getZ() + 0.5;
            }
            sus.center = new Vec3d(sx / sus.count, sy / sus.count, sz / sus.count);

            // Chống spam: chỉ thông báo nếu là chunk mới (chưa từng phát hiện)
            boolean isNew = !susChunks.containsKey(pos);
            susChunks.put(pos, sus);

            if (isNew && notify.get()) {
                ChatUtils.sendMsg(title.hashCode(),
                    String.format("(highlight)[SusChunkFinder](default) Found a stash in chunk (highlight)[%d, %d](default) — (highlight)%d(default) storage blocks.",
                        pos.x, pos.z, sus.count));
            }
        }
    }

    /** Kiểm tra block entity có phải storage block đang được bật trong settings không. */
    private boolean isStorageBlock(BlockEntity be) {
        if (be instanceof ChestBlockEntity || be instanceof TrappedChestBlockEntity) return countChests.get();
        if (be instanceof BarrelBlockEntity)        return countBarrels.get();
        if (be instanceof ShulkerBoxBlockEntity)    return countShulkers.get();
        if (be instanceof HopperBlockEntity)        return countHoppers.get();
        if (be instanceof DispenserBlockEntity)     return countDroppersDispensers.get(); // DropperBE extends DispenserBE
        return false;
    }

    // ============================================================
    //                   RENDERING
    // ============================================================
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || susChunks.isEmpty()) return;

        Vec3d cam = mc.gameRenderer.getCamera().getPos();
        double maxDistSq = (double) maxRenderDistance.get() * maxRenderDistance.get();

        for (Map.Entry<ChunkPos, SusChunk> entry : susChunks.entrySet()) {
            ChunkPos cp = entry.getKey();
            SusChunk sus = entry.getValue();

            // Bỏ qua chunk quá xa để tránh ngốn hiệu năng
            if (sus.center.squaredDistanceTo(cam) > maxDistSq) continue;

            // --- 1) Chunk Outline: hộp đỏ bao quanh chunk từ minY -> maxY+1 ---
            if (renderChunkBox.get()) {
                int worldMinY = mc.world.getBottomY();
                int worldMaxY = mc.world.getTopYInclusive() + 1;

                // Mở rộng box theo trục Y dựa trên block thực tế, fallback về full chunk height
                int yMin = sus.minY != Integer.MAX_VALUE ? sus.minY : worldMinY;
                int yMax = sus.maxY != Integer.MIN_VALUE ? sus.maxY + 1 : worldMaxY;

                Box chunkBox = new Box(
                    cp.getStartX(),  yMin, cp.getStartZ(),
                    cp.getStartX() + 16, yMax, cp.getStartZ() + 16
                );
                event.renderer.box(chunkBox, chunkSideColor.get(), chunkLineColor.get(), shapeMode.get(), 0);
            }

            // --- 2) Block ESP: viền từng storage block ---
            if (renderBlockEsp.get()) {
                for (BlockPos bp : sus.blocks) {
                    Box bb = new Box(
                        bp.getX(), bp.getY(), bp.getZ(),
                        bp.getX() + 1, bp.getY() + 1, bp.getZ() + 1
                    );
                    event.renderer.box(bb, blockSideColor.get(), blockLineColor.get(), shapeMode.get(), 0);
                }
            }

            // --- 3) Tracer: line từ camera -> tâm cụm storage ---
            if (renderTracer.get()) {
                event.renderer.line(
                    cam.x, cam.y, cam.z,
                    sus.center.x, sus.center.y, sus.center.z,
                    tracerColor.get()
                );
            }
        }
    }
}
