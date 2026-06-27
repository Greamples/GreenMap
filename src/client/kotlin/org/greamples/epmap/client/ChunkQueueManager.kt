package org.greamples.epmap.client

import java.util.concurrent.ConcurrentHashMap
import java.util.Timer
import kotlin.concurrent.timerTask

object ChunkQueueManager {
    val basketChunks: ConcurrentHashMap<String, IntArray> = ConcurrentHashMap()

    fun addChunk(chunkX: Int, chunkZ: Int, pixelData: IntArray) {
        val key = "$chunkX:$chunkZ"
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