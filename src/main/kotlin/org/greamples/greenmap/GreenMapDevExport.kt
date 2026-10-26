package org.greamples.greenmap

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents
import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
import org.greamples.greenmap.protocol.ChunkSnapshot
import org.greamples.greenmap.protocol.GreenMapCodec
import org.greamples.greenmap.protocol.SnapshotBatch
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random

/**
 * Инструмент разработчика: на выделенном сервере генерирует один регион (32x32 чанка), прогоняет
 * его через тот же [ChunkScanner], что и клиент, и сохраняет пачку *.gmap — ровно то, что мод отправил бы.
 * Включается только системным свойством `greenmap.export.out`, в обычной игре ничего не делает.
 *
 * Запуск: `./gradlew runExportRegion` (см. build.gradle.kts).
 */
class GreenMapDevExport : ModInitializer {
    private val logger = LoggerFactory.getLogger("GreenMap/Export")

    override fun onInitialize() {
        val out = System.getProperty("greenmap.export.out") ?: return
        var job: ExportJob? = null
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            job = try {
                ExportJob(server, Path.of(out))
            } catch (e: Exception) {
                logger.error("Экспорт региона не удался", e)
                server.halt(false)
                null
            }
        }
        // Генерируем по одной строке чанков за тик, чтобы сервер успевал разгребать свои задачи.
        ServerTickEvents.END_SERVER_TICK.register { server ->
            val current = job ?: return@register
            try {
                if (current.step()) {
                    current.save()
                    job = null
                    server.halt(false)
                }
            } catch (e: Exception) {
                logger.error("Экспорт региона не удался", e)
                job = null
                server.halt(false)
            }
        }
    }

    private inner class ExportJob(server: MinecraftServer, private val outDir: Path) {
        private val dimensionId = System.getProperty("greenmap.export.dimension", "minecraft:overworld")
        private val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionId)))
            ?: error("Нет измерения $dimensionId")
        private val region = parseRegion(System.getProperty("greenmap.export.region", "random"))
        private val min = ChunkPos.minFromRegion(region.first, region.second)
        private val chunks = ArrayList<ChunkSnapshot>(ChunkPos.REGION_SIZE * ChunkPos.REGION_SIZE)
        private var row = 0

        init {
            logger.info("Генерирую и сканирую регион r.{}.{} в {}", region.first, region.second, dimensionId)
        }

        /** @return true, когда весь регион отсканирован. */
        fun step(): Boolean {
            for (dx in 0 until ChunkPos.REGION_SIZE) {
                val chunk = level.chunkSource.getChunk(min.x() + dx, min.z() + row, ChunkStatus.FULL, true) as LevelChunk
                chunks += ChunkScanner.scan(chunk, dimensionId)
            }
            return ++row == ChunkPos.REGION_SIZE
        }

        fun save() {
            val batch = SnapshotBatch(
                modVersion = "dev-export",
                minecraftVersion = SharedConstants.getCurrentVersion().name(),
                playerName = "dev-export",
                playerUuid = "00000000-0000-0000-0000-000000000000",
                createdAt = System.currentTimeMillis(),
                chunks = chunks,
            )
            Files.createDirectories(outDir)
            val file = outDir.resolve("r.${region.first}.${region.second}.gmap")
            val bytes = GreenMapCodec.encode(batch)
            Files.write(file, bytes)
            logger.info("Сохранено {} чанков ({} байт) в {}", chunks.size, bytes.size, file.toAbsolutePath())
        }
    }

    private fun parseRegion(value: String): Pair<Int, Int> {
        if (value == "random") return Random.nextInt(-8, 8) to Random.nextInt(-8, 8)
        val parts = value.split(',').map { it.trim().toInt() }
        require(parts.size == 2) { "greenmap.export.region должен быть 'x,z' или 'random'" }
        return parts[0] to parts[1]
    }
}
