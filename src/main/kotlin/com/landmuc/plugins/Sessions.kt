package com.landmuc.plugins

import com.landmuc.session.DrawingSession
import io.ktor.application.*
import io.ktor.sessions.*

fun Application.configureSessions() {
    install(Sessions) {
        cookie<DrawingSession>("SESSION")
    }
}