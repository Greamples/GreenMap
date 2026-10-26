package org.greamples.greenmap.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class GreenMapCodecTest {
    private fun sampleChunk(x: Int, z: Int, shift: Int = 0): ChunkSnapshot {
        val builder = ChunkSnapshotBuilder("minecraft:overworld", x, z)
        for (cz in 0 until 16) for (cx in 0 until 16) {
            val water = (cx + cz + shift) % 5 == 0
            builder.setColumn(
                cx, cz,
                surface = if (water) "minecraft:water" else "minecraft:grass_block",
                y = 60 + cx - cz + shift,
                depth = if (water) 3 else 0,
                floor = if (water) "minecraft:sand" else "minecraft:grass_block",
                biomeId = if (cz < 8) "minecraft:plains" else "minecraft:river",
                mapColorId = if (water) 12 else 1,
            )
        }
        return builder.build(scannedAt = 1234L)
    }

    @Test
    fun roundTrip() {
        val batch = SnapshotBatch("1.0", "26.1.2", "Steve", "uuid", 42L, listOf(sampleChunk(3, -7), sampleChunk(-1, 0, 2)))
        val decoded = GreenMapCodec.decode(GreenMapCodec.encode(batch))

        assertEquals("Steve", decoded.playerName)
        assertEquals(2, decoded.chunks.size)
        for ((a, b) in batch.chunks.zip(decoded.chunks)) {
            assertEquals(a.dimension, b.dimension)
            assertEquals(a.chunkX, b.chunkX)
            assertEquals(a.chunkZ, b.chunkZ)
            assertEquals(a.blockPalette, b.blockPalette)
            assertEquals(a.biomePalette, b.biomePalette)
            assertContentEquals(a.surfaceBlock, b.surfaceBlock)
            assertContentEquals(a.surfaceY, b.surfaceY)
            assertContentEquals(a.waterDepth, b.waterDepth)
            assertContentEquals(a.floorBlock, b.floorBlock)
            assertContentEquals(a.biome, b.biome)
            assertContentEquals(a.mapColor, b.mapColor)
            assertEquals(a.contentHash(), b.contentHash())
        }
        assertEquals("minecraft:water", decoded.chunks[0].blockAt(0, 0))
    }

    @Test
    fun hashIgnoresTimeButSeesContent() {
        assertEquals(sampleChunk(0, 0).contentHash(), sampleChunk(0, 0).contentHash())
        assertNotEquals(sampleChunk(0, 0).contentHash(), sampleChunk(0, 0, 1).contentHash())
    }

    @Test
    fun rejectsGarbage() {
        assertFailsWith<java.io.IOException> { GreenMapCodec.decode(ByteArray(10)) }
    }
}
