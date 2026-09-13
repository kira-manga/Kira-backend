package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser

/** Historical 45-source input checked against the pinned generic models, never silently repaired. */
object InitialSourceCatalogFixtures {
    fun approvedDocument(): SourceConfigDocument = approvedDocument(
        SourceConfigParser.parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-full.json")),
    )

    /** Pure test seam: in-memory drift probes must not mutate the committed raw fixture. */
    internal fun approvedDocument(historical: SourceConfigDocument): SourceConfigDocument {
        val reference = requireNotNull(
            InitialSourceCatalogFixtures::class.java.getResourceAsStream("/source-config/bootstrap/app-bundle-v6-generic.json"),
        ) { "missing pinned initial-catalog reference" }.use {
            SourceConfigParser.parseStrictDocument(it.readBytes().toString(Charsets.UTF_8))
        }
        check(historical.sources.count { it.engine == "legacy" } == 33)
        check(historical.sources.filter { it.engine == "generic" } == reference.sources) {
            "historical fixture generic content or order differs from the pinned reference"
        }
        // Retain reference schema/revision6/generatedAt and the historical sources without substitution.
        return reference.copy(sources = historical.sources)
    }

    /** Each call returns its own bytes so raw-body mutation tests cannot change another baseline. */
    fun approvedPayload(): ByteArray = SourceConfigParser.canonicalDocument(approvedDocument()).toByteArray(Charsets.UTF_8)

    /** Preserve the real four-stanza shapes while testing ordinary post-bootstrap *creation*. */
    fun postBootstrapTrimmedDocument(): SourceConfigDocument = SourceConfigParser
        .parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-trimmed.json"))
        .let { document -> document.copy(sources = document.sources.map { it.copy(api = "Imported ${it.api}") }) }
}
