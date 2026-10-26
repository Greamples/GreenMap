package org.greamples.greenmap

import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents
import net.minecraft.SharedConstants
import net.minecraft.core.registries.Registries
import net.minecraft.resources.Identifier
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.status.ChunkStatus
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
        ServerLifecycleEvents.SERVER_STARTED.register { server ->
            try {
                export(server, Path.of(out))
            } catch (e: Exception) {
                logger.error("Экспорт региона не удался", e)
            } finally {
                server.halt(false)
            }
        }
    }

    private fun export(server: MinecraftServer, outDir: Path) {
        val dimensionId = System.getProperty("greenmap.export.dimension", "minecraft:overworld")
        val level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimensionId)))
            ?: error("Нет измерения $dimensionId")
        val (regionX, regionZ) = parseRegion(System.getProperty("greenmap.export.region", "random"))
        val min = ChunkPos.minFromRegion(regionX, regionZ)
        logger.info("Генерирую и сканирую регион r.{}.{} в {}", regionX, regionZ, dimensionId)

        val chunks = ArrayList<org.greamples.greenmap.protocol.ChunkSnapshot>(ChunkPos.REGION_SIZE * ChunkPos.REGION_SIZE)
        for (dz in 0 until ChunkPos.REGION_SIZE) for (dx in 0 until ChunkPos.REGION_SIZE) {
            val chunk = level.chunkSource.getChunk(min.x() + dx, min.z() + dz, ChunkStatus.FULL, true) as LevelChunk
            chunks += ChunkScanner.scan(chunk, dimensionId)
        }

        val batch = SnapshotBatch(
            modVersion = "dev-export",
            minecraftVersion = SharedConstants.getCurrentVersion().name(),
            playerName = "dev-export",
            playerUuid = "00000000-0000-0000-0000-000000000000",
            createdAt = System.currentTimeMillis(),
            chunks = chunks,
        )
        Files.createDirectories(outDir)
        val file = outDir.resolve("r.$regionX.$regionZ.gmap")
        val bytes = GreenMapCodec.encode(batch)
        Files.write(file, bytes)
        logger.info("Сохранено {} чанков ({} байт) в {}", chunks.size, bytes.size, file.toAbsolutePath())
    }

    private fun parseRegion(value: String): Pair<Int, Int> {
        if (value == "random") return Random.nextInt(-8, 8) to Random.nextInt(-8, 8)
        val parts = value.split(',').map { it.trim().toInt() }
        require(parts.size == 2) { "greenmap.export.region должен быть 'x,z' или 'random'" }
        return parts[0] to parts[1]
    }
}
