package dev.arbjerg.repo

import com.charleskorn.kaml.Yaml
import kotlinx.serialization.Serializable
import java.io.File

@Serializable
data class Config(
    val repositories: List<RepositoryConfig>
) {
    companion object {
        fun load(): Config {
            return Yaml.default.decodeFromString(Config.serializer(), File("config.yml").readText())
        }
    }
}

@Serializable
data class RepositoryConfig(val owner: String, val name: String, val secret: String, val statusAccessToken: String?)
