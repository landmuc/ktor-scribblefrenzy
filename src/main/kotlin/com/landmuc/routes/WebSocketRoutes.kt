package com.landmuc.routes

import com.google.gson.JsonParser
import com.landmuc.data.Player
import com.landmuc.data.Room
import com.landmuc.data.models.*
import com.landmuc.gson
import com.landmuc.server
import com.landmuc.session.DrawingSession
import com.landmuc.util.Constants.TYPE_ANNOUNCEMENT
import com.landmuc.util.Constants.TYPE_CHAT_MESSAGE
import com.landmuc.util.Constants.TYPE_CHOSEN_WORD
import com.landmuc.util.Constants.TYPE_DRAW_DATA
import com.landmuc.util.Constants.TYPE_GAME_STATE
import com.landmuc.util.Constants.TYPE_JOIN_ROOM_HANDSHAKE
import com.landmuc.util.Constants.TYPE_NEW_WORDS
import com.landmuc.util.Constants.TYPE_PHASE_CHANGE
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when(payload) {
                is JoinRoomHandshake -> {
                    val room = server.rooms[payload.roomName]
                    if (room == null) {
                        val gameError = GameError(GameError.ERROR_ROOM_NOT_FOUND)
                        socket.send(Frame.Text(gson.toJson(gameError)))
                        return@standardWebSocket
                    }
                    val player = Player(
                        username = payload.username,
                        socket = socket,
                        clientId = clientId
                    )
                    server.playerJoined(player)
                    if (!room.containsPlayer(player.username)) {
                        room.addPlayer(
                            clientId = payload.clientId,
                            username = payload.username,
                            socket = socket
                        )
                    }

                }
                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?:return@standardWebSocket
                    if (room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                    }
                }
                is ChosenWord -> {
                    val room = server.rooms[payload.roomName] ?: return@standardWebSocket
                    room.setWordsAndSwitchToGameRunning(payload.chosenWord)
                }
                is ChatMessage -> {}
            }
        }
    }
}
fun Route.standardWebSocket(
    // Frame is a single piece of data that is sent or received using websockets
    handleFrame: suspend (
        socket: DefaultWebSocketServerSession,
        clientId: String,
        // message -> unparsed json data
        message: String,
        // payLoad -> parsed json data
        payload: BaseModel
        ) -> Unit
) {
    webSocket {
        val session = call.sessions.get<DrawingSession>()
        if (session == null) {
            close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "No session."))
            return@webSocket
        }
        try {
            // consumes each Frame received by the ReceiveChannel<Frame>
            // suspends as long as this channel is open
            incoming.consumeEach { frame ->
                if (frame is Frame.Text) {
                    // raw json string
                    val message = frame.readText()
                    val jsonObject = JsonParser.parseString(message).asJsonObject
                    val type = when(jsonObject.get("type").asString) {
                        TYPE_CHAT_MESSAGE -> ChatMessage::class.java
                        TYPE_DRAW_DATA -> DrawData::class.java
                        TYPE_ANNOUNCEMENT -> Announcement::class.java
                        TYPE_JOIN_ROOM_HANDSHAKE -> JoinRoomHandshake::class.java
                        TYPE_PHASE_CHANGE -> PhaseChange::class.java
                        TYPE_CHOSEN_WORD -> ChosenWord::class.java
                        TYPE_GAME_STATE -> GameState::class.java
                        TYPE_NEW_WORDS -> NewWords::class.java
                        else -> BaseModel::class.java
                    }
                    // convert json string to one of our data classes
                    val payload = gson.fromJson(message, type)
                    handleFrame(
                        this,
                        session.clientId,
                        message,
                        payload
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        finally {
            // Handle disconnects
        }
    }
}