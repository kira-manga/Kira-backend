package me.manga.kira.backend.tutorial.application

import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.tutorial.domain.MediaFileKind
import me.manga.kira.backend.tutorial.domain.MediaFileSnapshot
import me.manga.kira.backend.tutorial.domain.MediaInventory
import me.manga.kira.backend.tutorial.domain.MediaReadResult
import me.manga.kira.backend.tutorial.domain.MediaReconciliationFinding
import me.manga.kira.backend.tutorial.domain.MediaReconciliationReport
import me.manga.kira.backend.tutorial.domain.MediaStorageIssue
import me.manga.kira.backend.tutorial.domain.MediaTransaction
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorage
import me.manga.kira.backend.tutorial.domain.TutorialMediaStorageException
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TutorialMediaReconciliationService(
    private val repository: TutorialRepository,
    private val storage: TutorialMediaStorage,
    private val properties: KiraTutorialProperties,
) {
    @Transactional(readOnly = true)
    fun inspect(): MediaReconciliationReport = scan(null)

    @Transactional(rollbackFor = [Exception::class])
    fun reconcile(): MediaReconciliationReport {
        // Explicit operator admission is required, but this boolean cannot prove that old or
        // maintenance writers were excluded/drained or that installed shared storage is fenced.
        check(properties.mediaReconciliationEnabled) { "tutorial media reconciliation mutation is disabled" }
        return scan(repository.acquireMediaLock())
    }

    private fun scan(transaction: MediaTransaction?): MediaReconciliationReport {
        val limit = properties.mediaInspectionLimit
        val inventory = inventory(limit)
        transaction?.let(repository::verifyMediaLock)
        // This MUST be a separate fresh READ_COMMITTED statement AFTER the fixed inventory.
        // Overflow cannot authorize any deletion, including candidates absent from this prefix.
        val observedRows = repository.listMedia(limit + 1)
        val rowsComplete = observedRows.size <= limit
        val rows = observedRows.take(limit)
        val findings = inventory.issues.map { MediaReconciliationFinding(it) }.toMutableList()
        if (!rowsComplete) findings += MediaReconciliationFinding(MediaStorageIssue.ROW_LIMIT)
        rows.forEach { media ->
            inspectRow(media)?.let { findings += MediaReconciliationFinding(it, media.id, media.storageFilename, media.published) }
        }
        val storageReliable = findings.none {
            it.issue == MediaStorageIssue.IO_FAILURE || it.issue == MediaStorageIssue.IDENTITY_CHANGED || it.issue == MediaStorageIssue.UNSAFE_FILE
        }
        var complete = inventory.complete && inventory.issues.isEmpty() && rowsComplete && storageReliable
        // Even invalid row metadata reserves its recorded name. Corruption never grants orphan
        // cleanup permission and this service never deletes/rewrites a row or stored checksum.
        val reservedNames = rows.map { it.storageFilename }.toSet()
        val rowless = if (rowsComplete) inventory.candidates.filter { it.filename !in reservedNames } else emptyList()
        rowless.forEach {
            val issue = if (it.kind == MediaFileKind.FINAL) MediaStorageIssue.ORPHAN else MediaStorageIssue.STAGING
            findings += MediaReconciliationFinding(issue, filename = it.filename)
        }
        var quarantined = 0
        val mutationInventoryComplete = inventory.complete && rowsComplete && storageReliable
        if (transaction != null && mutationInventoryComplete) {
            for (candidate in rowless) {
                // Verification never reacquires the lock or reconnects. DB failures escape and
                // stop the sweep; they are not converted into an apparently clean empty store.
                repository.verifyMediaLock(transaction)
                try {
                    if (quarantine(candidate, transaction)) quarantined++
                } catch (exception: TutorialMediaStorageException) {
                    findings += MediaReconciliationFinding(exception.issue, filename = candidate.filename)
                    complete = false
                    break
                }
            }
        }
        return MediaReconciliationReport(
            rowsChecked = rows.size,
            publishedRowsChecked = rows.count { it.published },
            publishedComplete = rowsComplete && findings.none { it.published == true },
            complete = complete,
            findings = findings.toList(),
            quarantinedFiles = quarantined,
            retainedQuarantineFiles = inventory.quarantinedFiles,
        )
    }

    private fun inventory(limit: Int): MediaInventory = try {
        storage.inventory(limit)
    } catch (exception: TutorialMediaStorageException) {
        MediaInventory(emptyList(), listOf(exception.issue), complete = false)
    }

    private fun inspectRow(media: StoredMedia): MediaStorageIssue? = try {
        when (val result = storage.readVerified(media)) {
            is MediaReadResult.Verified -> null
            is MediaReadResult.Unavailable -> result.issue
        }
    } catch (exception: TutorialMediaStorageException) {
        exception.issue
    }

    private fun quarantine(candidate: MediaFileSnapshot, transaction: MediaTransaction): Boolean =
        storage.quarantine(candidate) { repository.verifyMediaLock(transaction) }
}
