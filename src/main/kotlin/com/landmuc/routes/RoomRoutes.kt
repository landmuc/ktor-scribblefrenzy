package com.landmuc.routes

import com.landmuc.data.Room
import com.landmuc.data.models.BasicApiResponse
import com.landmuc.data.models.CreateRoomRequest
import com.landmuc.data.models.RoomResponse
import com.landmuc.server
import com.landmuc.util.Constants.MAX_ROOM_SIZE
import com.landmuc.util.Constants.MIN_ROOM_SIZE
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*

fun Route.createRoomRoute() {
    route("/api/createRoom") {
        post {
            val roomRequest = call.receiveOrNull<CreateRoomRequest>()
            if (roomRequest == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@post
            }
            if (server.rooms[roomRequest.name] != null) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "Room already exists.")
                    )
                return@post
            }
            if (roomRequest.maxPlayers < MIN_ROOM_SIZE) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "The minimum room size is $MIN_ROOM_SIZE.")
                )
                return@post
            }
            if (roomRequest.maxPlayers > MAX_ROOM_SIZE) {
                call.respond(
                    HttpStatusCode.OK,
                    BasicApiResponse(false, "The maximum room size is $MAX_ROOM_SIZE.")
                )
                return@post
            }
            val room = Room(
                roomRequest.name,
                roomRequest.maxPlayers
            )
            server.rooms[roomRequest.name] = room
            println("Room created: ${roomRequest.name}")

            call.respond(
                HttpStatusCode.OK,
                BasicApiResponse(true)
            )
        }
    }
}

fun Route.getRoomsRoute() {
    route("/api/getRooms") {
        get {
            val searchQuery = call.parameters["searchQuery"]
            if (searchQuery == null) {
                call.respond(HttpStatusCode.BadRequest)
                return@get
            }
            val roomsResult = server.rooms.filterKeys {
                it.contains(searchQuery, ignoreCase = true)
            }
            val roomResponses = roomsResult.values.map { room ->
                RoomResponse(
                    room.name,
                    room.maxPlayers,
                    room.players.size)
            }.sortedBy { it.name }

            call.respond(
                HttpStatusCode.OK,
                roomResponses)
        }
    }
}