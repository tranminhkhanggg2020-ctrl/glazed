package com.nnpg.glazed.mixins;

import com.nnpg.glazed.ChunkSyncExploit;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientWorld.class)
public abstract class MixinClientWorld {
    
    @Inject(method = "setChunk", at = @At("HEAD"), cancellable = true)
    private void onSetChunk(int chunkX, int chunkZ, WorldChunk chunk, CallbackInfo ci) {
        // Bypass chunk verification nếu được enable
        if (ChunkSyncExploit.getInstance().getStateManager()
                .isChunkBypassed(new ChunkPos(chunkX, chunkZ))) {
            ci.cancel(); // Skip normal chunk setting
        }
    }
}
