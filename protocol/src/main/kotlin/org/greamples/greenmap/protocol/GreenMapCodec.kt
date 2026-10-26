package org.greamples.greenmap.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Бинарный формат пачки (big-endian, весь поток сжат gzip):
 *
 * ```
 * "GMAP" u8 version
 * utf modVersion, utf minecraftVersion, utf playerName, utf playerUuid, i64 createdAt
 * i32 chunkCount
 * chunk*:
 *   utf dimension, i32 chunkX, i32 chunkZ, i64 scannedAt
 *   u16 blockPaletteSize, utf*   u16 biomePaletteSize, utf*
 *   256 x { u16 surfaceBlock, i16 surfaceY, u8 waterDepth, u16 floorBlock, u16 biome, u8 mapColor }
 * ```
 */
object GreenMapCodec {
    const val VERSION = 1
    const val CONTENT_TYPE = "application/x-greenmap-batch"
    private val MAGIC = byteArrayOf('G'.code.toByte(), 'M'.code.toByte(), 'A'.code.toByte(), 'P'.code.toByte())
    private const val MAX_CHUNKS = 65_536

    fun encode(batch: SnapshotBatch): ByteArray {
        val bytes = ByteArrayOutputStream()
        write(batch, bytes)
        return bytes.toByteArray()
    }

    fun write(batch: SnapshotBatch, target: OutputStream) {
        val gzip = GZIPOutputStream(target)
        val out = DataOutputStream(gzip)
        out.write(MAGIC)
        out.writeByte(VERSION)
        out.writeUTF(batch.modVersion)
        out.writeUTF(batch.minecraftVersion)
        out.writeUTF(batch.playerName)
        out.writeUTF(batch.playerUuid)
        out.writeLong(batch.createdAt)
        out.writeInt(batch.chunks.size)
        for (chunk in batch.chunks) writeChunk(out, chunk)
        out.flush()
        gzip.finish()
    }

    fun decode(bytes: ByteArray): SnapshotBatch = read(bytes.inputStream())

    fun read(source: InputStream): SnapshotBatch {
        val input = DataInputStream(GZIPInputStream(source).buffered())
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("not a GreenMap batch")
        val version = input.readUnsignedByte()
        if (version != VERSION) throw IOException("unsupported GreenMap batch version $version")
        val modVersion = input.readUTF()
        val minecraftVersion = input.readUTF()
        val playerName = input.readUTF()
        val playerUuid = input.readUTF()
        val createdAt = input.readLong()
        val count = input.readInt()
        if (count !in 0..MAX_CHUNKS) throw IOException("bad chunk count $count")
        val chunks = List(count) { readChunk(input) }
        return SnapshotBatch(modVersion, minecraftVersion, playerName, playerUuid, createdAt, chunks)
    }

    private fun writeChunk(out: DataOutputStream, chunk: ChunkSnapshot) {
        out.writeUTF(chunk.dimension)
        out.writeInt(chunk.chunkX)
        out.writeInt(chunk.chunkZ)
        out.writeLong(chunk.scannedAt)
        writePalette(out, chunk.blockPalette)
        writePalette(out, chunk.biomePalette)
        for (i in 0 until ChunkSnapshot.COLUMNS) {
            out.writeShort(chunk.surfaceBlock[i])
            out.writeShort(chunk.surfaceY[i])
            out.writeByte(chunk.waterDepth[i].coerceIn(0, 255))
            out.writeShort(chunk.floorBlock[i])
            out.writeShort(chunk.biome[i])
            out.writeByte(chunk.mapColor[i])
        }
    }

    private fun readChunk(input: DataInputStream): ChunkSnapshot {
        val dimension = input.readUTF()
        val chunkX = input.readInt()
        val chunkZ = input.readInt()
        val scannedAt = input.readLong()
        val blockPalette = readPalette(input)
        val biomePalette = readPalette(input)
        val n = ChunkSnapshot.COLUMNS
        val surfaceBlock = IntArray(n)
        val surfaceY = IntArray(n)
        val waterDepth = IntArray(n)
        val floorBlock = IntArray(n)
        val biome = IntArray(n)
        val mapColor = IntArray(n)
        for (i in 0 until n) {
            surfaceBlock[i] = checkIndex(input.readUnsignedShort(), blockPalette)
            surfaceY[i] = input.readShort().toInt()
            waterDepth[i] = input.readUnsignedByte()
            floorBlock[i] = checkIndex(input.readUnsignedShort(), blockPalette)
            biome[i] = checkIndex(input.readUnsignedShort(), biomePalette)
            mapColor[i] = input.readUnsignedByte()
        }
        return ChunkSnapshot(
            dimension, chunkX, chunkZ, scannedAt, blockPalette, biomePalette,
            surfaceBlock, surfaceY, waterDepth, floorBlock, biome, mapColor,
        )
    }

    private fun writePalette(out: DataOutputStream, palette: List<String>) {
        out.writeShort(palette.size)
        for (entry in palette) out.writeUTF(entry)
    }

    private fun readPalette(input: DataInputStream): List<String> {
        val size = input.readUnsignedShort()
        if (size == 0) throw IOException("empty palette")
        return List(size) { input.readUTF() }
    }

    private fun checkIndex(index: Int, palette: List<String>): Int {
        if (index >= palette.size) throw IOException("palette index $index out of range ${palette.size}")
        return index
    }
}

/** Удобный сборщик снимка: сам ведёт палитры блоков и биомов. */
class ChunkSnapshotBuilder(
    private val dimension: String,
    private val chunkX: Int,
    private val chunkZ: Int,
) {
    private val blocks = LinkedHashMap<String, Int>()
    private val biomes = LinkedHashMap<String, Int>()
    private val surfaceBlock = IntArray(ChunkSnapshot.COLUMNS)
    private val surfaceY = IntArray(ChunkSnapshot.COLUMNS) { ChunkSnapshot.NO_DATA_Y }
    private val waterDepth = IntArray(ChunkSnapshot.COLUMNS)
    private val floorBlock = IntArray(ChunkSnapshot.COLUMNS)
    private val biome = IntArray(ChunkSnapshot.COLUMNS)
    private val mapColor = IntArray(ChunkSnapshot.COLUMNS)

    init {
        // Индекс 0 всегда воздух/биом по умолчанию, чтобы пустые колонки были валидны.
        blocks[AIR] = 0
        biomes[UNKNOWN_BIOME] = 0
    }

    fun setColumn(
        x: Int, z: Int,
        surface: String, y: Int, depth: Int, floor: String, biomeId: String, mapColorId: Int,
    ) {
        val i = z * 16 + x
        surfaceBlock[i] = blocks.getOrPut(surface) { blocks.size }
        surfaceY[i] = y
        waterDepth[i] = depth.coerceIn(0, 255)
        floorBlock[i] = blocks.getOrPut(floor) { blocks.size }
        biome[i] = biomes.getOrPut(biomeId) { biomes.size }
        mapColor[i] = mapColorId and 0xFF
    }

    fun build(scannedAt: Long): ChunkSnapshot = ChunkSnapshot(
        dimension, chunkX, chunkZ, scannedAt,
        blocks.keys.toList(), biomes.keys.toList(),
        surfaceBlock.copyOf(), surfaceY.copyOf(), waterDepth.copyOf(),
        floorBlock.copyOf(), biome.copyOf(), mapColor.copyOf(),
    )

    companion object {
        const val AIR = "minecraft:air"
        const val UNKNOWN_BIOME = "minecraft:plains"
    }
}
