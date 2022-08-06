package dev.arbjerg.repo

import dev.arbjerg.repo.controllers.StorageController
import dev.arbjerg.repo.routes.Webhook
import io.ktor.client.*
import io.ktor.serialization.gson.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*

fun main() {
    val config = Config.load()
    val httpClient = HttpClient {
        developmentMode = true
        expectSuccess = true
        followRedirects = true
    }
    val storageController = StorageController(config)
    embeddedServer(Netty, port = config.port, host = config.host) {
        install(ContentNegotiation) {
            gson()
        }

        Webhook(config, httpClient, storageController).apply { configure() }
    }.start(wait = true)
}
