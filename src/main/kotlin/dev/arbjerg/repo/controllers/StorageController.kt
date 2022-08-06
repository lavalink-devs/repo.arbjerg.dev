package dev.arbjerg.repo.controllers

import dev.arbjerg.repo.Config
import dev.arbjerg.repo.RepositoryConfig
import dev.arbjerg.repo.routes.Webhook
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Files

class StorageController(config: Config) {

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

    fun submit(repository: RepositoryConfig, sha: String, artifacts: List<Webhook.DownloadedArtifact>) {
        val repoDir = File(storageRoot, repository.repoName)
        repoDir.mkdir()
        val shaShort = sha.take(8)
        val commitDir = File(repoDir, shaShort)
        commitDir.mkdir()
        artifacts.forEach {
            val dest = File(commitDir, it.originalName)
            Files.move(it.tempFile.toPath(), dest.toPath())
            log.info("Saved ${dest.absolutePath}")
        }
    }

}