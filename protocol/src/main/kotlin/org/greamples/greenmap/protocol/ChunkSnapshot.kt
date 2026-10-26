package org.greamples.greenmap.protocol

/**
 * Снимок одного чанка 16x16 в "семантическом" виде: вместо цветов пикселей храним
 * идентификаторы блоков/биомов и высоты. Цвета выбирает сервер карты по единой палитре,
 * поэтому ресурспаки игроков никак не влияют на итоговую карту.
 *
 * Колонки лежат в порядке index = z * 16 + x.
 */
class ChunkSnapshot(
    val dimension: String,
    val chunkX: Int,
    val chunkZ: Int,
    val scannedAt: Long,
    /** Палитра блоков (например, "minecraft:grass_block"). */
    val blockPalette: List<String>,
    /** Палитра биомов (например, "minecraft:plains"). */
    val biomePalette: List<String>,
    /** Индекс верхнего видимого блока в [blockPalette]. */
    val surfaceBlock: IntArray,
    /** Y верхнего видимого блока. [NO_DATA_Y], если колонка пустая (void / чанк не догружен). */
    val surfaceY: IntArray,
    /** Глубина жидкости над дном (0 = суши). */
    val waterDepth: IntArray,
    /** Индекс блока дна под жидкостью в [blockPalette] (совпадает с surfaceBlock, если воды нет). */
    val floorBlock: IntArray,
    /** Индекс биома в [biomePalette]. */
    val biome: IntArray,
    /** id ванильного MapColor (код игры, не текстуры) — запасной цвет для неизвестных серверу блоков. */
    val mapColor: IntArray,
) {
    init {
        for (column in listOf(surfaceBlock, surfaceY, waterDepth, floorBlock, biome, mapColor)) {
            require(column.size == COLUMNS) { "column array must have $COLUMNS entries" }
        }
    }

    fun blockAt(x: Int, z: Int): String = blockPalette[surfaceBlock[z * 16 + x]]

    /** Хэш только содержимого (без времени), чтобы не переотправлять неизменившиеся чанки. */
    fun contentHash(): Long {
        var h = 1125899906842597L
        fun mix(v: Int) { h = 31 * h + v }
        for (i in 0 until COLUMNS) {
            mix(blockPalette[surfaceBlock[i]].hashCode())
            mix(surfaceY[i]); mix(waterDepth[i])
            mix(blockPalette[floorBlock[i]].hashCode())
            mix(biomePalette[biome[i]].hashCode())
        }
        return h
    }

    companion object {
        const val COLUMNS = 256
        const val NO_DATA_Y = Short.MIN_VALUE.toInt()
    }
}

/** Пачка чанков от одного игрока — то, что уходит на сервер одним запросом. */
class SnapshotBatch(
    val modVersion: String,
    val minecraftVersion: String,
    val playerName: String,
    val playerUuid: String,
    val createdAt: Long,
    val chunks: List<ChunkSnapshot>,
)
