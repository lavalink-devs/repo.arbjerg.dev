package dev.arbjerg.repo

import io.ktor.server.engine.*
import io.ktor.server.netty.*
import dev.arbjerg.repo.routes.configureWebhook
import io.ktor.serialization.gson.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.application.*

fun main() {
    embeddedServer(Netty, port = 8080, host = "0.0.0.0") {
        install(ContentNegotiation) {
            gson {
            }
        }

        val config = Config.load()
        configureWebhook(config)
    }.start(wait = true)
}
