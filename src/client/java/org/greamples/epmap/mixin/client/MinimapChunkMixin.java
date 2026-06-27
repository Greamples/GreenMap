package org.greamples.epmap.mixin.client;

import org.greamples.epmap.client.ChunkQueueManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.greamples.epmap.client.EpMapClient;
import xaero.common.minimap.region.MinimapChunk;

import java.nio.IntBuffer;

@Mixin(MinimapChunk.class)
public abstract class MinimapChunkMixin {

    @Shadow private int X;
    @Shadow private int Z;
    @Shadow private IntBuffer[] buffer;

    @Inject(method = "updateBuffers", at = @At("TAIL"))
    private void onRendered(int levelsToLoad, int[][] intArrayBuffer, CallbackInfo ci) {
        if (!EpMapClient.Companion.isTargetServer()) {
            return;
        }
        
        int chunkX = this.X;
        int chunkZ = this.Z;
        if (this.buffer != null && this.buffer.length > 0) {
            IntBuffer topLevelBuffer = this.buffer[0];
            if (topLevelBuffer != null) {
                int[] pixelData = new int[topLevelBuffer.capacity()];
                IntBuffer copy = topLevelBuffer.duplicate();
                copy.rewind();
                copy.get(pixelData);
                // pixelData содержит ARGB цвета чанка
                ChunkQueueManager.INSTANCE.addChunk(chunkX, chunkZ, pixelData);
            }
        }
    }
}