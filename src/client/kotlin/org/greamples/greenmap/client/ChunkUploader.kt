package org.greamples.greenmap.client

import org.greamples.greenmap.protocol.GreenMapCodec
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
    enum class Result {
        /** Пачка доставлена (или отправка выключена). */
        DELIVERED,
        /** Временная ошибка (сеть, 5xx, 408, 429) — повторить позже. */
        RETRY,
        /** Сервер отверг пачку (4xx) — повтор бессмысленен. */
        REJECTED,
    }

    private val logger = LoggerFactory.getLogger("GreenMap")
    private val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    init {
        val endpoint = config.endpoint.trim()
        if (config.token.isNotBlank() && endpoint.startsWith("http://") && !isLocal(endpoint)) {
            logger.warn("Токен GreenMap уходит на {} без шифрования — используйте https", endpoint)
        }
    }

    fun send(body: ByteArray, chunkCount: Int): Result {
        val endpoint = config.endpoint.trim()
        if (endpoint.isEmpty()) return Result.DELIVERED

        return try {
            val request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", GreenMapCodec.CONTENT_TYPE)
                .header("User-Agent", "GreenMap/${GreenMapClient.modVersion}")
                .apply { if (config.token.isNotBlank()) header("Authorization", "Bearer ${config.token}") }
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build()
            val status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode()
            when {
                status in 200..299 -> Result.DELIVERED
                status == 408 || status == 429 || status >= 500 -> {
                    logger.warn("Сервер карты временно недоступен ({}), повторим позже", status)
                    Result.RETRY
                }
                else -> {
                    logger.warn("Сервер карты отверг пачку из {} чанков: {}", chunkCount, status)
                    Result.REJECTED
                }
            }
        } catch (e: Exception) {
            logger.warn("Не удалось отправить пачку на {}: {}", endpoint, e.toString())
            Result.RETRY
        }
    }

    fun dump(body: ByteArray) {
        if (config.dumpDir.isBlank()) return
        try {
            val dir = Path.of(config.dumpDir)
            Files.createDirectories(dir)
            Files.write(dir.resolve("batch-${System.currentTimeMillis()}-${System.nanoTime() % 1000}.gmap"), body)
        } catch (e: Exception) {
            logger.warn("Не удалось сохранить пачку в {}", config.dumpDir, e)
        }
    }

    private fun isLocal(endpoint: String): Boolean {
        val host = runCatching { URI.create(endpoint).host }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host == "::1"
    }
}
