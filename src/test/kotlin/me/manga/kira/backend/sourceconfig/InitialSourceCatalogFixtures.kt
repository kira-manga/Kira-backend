package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser

/** Real reviewed legacy input plus the complete pinned generic models, not a synthetic bootstrap. */
object InitialSourceCatalogFixtures {
    fun approvedDocument(): SourceConfigDocument {
        val historical = SourceConfigParser.parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-full.json"))
        val reference = requireNotNull(
            InitialSourceCatalogFixtures::class.java.getResourceAsStream("/source-config/bootstrap/app-bundle-v6-generic.json"),
        ) { "missing pinned initial-catalog reference" }.use {
            SourceConfigParser.parseStrictDocument(it.readBytes().toString(Charsets.UTF_8))
        }
        val generics = reference.sources.associateBy { it.api }
        check(historical.sources.count { it.engine == "legacy" } == 33)
        check(historical.sources.filter { it.engine == "generic" }.map { it.api } == reference.sources.map { it.api })
        return reference.copy(sources = historical.sources.map { generics[it.api] ?: it })
    }

    /** Each call returns its own bytes so raw-body mutation tests cannot change another baseline. */
    fun approvedPayload(): ByteArray = SourceConfigParser.canonicalDocument(approvedDocument()).toByteArray(Charsets.UTF_8)

    /** Preserve the real four-stanza shapes while testing ordinary post-bootstrap *creation*. */
    fun postBootstrapTrimmedDocument(): SourceConfigDocument = SourceConfigParser
        .parseCompatibleDocument(SourceConfigFixtures.loadFixture("bundled-trimmed.json"))
        .let { document -> document.copy(sources = document.sources.map { it.copy(api = "Imported ${it.api}") }) }
}
