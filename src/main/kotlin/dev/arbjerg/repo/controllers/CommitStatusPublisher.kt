package dev.arbjerg.repo.controllers

import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import dev.arbjerg.repo.RepositoryConfig
import io.ktor.client.*
import io.ktor.client.request.*
import org.slf4j.LoggerFactory

class CommitStatusPublisher(private val http: HttpClient) {

    private val gson = Gson()
    private val log = LoggerFactory.getLogger(javaClass)

    suspend fun publishFailure(repository: RepositoryConfig, sha: String, description: String, targetUrl: String? = null) =
        publish(repository, sha, State.FAILURE, description, targetUrl)

    suspend fun publishPending(repository: RepositoryConfig, sha: String, description: String, targetUrl: String? = null) =
        publish(repository, sha, State.PENDING, description, targetUrl)

    suspend fun publishSuccess(repository: RepositoryConfig, sha: String, description: String, targetUrl: String? = null) =
        publish(repository, sha, State.SUCCESS, description, targetUrl)

    private suspend fun publish(repository: RepositoryConfig, sha: String, state: State, description: String, targetUrl: String? = null) {
        val request = Request(state.name.lowercase(), targetUrl, description)
        try {
            http.post("https://api.github.com/repos/${repository.owner}/${repository.name}/statuses/$sha") {
                basicAuth(repository.loginUsername, repository.accessToken)
                setBody(gson.toJson(request))
                log.info("Submitted status for ${repository.owner}/${repository.name}/$sha: $request")
            }
        } catch (e: Exception) {
            log.error("Failed to publish commit status", e)
        }

    }

    private data class Request(
        val state: String,
        @SerializedName("target_url")
        val targetUrl: String?,
        val description: String,
    ) {
        val context = "repo.arbjerg.dev"
    }

    enum class State {
        FAILURE, PENDING, SUCCESS
    }
}
