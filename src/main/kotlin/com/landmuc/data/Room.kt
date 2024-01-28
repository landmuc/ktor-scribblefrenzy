package com.landmuc.data

import io.ktor.http.cio.websocket.*
import kotlinx.coroutines.isActive

class Room(
    val name: String,
    val maxPlayers: Int,
    var players: List<Player> = listOf()
) {

    suspend fun broadcast(message: String) {
        players.forEach {player ->
            if (player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }

    suspend fun broadcastToAllExcept(message: String, clientID: String) {
        players.forEach { player ->
            if (player.clientId != clientID && player.socket.isActive) {
                player.socket.send(Frame.Text(message))
            }
        }
    }
}