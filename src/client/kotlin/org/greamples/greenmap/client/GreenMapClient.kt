package org.greamples.greenmap.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.SharedConstants

class GreenMapClient : ClientModInitializer {

    companion object {
        @Volatile
        var isTargetServer = false
            private set

        val modVersion: String by lazy {
            FabricLoader.getInstance().getModContainer("greenmap").map { it.metadata.version.friendlyString }.orElse("dev")
        }
        val minecraftVersion: String by lazy { SharedConstants.getCurrentVersion().name() }
    }

    override fun onInitializeClient() {
        val config = GreenMapConfig.load()
        if (!config.enabled) return

        val controller = ChunkCaptureController(config)
        ChunkQueueManager.startQueueWorker(config, ChunkUploader(config))

        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            isTargetServer = config.matchesServer(client.currentServer?.ip)
            ChunkQueueManager.resetSession()
            ChunkQueueManager.playerName = client.user.name
            ChunkQueueManager.playerUuid = client.user.profileId.toString()
            controller.reset()
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            isTargetServer = false
            controller.reset()
        }
        ClientChunkEvents.CHUNK_LOAD.register { _, chunk -> controller.onChunkLoaded(chunk.pos) }
        ClientChunkEvents.CHUNK_UNLOAD.register { _, chunk -> controller.onChunkUnloaded(chunk.pos) }
        ClientTickEvents.END_CLIENT_TICK.register(controller::tick)
    }
}
