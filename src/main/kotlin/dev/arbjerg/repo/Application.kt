package dev.arbjerg.repo

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import dev.arbjerg.repo.plugins.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        configureTemplating()
        configureSerialization()
        configureRouting()
    }.start(wait = true)
}
