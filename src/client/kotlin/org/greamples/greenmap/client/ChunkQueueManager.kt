package org.greamples.greenmap.client

import org.greamples.greenmap.protocol.ChunkSnapshot
import org.greamples.greenmap.protocol.SnapshotBatch
import org.slf4j.LoggerFactory
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.timerTask

object ChunkQueueManager {
    private val LOGGER = LoggerFactory.getLogger("GreenMap")
    private const val MAX_BATCH = 512
    private const val MAX_REMEMBERED_HASHES = 200_000

    // Ключ "ИЗМЕРЕНИЕ:X:Z" (например, "minecraft:overworld:10:-5"): новый снимок перезаписывает старый.
    val basketChunks: ConcurrentHashMap<String, ChunkSnapshot> = ConcurrentHashMap()

    // Хэши уже отправленного содержимого: неизменившиеся чанки повторно не шлём.
    private val sentHashes = ConcurrentHashMap<String, Long>()

    @Volatile
    var playerName: String = ""
    @Volatile
    var playerUuid: String = ""

    fun offer(snapshot: ChunkSnapshot) {
        val key = "${snapshot.dimension}:${snapshot.chunkX}:${snapshot.chunkZ}"
        if (sentHashes[key] == snapshot.contentHash()) return
        basketChunks[key] = snapshot
    }

    fun startQueueWorker(config: GreenMapConfig, uploader: ChunkUploader) {
        val period = config.uploadIntervalSeconds.coerceAtLeast(1) * 1000L
        val timer = Timer("GreenMap-Queue-Worker", true)
        timer.scheduleAtFixedRate(timerTask {
            try {
                flush(uploader)
            } catch (e: Exception) {
                LOGGER.warn("Ошибка при отправке чанков", e)
            }
        }, period, period)
    }

    private fun flush(uploader: ChunkUploader) {
        if (basketChunks.isEmpty()) return
        val taken = HashMap<String, ChunkSnapshot>()
        for (key in basketChunks.keys.take(MAX_BATCH)) {
            basketChunks.remove(key)?.let { taken[key] = it }
        }
        if (taken.isEmpty()) return

        val batch = SnapshotBatch(
            modVersion = GreenMapClient.modVersion,
            minecraftVersion = GreenMapClient.minecraftVersion,
            playerName = playerName,
            playerUuid = playerUuid,
            createdAt = System.currentTimeMillis(),
            chunks = taken.values.toList(),
        )
        if (uploader.send(batch)) {
            if (sentHashes.size > MAX_REMEMBERED_HASHES) sentHashes.clear()
            for ((key, snapshot) in taken) sentHashes[key] = snapshot.contentHash()
        } else {
            // Не получилось — вернём в корзину, если за это время не пришёл более свежий снимок.
            for ((key, snapshot) in taken) basketChunks.putIfAbsent(key, snapshot)
        }
    }
}
