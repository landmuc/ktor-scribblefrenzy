package com.landmuc.plugins

import io.ktor.application.*
import io.ktor.features.*
import io.ktor.request.*
import io.ktor.response.*
import org.slf4j.event.*

fun Application.configureMonitoring() {
    install(CallLogging)
}
