package org.greamples.greenmap.renderer

import org.greamples.greenmap.protocol.ChunkSnapshot
import java.awt.image.BufferedImage

enum class RenderStyle {
    /** Средние цвета ванильных текстур + биомная окраска (похоже на Xaero, но одинаково для всех). */
    TEXTURED,
    /** Только ванильные MapColor — как предмет "карта" в игре. */
    VANILLA_MAP,
}

/** Рисует регион 32x32 чанка (512x512 блоков) в PNG. Один блок = один пиксель. */
class RegionRenderer(private val palette: CanonicalPalette, private val style: RenderStyle = RenderStyle.TEXTURED) {

    fun render(map: WorldMap, region: WorldMap.RegionKey): BufferedImage {
        val image = BufferedImage(REGION_BLOCKS, REGION_BLOCKS, BufferedImage.TYPE_INT_ARGB)
        val baseChunkX = region.x shl 5
        val baseChunkZ = region.z shl 5
        for (cz in 0 until 32) for (cx in 0 until 32) {
            val chunk = map.chunk(region.dimension, baseChunkX + cx, baseChunkZ + cz) ?: continue
            for (z in 0 until 16) for (x in 0 until 16) {
                val argb = pixel(map, chunk, x, z) ?: continue
                image.setRGB(cx * 16 + x, cz * 16 + z, argb)
            }
        }
        return image
    }

    private fun pixel(map: WorldMap, chunk: ChunkSnapshot, x: Int, z: Int): Int? {
        val i = z * 16 + x
        val y = chunk.surfaceY[i]
        if (y == ChunkSnapshot.NO_DATA_Y) return null
        val biome = chunk.biomePalette[chunk.biome[i]]
        val depth = chunk.waterDepth[i]

        if (style == RenderStyle.VANILLA_MAP) {
            val base = palette.mapColor(chunk.mapColor[i])
            // Как в ванильной карте: вода темнеет с глубиной, суша — по перепаду высот.
            val shade = if (depth > 0) {
                when { depth < 3 -> 1.0; depth < 7 -> 0.86; else -> 0.71 }
            } else {
                val diff = y - (northY(map, chunk, x, z) ?: y)
                when { diff > 0 -> 1.0; diff < 0 -> 0.71; else -> 0.86 }
            }
            return opaque(scale(base, shade))
        }

        val surface = palette.colorOf(chunk.blockPalette[chunk.surfaceBlock[i]], biome, chunk.mapColor[i]) ?: return null
        if (depth > 0) {
            val water = palette.colorOf("minecraft:water", biome, 12) ?: surface
            val floor = palette.colorOf(chunk.blockPalette[chunk.floorBlock[i]], biome, 0) ?: water
            val alpha = (0.55 + depth * 0.05).coerceAtMost(0.92)
            return opaque(blend(water, floor, alpha))
        }

        // Рельеф: сравниваем с соседом с севера, как ванильная карта, но мягче.
        val diff = (y - (northY(map, chunk, x, z) ?: y)).coerceIn(-6, 6)
        return opaque(scale(surface, 1.0 + diff * 0.045))
    }

    private fun northY(map: WorldMap, chunk: ChunkSnapshot, x: Int, z: Int): Int? {
        val y = if (z > 0) {
            chunk.surfaceY[(z - 1) * 16 + x]
        } else {
            val north = map.chunk(chunk.dimension, chunk.chunkX, chunk.chunkZ - 1) ?: return null
            north.surfaceY[15 * 16 + x]
        }
        return y.takeUnless { it == ChunkSnapshot.NO_DATA_Y }
    }

    companion object {
        const val REGION_BLOCKS = 512

        private fun opaque(rgb: Int) = rgb or (0xFF shl 24)

        fun scale(rgb: Int, factor: Double): Int {
            fun c(v: Int) = (v * factor).toInt().coerceIn(0, 255)
            return (c(rgb shr 16 and 0xFF) shl 16) or (c(rgb shr 8 and 0xFF) shl 8) or c(rgb and 0xFF)
        }

        fun blend(top: Int, bottom: Int, alpha: Double): Int {
            fun c(shift: Int) = ((top shr shift and 0xFF) * alpha + (bottom shr shift and 0xFF) * (1 - alpha)).toInt()
            return (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
    }
}
