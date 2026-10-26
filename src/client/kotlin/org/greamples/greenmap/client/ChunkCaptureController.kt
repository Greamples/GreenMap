package org.greamples.greenmap.client

import net.minecraft.client.Minecraft
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.greamples.greenmap.ChunkScanner

/**
 * Решает, какие чанки и когда сканировать. Всё вызывается на клиентском потоке,
 * поэтому мир читается безопасно. Сканирование размазано по тикам ([GreenMapConfig.chunksPerTick]).
 */
class ChunkCaptureController(private val config: GreenMapConfig) {
    private val pending = LinkedHashSet<Long>()
    private var ticksUntilRescan = 0

    fun onChunkLoaded(pos: ChunkPos) {
        pending.add(pos.pack())
    }

    fun onChunkUnloaded(pos: ChunkPos) {
        pending.remove(pos.pack())
    }

    fun reset() {
        pending.clear()
        ticksUntilRescan = 0
    }

    fun tick(client: Minecraft) {
        val level = client.level ?: return
        val player = client.player ?: return
        if (!GreenMapClient.isTargetServer) return

        if (--ticksUntilRescan <= 0) {
            ticksUntilRescan = config.rescanIntervalSeconds.coerceAtLeast(5) * 20
            val center = player.chunkPosition()
            val radius = client.options.effectiveRenderDistance + 1
            for (dz in -radius..radius) for (dx in -radius..radius) {
                pending.add(ChunkPos.pack(center.x() + dx, center.z() + dz))
            }
        }

        val dimension = level.dimension().identifier().toString()
        var budget = config.chunksPerTick.coerceAtLeast(1)
        val iterator = pending.iterator()
        while (budget > 0 && iterator.hasNext()) {
            val packed = iterator.next()
            iterator.remove()
            val chunk = level.chunkSource.getChunk(ChunkPos.getX(packed), ChunkPos.getZ(packed), ChunkStatus.FULL, false)
                ?: continue // чанк уже выгружен или ещё не пришёл
            ChunkQueueManager.offer(ChunkScanner.scan(chunk, dimension))
            budget--
        }
    }
}
