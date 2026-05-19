package net.glazed.exploit.chunksync;

import net.minecraft.network.packet.s2c.play.*;
import net.minecraft.util.math.ChunkPos;
import io.netty.channel.ChannelHandlerContext;
import java.lang.reflect.Field;

public class PacketInterceptHandler {
    private final ChunkStateManager stateManager;
    private boolean interceptEnabled = true;
    
    public PacketInterceptHandler(ChunkStateManager stateManager) {
        this.stateManager = stateManager;
    }
    
    public void onPacketReceived(Object packet, ChannelHandlerContext ctx) {
        if (!interceptEnabled) return;
        
        try {
            if (packet instanceof ChunkDataS2CPacket) {
                handleChunkDataPacket((ChunkDataS2CPacket) packet);
            } else if (packet instanceof ChunkDeltaUpdateS2CPacket) {
                handleChunkDeltaPacket((ChunkDeltaUpdateS2CPacket) packet);
            } else if (packet instanceof UnloadChunkS2CPacket) {
                handleUnloadChunkPacket((UnloadChunkS2CPacket) packet);
            }
        } catch (Exception e) {
            ChunkSyncExploit.LOGGER.error("Packet intercept error: ", e);
        }
    }
    
    private void handleChunkDataPacket(ChunkDataS2CPacket packet) {
        try {
            // Lấy chunk position từ packet
            Field chunkXField = packet.getClass().getDeclaredField("chunkX");
            Field chunkZField = packet.getClass().getDeclaredField("chunkZ");
            chunkXField.setAccessible(true);
            chunkZField.setAccessible(true);
            
            int chunkX = chunkXField.getInt(packet);
            int chunkZ = chunkZField.getInt(packet);
            ChunkPos pos = new ChunkPos(chunkX, chunkZ);
            
            // Lấy chunk data
            Field chunkDataField = packet.getClass().getDeclaredField("chunkData");
            chunkDataField.setAccessible(true);
            Object chunkData = chunkDataField.get(packet);
            
            // Modify packet data để bypass sync
            modifyChunkPacketData(packet, chunkData);
            
            // Cache modified data
            cacheModifiedChunkData(pos, packet);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private void modifyChunkPacketData(ChunkDataS2CPacket packet, Object chunkData) {
        try {
            // Access private fields để modify data
            Field sectionsField = chunkData.getClass().getDeclaredField("sections");
            sectionsField.setAccessible(true);
            Object[] sections = (Object[]) sectionsField.get(chunkData);
            
            // Inject exploit vào mỗi section
            for (Object section : sections) {
                if (section != null) {
                    injectExploitIntoSection(section);
                }
            }
            
            // Bypass chunk verification
            Field inhabitedTimeField = chunkData.getClass().getDeclaredField("inhabitedTime");
            inhabitedTimeField.setAccessible(true);
            inhabitedTimeField.setLong(chunkData, Long.MAX_VALUE); // Invalid value
            
        } catch (Exception e) {
            ChunkSyncExploit.LOGGER.error("Failed to modify chunk packet: ", e);
        }
    }
    
    private void injectExploitIntoSection(Object chunkSection) {
        try {
            // Access block state container
            Field containerField = chunkSection.getClass().getDeclaredField("container");
            containerField.setAccessible(true);
            Object container = containerField.get(chunkSection);
            
            // Modify block states để tạo desync
            if (container != null) {
                Field dataField = container.getClass().getDeclaredField("data");
                dataField.setAccessible(true);
                long[] data = (long[]) dataField.get(container);
                
                // Inject invalid block states
                for (int i = 0; i < Math.min(data.length, 100); i++) {
                    data[i] = 0x7FFFFFFFFFFFFFFFL; // Invalid block state
                }
            }
            
        } catch (Exception e) {
            // Silent fail
        }
    }
}
