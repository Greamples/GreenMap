package org.greamples.epmap.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class EpMapClient : ClientModInitializer {

    companion object {
        const val SERVER_IP = "epserv.ru"

        @Volatile
        var isTargetServer = false
            private set
    }

    override fun onInitializeClient() {
        ChunkQueueManager.startQueueWorker()
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            val serverInfo = client.currentServer
            isTargetServer = serverInfo?.ip?.contains(SERVER_IP, ignoreCase = true) ?: false
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            isTargetServer = false
        }
    }
}
