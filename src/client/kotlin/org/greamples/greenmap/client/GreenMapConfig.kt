package org.greamples.greenmap.client

import com.google.gson.GsonBuilder
import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Files

/** Настройки из config/greenmap.json. Файл создаётся с значениями по умолчанию при первом запуске. */
class GreenMapConfig {
    var enabled: Boolean = true
    /** Сбор работает только на этом домене и его поддоменах (пусто = на любом сервере и в одиночной игре). */
    var serverFilter: String = "epserv.ru"
    /** URL, куда POST-ом уходят пачки чанков. Пусто = отправка выключена. */
    var endpoint: String = ""
    /** Необязательный токен, уходит в заголовке Authorization: Bearer. */
    var token: String = ""
    /** Если задано — каждая пачка дополнительно сохраняется сюда файлом *.gmap (для отладки). */
    var dumpDir: String = ""
    var uploadIntervalSeconds: Int = 3
    /** Как часто перепроверять уже загруженные чанки (ловим постройки/разрушения). */
    var rescanIntervalSeconds: Int = 30
    /** Сколько чанков сканировать за тик, чтобы не было фризов. */
    var chunksPerTick: Int = 8

    /** Gson может записать null даже в не-null поля Kotlin — подставляем значения по умолчанию. */
    @Suppress("SENSELESS_COMPARISON")
    private fun sanitized(): GreenMapConfig = apply {
        val defaults = GreenMapConfig()
        if (serverFilter == null) serverFilter = defaults.serverFilter
        if (endpoint == null) endpoint = defaults.endpoint
        if (token == null) token = defaults.token
        if (dumpDir == null) dumpDir = defaults.dumpDir
    }

    /** Сбор включён на этом сервере? address — "host" или "host:port", null в одиночной игре. */
    fun matchesServer(address: String?): Boolean {
        val filter = serverFilter.trim().lowercase()
        if (filter.isEmpty()) return true
        val host = address?.trim()?.lowercase()?.substringBefore(':')?.removeSuffix(".") ?: return false
        return host == filter || host.endsWith(".$filter")
    }

    companion object {
        private val LOGGER = LoggerFactory.getLogger("GreenMap")
        private val GSON = GsonBuilder().setPrettyPrinting().create()

        fun load(): GreenMapConfig {
            val path = FabricLoader.getInstance().configDir.resolve("greenmap.json")
            val config = try {
                if (Files.exists(path)) {
                    Files.newBufferedReader(path).use { GSON.fromJson(it, GreenMapConfig::class.java) }?.sanitized() ?: GreenMapConfig()
                } else {
                    GreenMapConfig()
                }
            } catch (e: Exception) {
                LOGGER.error("Не удалось прочитать {}, используются настройки по умолчанию", path, e)
                return GreenMapConfig()
            }
            try {
                // Перезаписываем, чтобы в файле появились новые поля.
                Files.createDirectories(path.parent)
                Files.newBufferedWriter(path).use { GSON.toJson(config, it) }
            } catch (e: Exception) {
                LOGGER.warn("Не удалось сохранить {}", path, e)
            }
            return config
        }
    }
}
