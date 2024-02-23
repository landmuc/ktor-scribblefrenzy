package com.landmuc

import com.google.gson.Gson
import com.landmuc.plugins.*
import com.landmuc.session.DrawingSession
import io.ktor.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.sessions.*
import io.ktor.util.*

fun main() {
    embeddedServer(Netty, port = 8082, host = "0.0.0.0", module = Application::module)
        .start(wait = true)
}

val server = DrawingServer()
val gson = Gson()

fun Application.module() {
    configureSessions()
    intercept(ApplicationCallPipeline.Features) {
        if(call.sessions.get<DrawingSession>() == null) {
            val clientId = call.parameters["client_id"] ?: ""
            call.sessions.set(DrawingSession(clientId, generateNonce()))
        }
    }
    configureSockets()
    configureRouting()
    configureSerialization()
    configureMonitoring()
}
