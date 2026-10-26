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

    fun add(batch: SnapshotBatch) = batch.chunks.forEach(::add)

    fun add(snapshot: ChunkSnapshot) {
        val key = ChunkKey(snapshot.dimension, snapshot.chunkX, snapshot.chunkZ)
        val existing = chunks[key]
        if (existing == null || existing.scannedAt <= snapshot.scannedAt) chunks[key] = snapshot
    }

    fun chunk(dimension: String, x: Int, z: Int): ChunkSnapshot? = chunks[ChunkKey(dimension, x, z)]

    fun regions(): Set<RegionKey> = chunks.keys.mapTo(sortedSetOf(compareBy({ it.dimension }, { it.x }, { it.z }))) {
        RegionKey(it.dimension, it.x shr 5, it.z shr 5)
    }
}
