package org.greamples.greenmap

import net.minecraft.core.BlockPos
import net.minecraft.core.QuartPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.levelgen.Heightmap
import net.minecraft.world.level.material.MapColor
import org.greamples.greenmap.protocol.ChunkSnapshot
import org.greamples.greenmap.protocol.ChunkSnapshotBuilder

/**
 * Читает чанк напрямую из мира (а не из рендера Xaero) и превращает его в [ChunkSnapshot].
 * Используются только данные игры: id блоков, высоты, биомы и MapColor (зашит в код блоков),
 * поэтому результат одинаков у всех игроков независимо от ресурспаков и шейдеров.
 *
 * Работает и на клиенте, и на сервере (dev-экспорт), т.к. LevelChunk — общий класс.
 */
object ChunkScanner {
    private const val MAX_WATER_DEPTH = 255

    fun scan(chunk: LevelChunk, dimension: String, now: Long = System.currentTimeMillis()): ChunkSnapshot {
        val pos = chunk.pos
        val builder = ChunkSnapshotBuilder(dimension, pos.x(), pos.z())
        val dimensionType = chunk.level.dimensionType()
        val minY = chunk.minY
        // В Незере крыша из бедрока: начинаем ниже логической высоты и пропускаем потолок.
        val ceilingY = if (dimensionType.hasCeiling()) minY + dimensionType.logicalHeight() - 1 else Int.MAX_VALUE
        val cursor = BlockPos.MutableBlockPos()

        for (z in 0 until 16) for (x in 0 until 16) {
            val worldX = pos.minBlockX + x
            val worldZ = pos.minBlockZ + z
            var y = minOf(chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z), ceilingY)

            if (dimensionType.hasCeiling()) {
                while (y > minY && !chunk.getBlockState(cursor.set(worldX, y, worldZ)).isAir) y--
            }

            // Как в ванильной карте: спускаемся до первого блока, у которого есть цвет.
            var state: BlockState = chunk.getBlockState(cursor.set(worldX, y, worldZ))
            while (y > minY && state.getMapColor(chunk, cursor) == MapColor.NONE) {
                y--
                state = chunk.getBlockState(cursor.set(worldX, y, worldZ))
            }
            if (y <= minY && state.getMapColor(chunk, cursor) == MapColor.NONE) {
                continue // пустая колонка: void или чанк ещё не догружен
            }

            val surfaceY = y
            val surface = blockId(state)
            val mapColor = state.getMapColor(chunk, cursor).id
            val biome = chunk.getNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(surfaceY), QuartPos.fromBlock(z))
                .unwrapKey().map { it.identifier().toString() }.orElse(ChunkSnapshotBuilder.UNKNOWN_BIOME)

            // Под жидкостью запоминаем глубину и блок дна — сервер сам решит, как рисовать воду.
            var depth = 0
            var floor = state
            if (!state.fluidState.isEmpty) {
                while (y > minY && depth < MAX_WATER_DEPTH && !floor.fluidState.isEmpty) {
                    y--
                    depth++
                    floor = chunk.getBlockState(cursor.set(worldX, y, worldZ))
                }
            }

            builder.setColumn(x, z, surface, surfaceY, depth, blockId(floor), biome, mapColor)
        }
        return builder.build(now)
    }

    private fun blockId(state: BlockState): String = BuiltInRegistries.BLOCK.getKey(state.block).toString()
}
