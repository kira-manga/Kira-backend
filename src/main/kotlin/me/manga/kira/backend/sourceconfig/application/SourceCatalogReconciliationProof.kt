package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.domain.PublishedCatalogMember
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogEntry
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogManifest
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.springframework.stereotype.Component
import java.time.format.DateTimeFormatter

/** Read-only proof under the caller's G lock. No signing, materialization or current-reference admission. */
@Component
class SourceCatalogReconciliationProof(
    private val documents: PublishedDocumentRepository,
    private val catalogs: PublishedSourceCatalogRepository,
    private val sources: SourceConfigRepository,
    private val verifier: StoredCatalogPairVerifier,
) {
    internal fun verify(intent: ReconciliationIntent) {
        val budget = ProofBudget()
        var pair = readPair(intent.expectedLatestRevision)
        check(pair.document.checksum == intent.documentChecksum && pair.manifest.checksum == intent.catalogChecksum)
        val currentRemoved = catalogs.removedApis(intent.expectedLatestRevision)
        val historicalApis = hashSetOf<String>()
        var currentApis: Set<String> = emptySet()
        var latest = true
        while (true) {
            budget.document()
            val members = verifyPair(pair, budget)
            historicalApis.addAll(members.map { it.entry.api })
            check(historicalApis.size <= MAX_MEMBERS * 2)
            if (latest) {
                verifyCurrentMapping(members)
                currentApis = members.mapTo(hashSetOf()) { it.entry.api }
            }
            latest = false
            if (pair.document.documentRevision == intent.anchorRevision) break
            val previous = checkNotNull(pair.document.previousDocumentRevision)
            check(previous >= intent.anchorRevision && previous == pair.manifest.previousCatalogRevision)
            val predecessor = readPair(previous)
            check(predecessor.document.checksum == pair.document.previousDocumentChecksum)
            check(predecessor.manifest.checksum == pair.manifest.previousCatalogChecksum)
            pair = predecessor
        }
        check(pair.document.checksum == intent.anchorDocumentChecksum && pair.manifest.checksum == intent.anchorCatalogChecksum)
        check(pair.manifest.previousCatalogRevision == null && pair.manifest.previousCatalogChecksum == null)
        check(catalogs.removedApis(intent.anchorRevision).isEmpty()) // First-v2 anchor has no earlier v2 members to remove.
        check(currentRemoved == historicalApis - currentApis)
        check(currentRemoved.all { sources.findByApi(it)?.status == SourceLifecycleStatus.REMOVED })
        // Pre-v2-only/never-v2 removed heads do not acquire invented v2 membership or tombstones.
        verifyPreV2Lineage(pair.document, budget)
        budget.requireCompleteHistory(documents.snapshotCount())
    }

    private fun readPair(revision: Long): StoredCatalogPair = StoredCatalogPair(
        checkNotNull(documents.findByRevision(revision)), checkNotNull(catalogs.findByRevision(revision)),
    )

    private fun verifyPair(pair: StoredCatalogPair, budget: ProofBudget): List<PublishedCatalogMember> {
        budget.bytes(pair.document.documentJson, MAX_DOCUMENT_BYTES)
        budget.bytes(pair.manifest.manifestJson, MAX_MANIFEST_BYTES)
        verifier.verify(pair, pair.document.documentRevision, null)
        check(pair.document.createdBy == pair.manifest.createdBy)
        val document = parsedDocument(pair.document)
        val manifest = SourceConfigParser.strictJson.decodeFromString(SourceCatalogManifest.serializer(), pair.manifest.manifestJson)
        check(CanonicalJson.canonicalize(SourceCatalogManifest.serializer(), manifest) == pair.manifest.manifestJson)
        check(manifest.schemaVersion == 1 && manifest.sourceSchemaVersion == 1 && manifest.catalogRevision == pair.manifest.catalogRevision)
        check(manifest.generatedAt == DateTimeFormatter.ISO_INSTANT.format(pair.manifest.createdAt))
        check(manifest.sources.size <= MAX_MEMBERS && manifest.sources.size == pair.manifest.sourceCount)
        check(manifest.sources.map { it.api }.distinct().size == manifest.sources.size)
        check(manifest.sources.size == document.sources.size)
        val members = catalogs.findMembers(manifest.catalogRevision, MAX_MEMBERS + 1)
        check(members.size == manifest.sources.size)
        members.forEachIndexed { index, member ->
            val entry = member.entry
            check(entry.order == index && entry.api.length in 1..128 && entry.lifecycle in LIFECYCLES)
            check(entry.toManifestEntry() == manifest.sources[index])
            budget.bytes(member.artifact.canonicalJson, MAX_SOURCE_BYTES)
            verifier.verifyMember(member)
            val source = SourceConfigParser.parseStrictSource(member.artifact.canonicalJson)
            check(SourceConfigParser.canonicalSource(source) == member.artifact.canonicalJson)
            check(source.api == entry.api && source.engine == "generic" && source.lifecycle == "active")
            val lifecycle = if (entry.lifecycle == "retired") "removed" else entry.lifecycle
            check(source.copy(lifecycle = lifecycle) == document.sources[index])
        }
        val removed = manifest.removedSources
        check(removed.size <= MAX_MEMBERS && removed.all { it.lifecycle == "removed" && it.api.length in 1..128 })
        val removedApis = removed.map { it.api }
        check(removedApis.distinct().size == removedApis.size && removedApis == removedApis.sorted())
        check(removedApis.toSet().intersect(manifest.sources.map { it.api }.toSet()).isEmpty())
        check(catalogs.removedApis(manifest.catalogRevision) == removedApis.toSet())
        return members
    }

    private fun parsedDocument(document: PublishedDocument) = SourceConfigParser.parseStrictDocument(document.documentJson).also { model ->
        check(SourceConfigParser.canonicalDocument(model) == document.documentJson)
        check(model.schemaVersion == document.schemaVersion && model.revision == document.documentRevision)
        check(model.generatedAt == DateTimeFormatter.ISO_INSTANT.format(document.createdAt))
        check(model.sources.size == document.sourceCount && model.sources.size <= MAX_MEMBERS)
    }

    private fun verifyCurrentMapping(members: List<PublishedCatalogMember>) {
        val current = sources.findSourcesForAssembly().sortedWith(compareBy({ it.position }, { it.api }))
        check(current.size == members.size)
        current.zip(members).forEach { (head, member) ->
            val entry = member.entry
            check(head.sourceConfigId == entry.sourceConfigId && head.sourceRevisionId == entry.sourceRevisionId)
            check(head.api == entry.api && head.revisionNumber == entry.sourceRevision && head.engine == entry.engine)
            check(head.status.wire == entry.lifecycle && head.checksum == member.artifact.checksum)
            check(head.canonVersion == member.artifact.canonVersion && head.canonicalContent == member.artifact.canonicalJson)
        }
    }

    private fun verifyPreV2Lineage(anchor: PublishedDocument, budget: ProofBudget) {
        var child = anchor
        while (child.previousDocumentRevision != null) {
            budget.document()
            val revision = checkNotNull(child.previousDocumentRevision)
            check(catalogs.findByRevision(revision) == null)
            val previous = checkNotNull(documents.findByRevision(revision))
            check(previous.checksum == child.previousDocumentChecksum)
            budget.bytes(previous.documentJson, MAX_DOCUMENT_BYTES)
            verifier.verifyRetainedDocument(previous)
            parsedDocument(previous)
            child = previous
        }
    }

    private fun me.manga.kira.backend.sourceconfig.domain.PublishedCatalogEntry.toManifestEntry() = SourceCatalogEntry(
        api, sourceRevision, checksum, order, lifecycle, engine, sourceSigningKeyId, sourceSignature,
    )

    private class ProofBudget {
        private var documents = 0
        private var remainingBytes = MAX_TOTAL_BYTES

        fun document() { check(++documents <= MAX_HISTORY) }

        fun requireCompleteHistory(retainedCount: Long) { check(retainedCount == documents.toLong()) }

        fun bytes(value: String, limit: Int) {
            check(value.length <= limit)
            val size = value.toByteArray(Charsets.UTF_8).size
            check(size <= limit && size <= remainingBytes)
            remainingBytes -= size
        }
    }

    private companion object {
        const val MAX_HISTORY = 256
        const val MAX_MEMBERS = 256
        const val MAX_DOCUMENT_BYTES = 5 * 1024 * 1024
        const val MAX_MANIFEST_BYTES = 1024 * 1024
        const val MAX_SOURCE_BYTES = 1024 * 1024
        const val MAX_TOTAL_BYTES = 32 * 1024 * 1024
        val LIFECYCLES = setOf("active", "disabled", "retired")
    }
}
