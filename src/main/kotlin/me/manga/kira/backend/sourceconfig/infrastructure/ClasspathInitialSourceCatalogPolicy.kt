package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.domain.AssemblySource
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogAdmission
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.APPROVED_GENERIC_APIS
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.EXPECTED_ALL
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.GENERIC_ENGINE
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.POLICY_ID
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.REFERENCE_SHA256
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicyRejected
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component
import java.io.IOException

/**
 * The only reference is this backend's hash-pinned main resource, not a sibling checkout or fixture.
 * Its source is App2af1178734ae8d6bac35f588332aa30704a1d84d's BundledSourcesConfig.kt, unchanged at
 * App4b4f9539bce70e7179385ce61ab035282bc5ac75; source SHA-256:
 * d4cc1de96901ced254170d594e7e7f33bb6002fa64ac9c15135cbacd9f0f5702.
 * The ordered12 projection is source-data provenance, not released-binary attestation or kcj-1.
 * No reference read or parse occurs during bean construction: old receipt replay must survive a
 * missing, unsupported or changed current reference. The transactional caller owns the PENDING gate.
 */
@Component
class ClasspathInitialSourceCatalogPolicy internal constructor(private val readReference: () -> ByteArray?) : InitialSourceCatalogPolicy {
    @Autowired
    constructor() : this(
        readReference = {
            ClasspathInitialSourceCatalogPolicy::class.java.getResourceAsStream(REFERENCE_PATH)?.use {
                it.readNBytes(REFERENCE_BYTES + 1)
            }
        },
    )

    private val reference: SourceConfigDocument by lazy { loadReference() }

    override fun admitPayload(rawJson: String): InitialSourceCatalogAdmission {
        val expected = reference
        val document = SourceConfigParser.parseStrictDocument(rawJson)
        requirePolicy(document.schemaVersion == SCHEMA_VERSION, "initial source schema is unsupported")
        requireInventoryApis(document.sources.map { it.api })
        requirePolicy(
            document.sources.filter { it.engine == GENERIC_ENGINE } == expected.sources,
            "initial generic content, lifecycle, priority or order differs from the approved reference",
        )
        return InitialSourceCatalogAdmission(document, POLICY_ID, REFERENCE_SHA256)
    }

    override fun requireStagedInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>) {
        requireInventory(heads, assemblySources, setOf(SourceLifecycleStatus.ACTIVE, SourceLifecycleStatus.WITHHELD))
    }

    override fun requirePublicationInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>) {
        requireInventory(heads, assemblySources, setOf(SourceLifecycleStatus.WITHHELD))
    }

    private fun requireInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>, legacyStatuses: Set<SourceLifecycleStatus>) {
        val expected = reference
        requireInventoryApis(heads.map { it.api })
        heads.forEach { head ->
            requirePolicy(head.currentPublishedRevisionId != null, "initial source head has no published revision")
            if (head.api in APPROVED_GENERIC_APIS) {
                requirePolicy(head.engine == GENERIC_ENGINE, "initial generic source head has a different engine")
                requirePolicy(head.status == SourceLifecycleStatus.ACTIVE, "initial generic source head is not active")
            } else {
                requirePolicy(head.engine != GENERIC_ENGINE, "initial legacy source head has a generic engine")
                requirePolicy(head.status in legacyStatuses, "initial legacy source head has an unsupported lifecycle")
            }
        }

        val ordered = assemblySources.sortedWith(compareBy({ it.position }, { it.api }))
        requirePolicy(ordered.map { it.api } == APPROVED_GENERIC_APIS, "effective initial generic inventory or order differs")
        val byApi = heads.associateBy { it.api }
        ordered.forEach { source -> requireMatchingHead(source, byApi.getValue(source.api)) }

        // Keep every raw field, including stored lifecycle and priority. Only the app's activated
        // view projects manifest lifecycle/order; that projection cannot hide drift in these bytes.
        val actual = ordered.map { parseStoredSource(it.canonicalContent) }
        requirePolicy(actual == expected.sources, "effective initial generic content differs from the approved reference")
    }

    private fun parseStoredSource(rawJson: String): SourceConfig = try {
        SourceConfigParser.parseStrictSource(rawJson)
    } catch (ex: BadRequestException) {
        throw InitialSourceCatalogPolicyRejected("effective initial generic content is malformed", ex)
    }

    private fun requireMatchingHead(source: AssemblySource, head: SourceConfigHead) {
        requirePolicy(
            source.sourceConfigId == head.id && source.sourceRevisionId == head.currentPublishedRevisionId,
            "effective initial generic publication does not match its head",
        )
        requirePolicy(source.position == head.position, "effective initial generic position does not match its head")
        requirePolicy(source.engine == head.engine, "effective initial generic engine does not match its head")
        requirePolicy(source.status == head.status, "effective initial generic lifecycle does not match its head")
    }

    private fun requireInventoryApis(apis: List<String>) {
        requirePolicy(
            apis.size == EXPECTED_ALL.size && apis.toSet() == EXPECTED_ALL,
            "initial inventory must contain exactly the reviewed 45 unique source APIs",
        )
    }

    private fun loadReference(): SourceConfigDocument {
        val bytes = try {
            readReference()
        } catch (ex: IOException) {
            throw IllegalStateException(INVALID_REFERENCE, ex)
        }
        checkNotNull(bytes) { INVALID_REFERENCE }
        check(bytes.size == REFERENCE_BYTES && Sha256.hex(bytes) == REFERENCE_SHA256) { INVALID_REFERENCE }
        // The approved digest binds the exact valid UTF-8 bytes before any decoding/default expansion.
        val document = try {
            SourceConfigParser.parseStrictDocument(bytes.toString(Charsets.UTF_8))
        } catch (ex: BadRequestException) {
            throw IllegalStateException(INVALID_REFERENCE, ex)
        }
        check(document.schemaVersion == SCHEMA_VERSION && document.revision == REFERENCE_REVISION) { INVALID_REFERENCE }
        check(document.generatedAt == null && document.sources.map { it.api } == APPROVED_GENERIC_APIS) { INVALID_REFERENCE }
        check(document.sources.all { it.engine == GENERIC_ENGINE && it.lifecycle == "active" }) { INVALID_REFERENCE }
        return document
    }

    private fun requirePolicy(condition: Boolean, detail: String) {
        if (!condition) throw InitialSourceCatalogPolicyRejected(detail)
    }

    private companion object {
        const val REFERENCE_PATH = "/source-config/bootstrap/app-bundle-v6-generic.json"
        const val REFERENCE_BYTES = 30_742
        const val REFERENCE_REVISION = 6L
        const val SCHEMA_VERSION = 1
        const val INVALID_REFERENCE = "Initial source catalog reference is unavailable or invalid"
    }
}
