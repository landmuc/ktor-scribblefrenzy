package com.landmuc.plugins

import io.ktor.application.*
import io.ktor.http.cio.websocket.*
import io.ktor.request.*
import io.ktor.response.*
import io.ktor.routing.*
import io.ktor.websocket.*
import java.time.*

fun Application.configureSockets() {
    install(WebSockets)
}
