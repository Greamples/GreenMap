package org.greamples.greenmap.renderer

import org.greamples.greenmap.protocol.ChunkSnapshot
import org.greamples.greenmap.protocol.SnapshotBatch

/**
 * Склейка данных от разных игроков. Т.к. данные семантические, конфликтов "чей ресурспак"
 * нет: для каждого чанка просто побеждает самый свежий снимок.
 */
class WorldMap {
    data class ChunkKey(val dimension: String, val x: Int, val z: Int)
    data class RegionKey(val dimension: String, val x: Int, val z: Int)

    private val chunks = HashMap<ChunkKey, ChunkSnapshot>()

    val size get() = chunks.size

    private val receivedAt = HashMap<ChunkKey, Long>()

    /**
     * @param receivedAt время получения пачки сервером. Время из пачки задаёт клиент, поэтому
     * ему не доверяем: иначе снимок с scannedAt = Long.MAX_VALUE нельзя было бы перезаписать.
     */
    fun add(batch: SnapshotBatch, receivedAt: Long = System.currentTimeMillis()) =
        batch.chunks.forEach { add(it, receivedAt) }

    fun add(snapshot: ChunkSnapshot, receivedAt: Long = System.currentTimeMillis()) {
        val key = ChunkKey(snapshot.dimension, snapshot.chunkX, snapshot.chunkZ)
        val time = minOf(snapshot.scannedAt, receivedAt)
        val existing = this.receivedAt[key]
        if (existing == null || existing <= time) {
            chunks[key] = snapshot
            this.receivedAt[key] = time
        }
    }

    fun chunk(dimension: String, x: Int, z: Int): ChunkSnapshot? = chunks[ChunkKey(dimension, x, z)]

    fun regions(): Set<RegionKey> = chunks.keys.mapTo(sortedSetOf(compareBy({ it.dimension }, { it.x }, { it.z }))) {
        RegionKey(it.dimension, it.x shr 5, it.z shr 5)
    }
}
