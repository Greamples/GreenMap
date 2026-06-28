package org.greamples.epmap.client

import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import kotlin.concurrent.timerTask

object ChunkQueueManager {
    // В корзине ключом теперь будет "ИЗМЕРЕНИЕ:X:Z" (например, "minecraft:overworld:10:-5")
    val basketChunks: ConcurrentHashMap<String, IntArray> = ConcurrentHashMap()

    // Принимаем измерение как простую строку (String) и не забываем про chunkX!
    fun addChunk(dimension: String, chunkX: Int, chunkZ: Int, pixelData: IntArray) {
        val key = "$dimension:$chunkX:$chunkZ"
        basketChunks[key] = pixelData
    }
    fun startQueueWorker() {
        val timer = Timer("EPMap-Queue-Worker", true)
        timer.scheduleAtFixedRate(timerTask {
            if (basketChunks.isEmpty()) return@timerTask
            val chunksToSend = HashMap(basketChunks)
            basketChunks.clear()
        }, 3000L, 3000L)
    }
}