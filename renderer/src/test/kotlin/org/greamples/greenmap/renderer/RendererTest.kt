package org.greamples.greenmap.renderer

import org.greamples.greenmap.protocol.ChunkSnapshotBuilder
import org.greamples.greenmap.protocol.GreenMapCodec
import org.greamples.greenmap.protocol.SnapshotBatch
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class RendererTest {
    private val clientJar: Path? = System.getProperty("greenmap.clientJar")?.let(Path::of)?.takeIf(Files::exists)

    private fun flatChunk(x: Int, z: Int, block: String, scannedAt: Long, y: Int = 64) =
        ChunkSnapshotBuilder("minecraft:overworld", x, z).apply {
            for (cz in 0 until 16) for (cx in 0 until 16) setColumn(cx, cz, block, y, 0, block, "minecraft:plains", 1)
        }.build(scannedAt)

    @Test
    fun newestSnapshotWins() {
        val map = WorldMap()
        map.add(flatChunk(0, 0, "minecraft:stone", scannedAt = 200))
        map.add(flatChunk(0, 0, "minecraft:dirt", scannedAt = 100)) // старый снимок другого игрока
        assertEquals("minecraft:stone", map.chunk("minecraft:overworld", 0, 0)!!.blockAt(0, 0))
        assertEquals(setOf(WorldMap.RegionKey("minecraft:overworld", 0, 0)), map.regions())
    }

    @Test
    fun negativeChunksLandInNegativeRegions() {
        val map = WorldMap()
        map.add(flatChunk(-1, -33, "minecraft:stone", 1))
        assertEquals(setOf(WorldMap.RegionKey("minecraft:overworld", -1, -2)), map.regions())
    }

    @Test
    fun vanillaPaletteIsCanonical() {
        val jar = clientJar ?: return // jar Minecraft не найден — пропускаем проверку палитры
        val palette = CanonicalPalette.load(jar)
        assertTrue(palette.blockCount > 800, "palette has ${palette.blockCount} blocks")

        // Трава окрашивается биомом: в пустыне и джунглях цвет разный.
        val plains = assertNotNull(palette.colorOf("minecraft:grass_block", "minecraft:plains", 1))
        val desert = assertNotNull(palette.colorOf("minecraft:grass_block", "minecraft:desert", 1))
        assertNotEquals(plains, desert)
        val g = plains shr 8 and 0xFF
        assertTrue(g > (plains shr 16 and 0xFF) && g > (plains and 0xFF), "grass must be green: ${plains.toString(16)}")

        // Модовый блок без текстуры в ванильном jar рисуется через MapColor.
        assertEquals(palette.mapColor(1), palette.colorOf("somemod:weird_block", "minecraft:plains", 1))

        // Пачка -> декодер -> рендер: одинаковые данные дают пиксель в пиксель одинаковую картинку.
        val bytes = GreenMapCodec.encode(SnapshotBatch("t", "t", "a", "a", 0, listOf(flatChunk(0, 0, "minecraft:grass_block", 1))))
        val map = WorldMap().apply { add(GreenMapCodec.decode(bytes)) }
        val image = RegionRenderer(palette).render(map, WorldMap.RegionKey("minecraft:overworld", 0, 0))
        assertEquals(plains or (0xFF shl 24), image.getRGB(5, 5))
        assertEquals(0, image.getRGB(100, 100)) // нет данных — прозрачно
    }
}
