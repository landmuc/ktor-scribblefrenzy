package com.landmuc.plugins

import com.landmuc.routes.createRoomRoute
import com.landmuc.routes.getRoomsRoute
import com.landmuc.routes.joinRoomRoute
import io.ktor.application.*
import io.ktor.http.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*

fun Application.configureRouting() {
    install(Routing) {
        createRoomRoute()
        getRoomsRoute()
        joinRoomRoute()
    }
}
