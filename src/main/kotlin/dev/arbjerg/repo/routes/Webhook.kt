package dev.arbjerg.repo.routes

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName
import dev.arbjerg.repo.Config
import dev.arbjerg.repo.RepositoryConfig
import dev.arbjerg.repo.controllers.CommitStatusPublisher
import dev.arbjerg.repo.controllers.StorageController
import io.ktor.client.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.routing.*
import io.ktor.util.*
import io.ktor.util.cio.*
import io.ktor.util.pipeline.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import org.slf4j.LoggerFactory
import java.io.File
import java.util.regex.Pattern
import java.util.zip.ZipFile
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class Webhook(
    private val config: Config,
    private val http: HttpClient,
    private val storageController: StorageController,
    private val statusPublisher: CommitStatusPublisher
) {
    private val gson = Gson()
    private val log = LoggerFactory.getLogger(Webhook::class.java)
    private val zenLogger = LoggerFactory.getLogger("Zen")

    fun Application.configure() {
        routing {
            post("/webhook") {
                val requestBody = call.request.receiveChannel().toByteArray().toString(charset("UTF-8"))
                val eventName = call.request.header("X-GitHub-Event")
                if (eventName == "ping") {
                    val zen = gson.fromJson(requestBody, JsonObject::class.java)["zen"].asString
                    zenLogger.info(zen)
                    call.response.status(HttpStatusCode.NoContent)
                    return@post
                } else if (eventName != "workflow_run") {
                    call.response.status(HttpStatusCode.NoContent)
                    return@post
                }
                val payload = gson.fromJson(requestBody, WebhookPayload::class.java)

                val repository = config.repositories.find {
                    it.name.equals(payload.repository.name, ignoreCase = true)
                            && it.owner.equals(payload.repository.owner.login, ignoreCase = true)
                } ?: return@post this@Webhook.log.info("Ignoring webhook from unrecognised repository ${payload.repository.fullName}")

                verifyIntegrity(repository, requestBody)
                val sha = payload.workflowRun.headSha
                try {
                    if (payload.action != "completed") {
                        if (payload.action == "requested" && !storageController.artifactsExist(repository, sha)) {
                            statusPublisher.publishPending(repository, sha, "Waiting for artifacts")
                        }
                        this@Webhook.log.info("Ignoring workflow_run because action is ${payload.action}")
                        call.response.status(HttpStatusCode.NoContent)
                        return@post
                    }

                    if (!storageController.artifactsExist(repository, sha)) {
                        statusPublisher.publishPending(repository, sha, "Downloading artifacts")
                    }

                    this@Webhook.log.info("Workflow completed for ${payload.repository.fullName}")
                    call.response.status(HttpStatusCode.Accepted)

                    async(Dispatchers.IO) {
                        try {
                            storageController.submit(
                                repository,
                                payload.workflowRun.headSha,
                                downloadArtifacts(repository, payload.workflowRun)
                            )
                        } catch (e: Exception) {
                            this@Webhook.log.error("Error in coroutine", e)
                        }
                    }.start()

                    if (!storageController.artifactsExist(repository, sha)) {
                        statusPublisher.publishPending(repository, sha, "Downloading artifacts")
                    }
                } catch (e: Exception) {
                    if (!storageController.artifactsExist(repository, sha)) {
                        statusPublisher.publishFailure(repository, sha, e.javaClass.canonicalName + ": " + e.message)
                    }
                }
            }
        }
    }

    private fun PipelineContext<Unit, ApplicationCall>.verifyIntegrity(
        repoConfig: RepositoryConfig,
        rawBody: String
    ) {
        val githubHashHeader = call.request.header("X-Hub-Signature-256")
        if (githubHashHeader?.startsWith("sha256=") != true) error("Unexpected hash algorithm: $githubHashHeader")
        val githubHash = githubHashHeader.drop("sha256=".length)

        val secretKeySpec = SecretKeySpec(repoConfig.secret.toByteArray(), "HmacSHA256")
        val digest = Mac.getInstance("HmacSHA256")
            .apply { init(secretKeySpec) }
            .doFinal(rawBody.toByteArray())
            .toHex()

        if (digest != githubHash) error("Invalid signature")
    }

    private fun ByteArray.toHex(): String = joinToString("") {
        java.lang.Byte.toUnsignedInt(it).toString(radix = 16).padStart(2, '0')
    }

    data class WebhookPayload(
        val action: String,
        val repository: Repository,
        @SerializedName("workflow_run")
        val workflowRun: WorkflowRun
    ) {
        data class Repository(val owner: Owner, val name: String) {
            data class Owner(val login: String)

            val fullName: String get() = "${owner.login}/$name"
        }

        data class WorkflowRun(
            @SerializedName("head_sha") val headSha: String,
            @SerializedName("artifacts_url") val artifactsUrl: String
        )
    }

    private suspend fun downloadArtifacts(repositoryConfig: RepositoryConfig, workflowRun: WebhookPayload.WorkflowRun): List<DownloadedArtifact> {
        val response = gson.fromJson(http.get(workflowRun.artifactsUrl).bodyAsText(), ArtifactsResponse::class.java)
        return response.artifacts.mapNotNull { artifact ->
            if (!Pattern.compile(repositoryConfig.artifactRegex).matcher(artifact.name).matches()) {
                log.info("Ignoring ${artifact.name} because it does not match regex ${repositoryConfig.artifactRegex}")
                return@mapNotNull null
            }

            val url = artifact.url + "/zip"
            log.info("Downloading $url")
            var file: File? = null
            var zipFile: File? = null
            try {
                file = File(artifact.name).run { File.createTempFile(nameWithoutExtension, extension) }
                zipFile = File.createTempFile(artifact.name, ".zip")

                http.get(url) {
                    basicAuth(repositoryConfig.loginUsername, repositoryConfig.accessToken)
                }.bodyAsChannel().copyAndClose(zipFile.writeChannel())

                log.info("Downloaded ${zipFile.name}")

                val zip = ZipFile(zipFile!!)
                val entry = zip.getEntry(artifact.name) ?: error("Downloaded zip file unexpectedly does not contain ${artifact.name}")
                zip.getInputStream(entry).toByteReadChannel().copyAndClose(file.writeChannel())
                log.info("Decompressed ${entry.name}")
                DownloadedArtifact(artifact.name, file)
            } catch (e: Exception) {
                file?.delete()
                throw e
            } finally {
                zipFile?.delete()
            }
        }
    }

    data class ArtifactsResponse(
        val artifacts: List<Artifact>
    ) {
        data class Artifact(
            val name: String,
            val url: String
        )
    }

    data class DownloadedArtifact(
        val originalName: String,
        val tempFile: File
    )
}
