package net.glazed.exploit.chunksync;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import java.lang.reflect.Method;

public class ChunkSyncBypass {
    
    public static void bypassChunkVerification(ClientWorld world, int chunkX, int chunkZ) {
        try {
            // Access private chunk manager
            Field chunkManagerField = ClientWorld.class.getDeclaredField("chunkManager");
            chunkManagerField.setAccessible(true);
            Object chunkManager = chunkManagerField.get(world);
            
            // Bypass chunk loading queue
            Method loadChunkMethod = chunkManager.getClass().getDeclaredMethod(
                "loadChunk", int.class, int.class, ChunkStatus.class, boolean.class
            );
            loadChunkMethod.setAccessible(true);
            
            // Load chunk với status giả mạo
            loadChunkMethod.invoke(chunkManager, chunkX, chunkZ, 
                ChunkStatus.FULL, false);
            
            // Force chunk vào cache mà không verification
            forceChunkIntoCache(world, chunkX, chunkZ);
            
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    private static void forceChunkIntoCache(ClientWorld world, int chunkX, int chunkZ) {
        try {
            // Access chunk storage
            Field chunkStorageField = world.getClass().getDeclaredField("chunkStorage");
            chunkStorageField.setAccessible(true);
            Object chunkStorage = chunkStorageField.get(world);
            
            // Get chunk array
            Method getChunkMethod = chunkStorage.getClass().getDeclaredMethod(
                "getChunk", int.class, int.class
            );
            getChunkMethod.setAccessible(true);
            
            WorldChunk chunk = (WorldChunk) getChunkMethod.invoke(chunkStorage, chunkX, chunkZ);
            
            if (chunk == null) {
                // Create fake chunk
                chunk = createFakeChunk(world, chunkX, chunkZ);
                
                // Inject vào storage
                Method setChunkMethod = chunkStorage.getClass().getDeclaredMethod(
                    "setChunk", int.class, int.class, WorldChunk.class
                );
                setChunkMethod.setAccessible(true);
                setChunkMethod.invoke(chunkStorage, chunkX, chunkZ, chunk);
            }
            
            // Mark chunk as loaded without server verification
            chunk.setLoaded(true);
            
        } catch (Exception e) {
            ChunkSyncExploit.LOGGER.error("Failed to force chunk into cache: ", e);
        }
    }
    
    private static WorldChunk createFakeChunk(ClientWorld world, int chunkX, int chunkZ) {
        // Sử dụng reflection để tạo chunk instance
        try {
            Constructor<WorldChunk> constructor = WorldChunk.class.getDeclaredConstructor(
                World.class, ChunkPos.class, UpgradeData.class, ChunkTickScheduler.class,
                ChunkTickScheduler.class, long.class, ChunkSection[].class, 
                ChunkStatusChangeListener.class, BlendingData.class
            );
            constructor.setAccessible(true);
            
            // Tạo empty chunk sections
            ChunkSection[] sections = new ChunkSection[16];
            for (int i = 0; i < sections.length; i++) {
                sections[i] = new ChunkSection(0);
            }
            
            return constructor.newInstance(
                world, 
                new ChunkPos(chunkX, chunkZ),
                UpgradeData.NO_UPGRADE_DATA,
                new ChunkTickScheduler<>(),
                new ChunkTickScheduler<>(),
                0L,
                sections,
                null,
                null
            );
            
        } catch (Exception e) {
            return null;
        }
    }
}
