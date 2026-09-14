package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.config.KiraConfigProperties
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Raw data/default regression, independent of the bootstrap helper's returned metadata. */
class InitialSourceCatalogFixturesTest {
    @Test
    fun `raw historical fixture carries all revision6 generic models including Azora chapter opt in`() {
        val raw = rawDocument()
        val reference = referenceDocument()
        val generics = raw.sources.filter { it.engine == "generic" }
        val legacy = raw.sources.filter { it.engine == "legacy" }

        assertEquals(1, raw.schemaVersion)
        assertEquals(4L, raw.revision, "historical input provenance is not the current App floor")
        assertEquals(45, raw.sources.size)
        assertEquals(InitialSourceCatalogPolicy.EXPECTED_ALL, raw.sources.map { it.api }.toSet())
        assertEquals(12, generics.size)
        assertEquals(33, legacy.size)
        assertEquals(InitialSourceCatalogPolicy.LEGACY_APIS, legacy.map { it.api })
        assertEquals(
            InitialSourceCatalogPolicy.APPROVED_GENERIC_APIS + InitialSourceCatalogPolicy.LEGACY_APIS,
            raw.sources.map { it.api },
            "retain the historical fixture order, not an additional runtime policy",
        )
        assertEquals(InitialSourceCatalogPolicy.APPROVED_GENERIC_APIS, generics.map { it.api })
        assertEquals(reference.sources, generics, "compare every default-expanded field in relative source order")
        assertEquals("{itemUrl}&includeChapters=true", generics.single { it.api == "Azora" }.endpoints.getValue("details").url)
    }

    @Test
    fun `helper keeps reference metadata and the entire raw source list without substitution`() {
        val raw = rawDocument()
        val reference = referenceDocument()
        val approved = InitialSourceCatalogFixtures.approvedDocument()

        assertEquals(reference.copy(sources = raw.sources), approved)
        assertEquals(raw.sources, approved.sources, "all 45 models and their full order survive unchanged")
        assertEquals(6L, approved.revision)
        assertEquals(reference.generatedAt, approved.generatedAt)
        assertEquals(
            SourceConfigParser.canonicalDocument(reference.copy(sources = raw.sources)),
            InitialSourceCatalogFixtures.approvedPayload().toString(Charsets.UTF_8),
        )
    }

    @Test
    fun `helper refuses same roster non Azora model drift rather than repairing it`() {
        val raw = rawDocument()
        val drifted = raw.copy(sources = raw.sources.map { if (it.api == "Tapas") it.copy(displayName = "Changed Tapas") else it })

        assertEquals(raw.sources.map { it.api }, drifted.sources.map { it.api })
        val failure = assertThrows(IllegalStateException::class.java) { InitialSourceCatalogFixtures.approvedDocument(drifted) }
        assertEquals("historical fixture generic content or order differs from the pinned reference", failure.message)
    }

    @Test
    fun `unoverridden source defaults align with bundle6 and retain the server minimum100`() {
        val defaults = KiraConfigProperties()
        val reference = referenceDocument()

        assertEquals(6L, reference.revision)
        assertEquals(6L, defaults.bundledRevisionFloor)
        assertEquals(reference.revision, defaults.bundledRevisionFloor)
        assertEquals(100L, defaults.minimumServerRevision)
        assertTrue(defaults.minimumServerRevision > defaults.bundledRevisionFloor)
    }

    private fun rawDocument(): SourceConfigDocument = SourceConfigParser.parseStrictDocument(SourceConfigFixtures.loadFixture("bundled-full.json"))

    private fun referenceDocument(): SourceConfigDocument {
        val bytes = requireNotNull(javaClass.getResourceAsStream("/source-config/bootstrap/app-bundle-v6-generic.json"))
            .use { it.readBytes() }
        assertEquals(InitialSourceCatalogPolicy.REFERENCE_SHA256, Sha256.hex(bytes))
        return SourceConfigParser.parseStrictDocument(bytes.toString(Charsets.UTF_8))
    }
}
