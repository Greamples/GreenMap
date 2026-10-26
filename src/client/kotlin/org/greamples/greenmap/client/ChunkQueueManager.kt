package org.greamples.greenmap.client

import org.greamples.greenmap.protocol.ChunkSnapshot
import org.greamples.greenmap.protocol.GreenMapCodec
import org.greamples.greenmap.protocol.SnapshotBatch
import org.slf4j.LoggerFactory
import java.util.Timer
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.timerTask

object ChunkQueueManager {
    private val LOGGER = LoggerFactory.getLogger("GreenMap")
    private const val MAX_BATCH = 512
    private const val MAX_REMEMBERED_HASHES = 200_000
    private const val MAX_BACKOFF_MS = 5 * 60_000L

    // Ключ "ИЗМЕРЕНИЕ:X:Z" (например, "minecraft:overworld:10:-5"): новый снимок перезаписывает старый.
    val basketChunks: ConcurrentHashMap<String, ChunkSnapshot> = ConcurrentHashMap()

    // Хэши уже отправленного содержимого: неизменившиеся чанки повторно не шлём.
    private val sentHashes = ConcurrentHashMap<String, Long>()

    /** Пачка, которую не удалось доставить: повторяем её саму, не мешая копиться новым чанкам. */
    private class PendingBatch(val chunks: Map<String, ChunkSnapshot>, val body: ByteArray)

    // Поля ниже трогает только поток воркера.
    private var pending: PendingBatch? = null
    private var backoffMs = 0L
    private var nextAttemptAt = 0L

    // Взводится при входе на сервер: воркер выбросит пачку, собранную на прошлом сервере.
    @Volatile
    private var sessionChanged = false

    @Volatile
    var playerName: String = ""
    @Volatile
    var playerUuid: String = ""

    fun offer(snapshot: ChunkSnapshot) {
        val key = "${snapshot.dimension}:${snapshot.chunkX}:${snapshot.chunkZ}"
        if (sentHashes[key] == snapshot.contentHash()) return
        basketChunks[key] = snapshot
    }

    /** Новый сервер — новая карта: забываем, что уже отправляли. */
    fun resetSession() {
        sentHashes.clear()
        basketChunks.clear()
        sessionChanged = true
    }

    fun startQueueWorker(config: GreenMapConfig, uploader: ChunkUploader) {
        val period = config.uploadIntervalSeconds.coerceAtLeast(1) * 1000L
        val timer = Timer("GreenMap-Queue-Worker", true)
        // schedule (а не scheduleAtFixedRate), чтобы после медленного запроса не было серии догоняющих запусков.
        timer.schedule(timerTask {
            try {
                flush(uploader, period)
            } catch (e: Throwable) {
                LOGGER.warn("Ошибка при отправке чанков", e)
            }
        }, period, period)
    }

    private fun flush(uploader: ChunkUploader, period: Long) {
        if (sessionChanged) {
            sessionChanged = false
            pending = null
            backoffMs = 0
            nextAttemptAt = 0
        }
        if (System.currentTimeMillis() < nextAttemptAt) return
        val batch = pending ?: takeBatch(uploader) ?: return

        when (uploader.send(batch.body, batch.chunks.size)) {
            ChunkUploader.Result.DELIVERED -> {
                if (sentHashes.size > MAX_REMEMBERED_HASHES) sentHashes.clear()
                for ((key, snapshot) in batch.chunks) sentHashes[key] = snapshot.contentHash()
                pending = null
                backoffMs = 0
            }
            ChunkUploader.Result.REJECTED -> {
                pending = null
                backoffMs = 0
            }
            ChunkUploader.Result.RETRY -> {
                pending = batch
                backoffMs = (if (backoffMs == 0L) period else backoffMs * 2).coerceAtMost(MAX_BACKOFF_MS)
                nextAttemptAt = System.currentTimeMillis() + backoffMs
            }
        }
    }

    private fun takeBatch(uploader: ChunkUploader): PendingBatch? {
        if (basketChunks.isEmpty()) return null
        val taken = HashMap<String, ChunkSnapshot>()
        for (key in basketChunks.keys.take(MAX_BATCH)) {
            basketChunks.remove(key)?.let { taken[key] = it }
        }
        if (taken.isEmpty()) return null

        val batch = SnapshotBatch(
            modVersion = GreenMapClient.modVersion,
            minecraftVersion = GreenMapClient.minecraftVersion,
            playerName = playerName,
            playerUuid = playerUuid,
            createdAt = System.currentTimeMillis(),
            chunks = taken.values.toList(),
        )
        val body = try {
            GreenMapCodec.encode(batch)
        } catch (e: Exception) {
            for ((key, snapshot) in taken) basketChunks.putIfAbsent(key, snapshot)
            throw e
        }
        uploader.dump(body)
        return PendingBatch(taken, body)
    }
}
