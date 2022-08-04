package dev.arbjerg.repo.routes

import com.google.gson.Gson
import com.google.gson.JsonObject
import dev.arbjerg.repo.Config
import dev.arbjerg.repo.RepositoryConfig
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.pipeline.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

private val gson = Gson()
private val zenLogger = LoggerFactory.getLogger("Zen")

fun Application.configureWebhook(config: Config) {
    routing {
        post("/webhook") {
            val requestBody = call.request.receiveChannel().toByteArray().toString(charset("UTF-8"))
            val eventName = call.request.header("X-GitHub-Event")
            if (eventName == "ping") {
                val zen = call.receive(JsonObject::class)["zen"].asString
                zenLogger.info(zen)
                call.response.status(HttpStatusCode.NoContent)
                return@post
            } else if (eventName != "workflow_run") {
                call.response.status(HttpStatusCode.NoContent)
                return@post
            }
            val payload = gson.fromJson(requestBody, WebhookPayload::class.java)
            val repository = verifyRepository(config, requestBody, payload)
            this@configureWebhook.log.info("Workflow run for ${payload}")
            call.response.status(HttpStatusCode.NoContent)
        }
    }
}

suspend fun PipelineContext<Unit, ApplicationCall>.verifyRepository(config: Config, rawBody: String, payload: WebhookPayload): RepositoryConfig {
    val repoConfig = config.repositories.find {
        it.name.equals(payload.repository.name, ignoreCase = true)
                && it.owner.equals(payload.repository.owner.login, ignoreCase = true)
    } ?: error("${payload.repository.owner.login}/${payload.repository.name} is not supported")

    val githubHashHeader = call.request.header("X-Hub-Signature-256")
    if (githubHashHeader?.startsWith("sha256=") != true) error("Unexpected hash algorithm: $githubHashHeader")
    val githubHash = githubHashHeader.drop("sha256=".length)

    val secretKeySpec = SecretKeySpec(repoConfig.secret.toByteArray(), "HmacSHA256")
    val digest = Mac.getInstance("HmacSHA256")
        .apply { init(secretKeySpec) }
        .doFinal(rawBody.toByteArray())
        .toHex()

    if (digest != githubHash) error("Invalid signature")
    return repoConfig
}

private fun ByteArray.toHex(): String = joinToString("") {
    java.lang.Byte.toUnsignedInt(it).toString(radix = 16).padStart(2, '0')
}

data class WebhookPayload(
    val repository: Repository
)

data class Repository(val owner: Owner, val name: String) {
    data class Owner(val login: String)
}
