package com.landmuc.routes

import com.google.gson.JsonParser
import com.landmuc.data.Room
import com.landmuc.data.models.BaseModel
import com.landmuc.data.models.ChatMessage
import com.landmuc.data.models.DrawData
import com.landmuc.gson
import com.landmuc.server
import com.landmuc.session.DrawingSession
import com.landmuc.util.Constants.TYPE_CHAT_MESSAGE
import com.landmuc.util.Constants.TYPE_DRAW_DATA
import io.ktor.http.cio.websocket.*
import io.ktor.routing.*
import io.ktor.sessions.*
import io.ktor.websocket.*
import kotlinx.coroutines.channels.consumeEach

fun Route.gameWebSocketRoute() {
    route("/ws/draw") {
        standardWebSocket { socket, clientId, message, payload ->
            when(payload) {
                is DrawData -> {
                    val room = server.rooms[payload.roomName] ?:return@standardWebSocket
                    if (room.phase == Room.Phase.GAME_RUNNING) {
                        room.broadcastToAllExcept(message, clientId)
                    }
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