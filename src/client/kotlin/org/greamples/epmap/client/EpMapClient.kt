package org.greamples.epmap.client

import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

class EpMapClient : ClientModInitializer {

    companion object {
        val targetServerIps = listOf("ekb.mc.epserv.ru", "3.ekb.mc.epserv.ru", "fra.mc.epserv.ru", "hel.mc.epserv.ru", "beam.mc.epserv.ru", "mc.epserv.ru") 
        
        @Volatile
        var isTargetServer = false
            private set
    }

    override fun onInitializeClient() {
        ClientPlayConnectionEvents.JOIN.register { _, _, client ->
            val serverInfo = client.currentServer
            isTargetServer = if (serverInfo != null) {
                targetServerIps.any { ip -> 
                    serverInfo.ip.contains(ip, ignoreCase = true)
                }
            } else {
                false
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            isTargetServer = false
        }
    }
}
