package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.application.DocumentAssemblyService
import me.manga.kira.backend.sourceconfig.domain.AssemblySource
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * PLAN §11 test 40 — `DocumentOrderDeterminismIT`: one normative source order. The same source set fed
 * to the assembly in multiple shuffled orders produces byte-identical canonical documents (identical
 * checksum/ETag); the assembled order is `(position ASC, api ASC)` (PLAN §5 source ordering — kcj-1 key
 * sorting never reorders the sources array). The Phase-8 half proves a bundled import assigns positions
 * from payload order so the served document preserves the bundled stanza order end-to-end (§5/§12.2).
 */
class DocumentOrderDeterminismIT : AbstractAdminSourceIT() {

    @Autowired
    private lateinit var assembly: DocumentAssemblyService

    private fun assemblySource(
        position: Int,
        api: String,
    ): AssemblySource =
        AssemblySource(
            api = api,
            position = position,
            engine = "generic",
            status = SourceLifecycleStatus.ACTIVE,
            canonicalContent = SourceConfigParser.canonicalSource(SourceConfigFixtures.validGenericSource(api)),
        )

    @Test
    fun `shuffled repository orders assemble to byte-identical canonical documents`() {
        // Positions and apis chosen so (position ASC, api ASC) differs from any insertion order, and so
        // the api tiebreak is exercised (two sources share position 0).
        val sources =
            listOf(
                assemblySource(2, "Zeta"),
                assemblySource(0, "Beta"),
                assemblySource(0, "Alpha"),
                assemblySource(1, "Gamma"),
            )
        val expectedOrder = listOf("Alpha", "Beta", "Gamma", "Zeta")

        val generatedAt = "2026-07-12T00:00:00Z"
        val revision = 123L
        val canonicalForms =
            listOf(
                sources,
                sources.reversed(),
                sources.shuffled(java.util.Random(1)),
                sources.shuffled(java.util.Random(42)),
            ).map { shuffled ->
                val document = assembly.buildDocument(shuffled, generatedAt, revision)
                assertEquals(expectedOrder, document.sources.map { it.api }, "assembled order must be (position, api)")
                SourceConfigParser.canonicalDocument(document)
            }

        val first = canonicalForms.first()
        canonicalForms.forEach { assertEquals(first, it, "every shuffle must canonicalize identically") }
        // And identical checksums (the ETag) follow from identical bytes.
        val checksums = canonicalForms.map { CanonicalJson.checksum(it) }.toSet()
        assertEquals(1, checksums.size, "all shuffles must share one checksum")
    }

    @Test
    fun `a bundled import serves stanzas in payload order`() {
        // Positions are assigned from payload order on create (PLAN §5(c)/§12.2), so the served document
        // preserves the exact bundled stanza order — the app's de-facto tab order.
        importBundled(SourceConfigFixtures.loadFixture("bundled-trimmed.json")).andExpect { status { isOk() } }
        assertEquals(
            listOf("Azora", "SwatManga", "Lavatoons", "Manhwatop"),
            publicServedDocument().sources.map { it.api },
            "served document must preserve the bundled payload order",
        )
    }
}
