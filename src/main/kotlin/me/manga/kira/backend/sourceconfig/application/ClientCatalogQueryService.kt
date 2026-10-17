package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.config.KiraClientCatalogProperties
import me.manga.kira.backend.config.KiraClientCatalogProperties.SnapshotPin
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

/** Client-only reads. Existing admin/latest queries and immutable source-artifact reads remain independent. */
@Service
@Lazy(false)
class ClientCatalogQueryService(
    config: KiraClientCatalogProperties,
    private val documents: PublishedDocumentRepository,
    private val catalogs: PublishedSourceCatalogRepository,
    private val verifier: StoredCatalogPairVerifier,
) {
    private val selection = ClientCatalogSelection(config)

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun documentForClient(appVersions: List<String>?): PublishedDocument? {
        if (selection.enabled) return selectedPair(appVersions).document
        validateLegacyVersion(appVersions)
        return documents.latestPointer()?.let(documents::findByRevision)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun documentMetaForClient(appVersions: List<String>?): PublishedDocument? = if (selection.enabled) {
        selectedPair(appVersions).document
    } else {
        documents.latestPointer()?.let(documents::findByRevision)
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun manifestForClient(appVersions: List<String>?): PublishedSourceCatalog? = if (selection.enabled) {
        selectedPair(appVersions).manifest
    } else {
        documents.latestPointer()?.let(catalogs::findByRevision)
    }

    /** Existing startup runner invokes this through the transaction proxy; reads repeat pointer/selected-pair checks. */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun validateStartup() {
        if (!selection.enabled) return
        guarded {
            val current = checkedCurrent()
            selection.pins.filter { it.revision != current.document.documentRevision }.forEach { readPair(it.revision, it) }
        }
    }

    private fun selectedPair(appVersions: List<String>?): StoredCatalogPair = guarded {
        val current = checkedCurrent()
        val pin = selection.pinFor(appVersions)
        if (pin == null || pin.revision == current.document.documentRevision) current else readPair(pin.revision, pin)
    }

    private fun checkedCurrent(): StoredCatalogPair {
        val pointer = checkNotNull(documents.latestPointer())
        check(pointer > 0 && selection.pins.all { it.revision <= pointer })
        return readPair(pointer, selection.pins.firstOrNull { it.revision == pointer })
    }

    private fun readPair(revision: Long, pin: SnapshotPin?): StoredCatalogPair {
        val pair = StoredCatalogPair(checkNotNull(documents.findByRevision(revision)), checkNotNull(catalogs.findByRevision(revision)))
        verifier.verify(pair, revision, pin)
        return pair
    }

    private fun <T> guarded(read: () -> T): T = try {
        read()
    } catch (_: Exception) {
        // No submitted version, stored bytes, key material or provider/DB cause crosses this boundary.
        throw ServiceUnavailableException("signed client catalog is unavailable.", code = "CLIENT_CATALOG_UNAVAILABLE")
    }

    private fun validateLegacyVersion(values: List<String>?) {
        if (values == null) return
        val value = values.singleOrNull()
        if (value == null || value.length > 64 || !LEGACY_VERSION.matches(value)) {
            throw BadRequestException("the 'appVersion' query parameter is not a valid version string.", code = "INVALID_APP_VERSION")
        }
    }

    private companion object {
        val LEGACY_VERSION = Regex("""\d+(\.\d+){0,3}([-+][A-Za-z0-9.-]+)?""")
    }
}

/** Bounded, immutable configuration projection; equality is exact, never a semantic-version range. */
private class ClientCatalogSelection(config: KiraClientCatalogProperties) {
    val enabled = config.enabled
    private val baseline: SnapshotPin?
    private val currentVersions: Set<String>
    private val pinnedVersions: Map<String, SnapshotPin>
    val pins: List<SnapshotPin>

    init {
        if (enabled) {
            valid(config.currentVersions.size <= MAX_VERSIONS && config.pinnedVersions.size <= MAX_VERSIONS)
            valid(config.currentVersions.size + config.pinnedVersions.size <= MAX_VERSIONS)
            val baselinePin = checkNotNull(config.baseline) { CONFIGURATION_ERROR }.copy()
            baseline = baselinePin
            val names = config.currentVersions + config.pinnedVersions.map { it.appVersion }
            valid(names.all { it.length <= 64 && VERSION.matches(it) } && names.distinct().size == names.size)
            currentVersions = config.currentVersions.toSet()
            pinnedVersions = config.pinnedVersions.associate { it.appVersion to it.snapshot.copy() }
            val selectedPins = listOf(baselinePin) + pinnedVersions.values
            valid(selectedPins.all { it.revision > 0 && HASH.matches(it.documentChecksum) && HASH.matches(it.manifestChecksum) })
            valid(selectedPins.all { it.revision >= baselinePin.revision })
            valid(selectedPins.groupBy { it.revision }.values.all { it.distinct().size == 1 })
            pins = selectedPins.distinct().sortedBy { it.revision }
        } else {
            baseline = null
            currentVersions = emptySet()
            pinnedVersions = emptyMap()
            pins = emptyList()
        }
    }

    /** Null means the authoritative current pointer; every non-admitted raw query falls back to the explicit baseline. */
    fun pinFor(values: List<String>?): SnapshotPin? {
        val version = values?.singleOrNull()?.takeIf { it.length <= 64 && VERSION.matches(it) }
        if (version == null) return checkNotNull(baseline)
        return if (version in currentVersions) null else pinnedVersions[version] ?: checkNotNull(baseline)
    }

    private fun valid(condition: Boolean) = check(condition) { CONFIGURATION_ERROR }

    private companion object {
        const val MAX_VERSIONS = 32
        const val CONFIGURATION_ERROR = "client catalog delivery configuration is invalid; see docs/SOURCE_CONFIG_LIFECYCLE.md."
        val VERSION = Regex("""(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)""")
        val HASH = Regex("[0-9a-f]{64}")
    }
}
