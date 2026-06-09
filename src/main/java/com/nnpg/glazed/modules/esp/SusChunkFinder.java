package com.nnpg.glazed.modules.esp;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettedContainer;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.network.PacketByteBuf;

import com.nnpg.glazed.GlazedAddon;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class SusChunkFinder extends Module {

    private final SettingGroup sgGeneral   = settings.createGroup("General");
    private final SettingGroup sgRender    = settings.createGroup("Render");
    private final SettingGroup sgHeuristic = settings.createGroup("Secondary Filters");

    // ==========================================
    // CÀI ĐẶT
    // ==========================================
    
    private final Setting<Integer> stashThreshold = sgGeneral.add(
        new IntSetting.Builder()
            .name("stash-packet-threshold")
            .description("Dung lượng gói tin mạng (bytes) để bị đánh dấu là Base/Stash. Giảm nếu muốn nhạy hơn.")
            .defaultValue(45000).min(10000).max(100000).sliderRange(20000, 80000)
            .build()
    );

    private final Setting<Integer> simulationDistance = sgGeneral.add(
        new IntSetting.Builder()
            .name("simulation-distance")
            .description("Bán kính quét (Chunks) quanh người chơi.")
            .defaultValue(6).min(1).max(12).sliderRange(1, 10)
            .build()
    );

    private final Setting<Integer> alpha = sgRender.add(
        new IntSetting.Builder()
            .name("alpha")
            .description("Độ trong suốt của thảm đỏ (Krypton Style).")
            .defaultValue(80).min(0).max(255).sliderRange(0, 255)
            .build()
    );

    private final Setting<Boolean> useSecondaryFilters = sgHeuristic.add(
        new BoolSetting.Builder()
            .name("use-block-heuristics")
            .description("Bật quét Amethyst/Lỗi xây dựng (Phụ trợ khi bay chậm).")
            .defaultValue(false).build()  
    );

    // ==========================================
    // HỆ THỐNG LÕI
    // ==========================================

    // Chỉ cần lưu tọa độ ChunkPos, không cần bận tâm trục Y nữa
    private final Map<Long, ChunkPos> renderCache = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(2);

    public SusChunkFinder() {
        super(GlazedAddon.CATEGORY, "sus-chunk-finder", "Dò Base chuẩn Krypton Client: Quét trọng lượng gói tin & Trải thảm đỏ ở Y=62.");
    }

    @Override
    public void onActivate() { renderCache.clear(); }

    @Override
    public void onDeactivate() { renderCache.clear(); }

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.world == null || mc.player == null) return;
        
        if (event.packet instanceof ChunkDataS2CPacket packet) {
            int cx = packet.getChunkX();
            int cz = packet.getChunkZ();

            if (!isWithinSimulationDistance(cx, cz)) return;

            ChunkPos pos = new ChunkPos(cx, cz);
            long key = pos.toLong();

            if (renderCache.containsKey(key)) return;

            // [VŨ KHÍ CHÍNH]: KRYPTON PACKET WEIGHT SNIPING
            try {
                PacketByteBuf buf = packet.getChunkData().getSectionsDataBuf();
                int packetSize = buf.readableBytes();
                
                // NẾU GÓI TIN NẶNG HƠN NGƯỠNG -> 100% CÓ RẤT NHIỀU RƯƠNG/ĐỒ
                if (packetSize > stashThreshold.get()) {
                    renderCache.put(key, pos);
                    return; 
                }
            } catch (Exception ignored) {}

            // [VŨ KHÍ PHỤ]: BLOCK HEURISTICS (Chỉ chạy nếu bật)
            if (useSecondaryFilters.get()) {
                final int finalCx = cx, finalCz = cz;
                
                mc.execute(() -> {
                    if (mc.world == null) return;
                    WorldChunk chunk = mc.world.getChunk(finalCx, finalCz);
                    
                    if (chunk != null && !chunk.isEmpty()) {
                        EXECUTOR.execute(() -> {
                            try {
                                if (isChunkSusByBlocks(chunk)) {
                                    renderCache.put(key, pos);
                                }
                            } catch (Exception ignored) {}
                        });
                    }
                });
            }
        }
    }

    // Tối giản hóa bộ lọc phụ
    private boolean isChunkSusByBlocks(WorldChunk chunk) {
        int functionalBlocks = 0;
        ChunkSection[] sections = chunk.getSectionArray();

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            PalettedContainer<BlockState> container = section.getBlockStateContainer();

            // Nếu quét thấy rương Ender, Bàn phù phép, đe... thì báo đỏ luôn
            if (container.hasAny(state -> {
                Block b = state.getBlock();
                return b == Blocks.ENDER_CHEST || b == Blocks.ENCHANTING_TABLE || b == Blocks.ANVIL || b == Blocks.BEACON;
            })) {
                return true; 
            }

            if (container.hasAny(state -> state.getBlock() == Blocks.CRAFTING_TABLE || state.getBlock() == Blocks.FURNACE)) {
                functionalBlocks++;
            }
        }
        
        return functionalBlocks >= 3;
    }

    // ==========================================
    // RENDER ĐỒ HỌA (KRYPTON STYLE)
    // ==========================================

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || renderCache.isEmpty()) return;

        int a = alpha.get();
        // Màu đỏ thuần Krypton (Đỏ rực, viền sắc nét)
        Color sideColor = new Color(255, 0, 0, a);
        Color lineColor = new Color(255, 0, 0, 255);

        pruneDistantChunks();

        // Cố định độ cao ở Y = 62 (Mặt nước / Tầm nhìn tối ưu khi bay)
        double targetY = 62.0;

        for (ChunkPos pos : renderCache.values()) {
            int bx = pos.getStartX();
            int bz = pos.getStartZ();

            // Vẽ thảm phẳng (Độ dày 0.1 block)
            event.renderer.box(
                bx,      targetY,       bz,
                bx + 16, targetY + 0.1, bz + 16,
                sideColor, lineColor,
                ShapeMode.Both, 0 
            );
        }
    }

    private void pruneDistantChunks() {
        if (mc.player == null) return;
        int playerCx = mc.player.getChunkPos().x;
        int playerCz = mc.player.getChunkPos().z;
        int dist = simulationDistance.get();

        renderCache.entrySet().removeIf(entry -> {
            ChunkPos cp = new ChunkPos(entry.getKey());
            return Math.abs(cp.x - playerCx) > dist || Math.abs(cp.z - playerCz) > dist;
        });
    }

    private boolean isWithinSimulationDistance(int cx, int cz) {
        if (mc.player == null) return false;
        int pcx = mc.player.getChunkPos().x;
        int pcz = mc.player.getChunkPos().z;
        int d = simulationDistance.get();
        return Math.abs(cx - pcx) <= d && Math.abs(cz - pcz) <= d;
    }
}
