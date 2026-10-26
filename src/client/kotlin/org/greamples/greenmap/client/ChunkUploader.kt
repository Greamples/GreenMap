package org.greamples.greenmap.client

import org.greamples.greenmap.protocol.GreenMapCodec
import org.greamples.greenmap.protocol.SnapshotBatch
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/** Отправляет пачки на сервер карты (HTTP POST) и/или складывает их в папку для отладки. */
class ChunkUploader(private val config: GreenMapConfig) {
    private val logger = LoggerFactory.getLogger("GreenMap")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    /** @return true, если пачку можно считать доставленной. */
    fun send(batch: SnapshotBatch): Boolean {
        val body = GreenMapCodec.encode(batch)
        dump(body)
        val endpoint = config.endpoint.trim()
        if (endpoint.isEmpty()) return true

        return try {
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", GreenMapCodec.CONTENT_TYPE)
                .header("User-Agent", "GreenMap/${GreenMapClient.modVersion}")
                .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            if (status !in 200..299) logger.warn("Сервер карты ответил {} на пачку из {} чанков", status, batch.chunks.size)
            status in 200..299
        } catch (e: Exception) {
            logger.warn("Не удалось отправить пачку на {}: {}", endpoint, e.toString())
            false
        }
    }

    private fun dump(body: ByteArray) {
        if (config.dumpDir.isBlank()) return
        try {
            val dir = Path.of(config.dumpDir)
            Files.createDirectories(dir)
            Files.write(dir.resolve("batch-${System.currentTimeMillis()}-${System.nanoTime() % 1000}.gmap"), body)
        } catch (e: Exception) {
            logger.warn("Не удалось сохранить пачку в {}", config.dumpDir, e)
        }
    }
}
