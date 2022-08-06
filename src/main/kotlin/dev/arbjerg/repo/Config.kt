package dev.arbjerg.repo

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Config(
    val repositories: List<RepositoryConfig>,
    val storagePath: String,
    val port: Int,
    val host: String
) {
    companion object {
        fun load(): Config {
            return Yaml.default.decodeFromString(Config.serializer(), File("config.yml").readText())
        }
    }
}

@Serializable
data class RepositoryConfig(
    val owner: String,
    val name: String,
    val repoName: String,
    val secret: String,
    val artifactRegex: String,
    val accessToken: String,
    val loginUsername: String
)
