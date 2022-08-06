package dev.arbjerg.repo.controllers

import dev.arbjerg.repo.Config
import dev.arbjerg.repo.RepositoryConfig
import dev.arbjerg.repo.routes.Webhook
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class StorageController(private val config: Config, private val statusPublisher: CommitStatusPublisher) {

    private val log = LoggerFactory.getLogger(javaClass)
    private val storageRoot = File(config.storagePath)

    init {
        storageRoot.mkdirs()
    }

    init {
        if (storageRoot.mkdirs()) {
            log.info("Created ${storageRoot.absolutePath}")
        } else {
            log.info("Using ${storageRoot.absolutePath}")
        }
    }

    fun artifactsExist(repository: RepositoryConfig, sha: String)
        = File(storageRoot, repository.storeName + "/" + sha.take(8)).listFiles()?.isNotEmpty() == true

    suspend fun submit(repository: RepositoryConfig, sha: String, artifacts: List<Webhook.DownloadedArtifact>) {
        val repoDir = File(storageRoot, repository.storeName)
        repoDir.mkdir()
        val shaShort = sha.take(8)
        val commitDir = File(repoDir, shaShort)
        commitDir.mkdir()
        artifacts.forEach {
            val dest = File(commitDir, it.originalName)
            Files.move(it.tempFile.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
            log.info("Saved ${dest.absolutePath}")
        }

        when (artifacts.size) {
            0 -> statusPublisher.publishSuccess(repository, sha, "No relevant artifacts published")
            1 -> {
                val artifact = artifacts.first()
                val description = "Download ${artifact.originalName}"
                val url = "${config.baseUrl}/${repository.storeName}/$shaShort/${artifact.originalName}"
                statusPublisher.publishSuccess(repository, sha, description, url)
            }
            else -> {
                val description = "Download artifacts ${artifacts.map { it.originalName }}"
                val url = "${config.baseUrl}/${repository.storeName}/$shaShort"
                statusPublisher.publishSuccess(repository, sha, description, url)
            }
        }
    }

}