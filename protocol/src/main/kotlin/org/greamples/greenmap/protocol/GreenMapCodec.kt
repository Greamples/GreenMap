package org.greamples.greenmap.protocol

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.FilterInputStream
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
    // Лимиты защищают сервер от "gzip-бомб" и мусора от недобросовестных клиентов.
    const val MAX_CHUNKS = 1024
    const val MAX_DECOMPRESSED_BYTES = 16L * 1024 * 1024
    private const val MAX_BLOCK_PALETTE = 1 + 2 * ChunkSnapshot.COLUMNS
    private const val MAX_BIOME_PALETTE = 1 + ChunkSnapshot.COLUMNS
    const val MAX_STRING = 256
    private val DIMENSION_ID = Regex("^[a-z0-9_.-]+:[a-z0-9_./-]+$")

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
        val input = DataInputStream(LimitedInputStream(GZIPInputStream(source), MAX_DECOMPRESSED_BYTES).buffered())
        val magic = ByteArray(4)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) throw IOException("not a GreenMap batch")
        val version = input.readUnsignedByte()
        if (version != VERSION) throw IOException("unsupported GreenMap batch version $version")
        val modVersion = readString(input)
        val minecraftVersion = readString(input)
        val playerName = readString(input)
        val playerUuid = readString(input)
        val createdAt = input.readLong()
        val count = input.readInt()
        if (count !in 0..MAX_CHUNKS) throw IOException("bad chunk count $count")
        val chunks = List(count) { readChunk(input) }
        if (input.read() != -1) throw IOException("trailing data after last chunk")
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
        val dimension = readString(input)
        if (!DIMENSION_ID.matches(dimension)) throw IOException("bad dimension id")
        val chunkX = input.readInt()
        val chunkZ = input.readInt()
        val scannedAt = input.readLong()
        val blockPalette = readPalette(input, MAX_BLOCK_PALETTE)
        val biomePalette = readPalette(input, MAX_BIOME_PALETTE)
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

    private fun readPalette(input: DataInputStream, max: Int): List<String> {
        val size = input.readUnsignedShort()
        if (size == 0 || size > max) throw IOException("bad palette size $size")
        return List(size) { readString(input) }
    }

    private fun readString(input: DataInputStream): String {
        val value = input.readUTF()
        if (value.length > MAX_STRING) throw IOException("string too long")
        return value
    }

    /** Обрывает чтение, если распакованные данные превысили лимит. */
    private class LimitedInputStream(source: InputStream, private var remaining: Long) : FilterInputStream(source) {
        override fun read(): Int {
            val b = super.read()
            if (b >= 0) consume(1)
            return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            val n = super.read(b, off, len)
            if (n > 0) consume(n.toLong())
            return n
        }

        private fun consume(n: Long) {
            remaining -= n
            if (remaining < 0) throw IOException("batch is too large after decompression")
        }
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
        // Слишком длинные id (бывают у модов) сервер не примет — заменяем заглушкой, цвет даст MapColor.
        fun id(value: String) = if (value.length > GreenMapCodec.MAX_STRING) UNKNOWN_BLOCK else value
        surfaceBlock[i] = blocks.getOrPut(id(surface)) { blocks.size }
        surfaceY[i] = y
        waterDepth[i] = depth.coerceIn(0, 255)
        floorBlock[i] = blocks.getOrPut(id(floor)) { blocks.size }
        biome[i] = biomes.getOrPut(if (biomeId.length > GreenMapCodec.MAX_STRING) UNKNOWN_BIOME else biomeId) { biomes.size }
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
        const val UNKNOWN_BLOCK = "greenmap:unknown"
    }
}
