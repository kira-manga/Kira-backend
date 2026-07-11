package me.manga.kira.backend.sourceconfig

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.domain.NewRevision
import me.manga.kira.backend.sourceconfig.domain.NewSourceConfig
import me.manga.kira.backend.sourceconfig.domain.RevisionRepository
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceConfigRepository
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import me.manga.kira.backend.support.AbstractIntegrationTest
import me.manga.kira.backend.user.domain.Role
import me.manga.kira.backend.user.domain.UserRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

/**
 * PLAN §11 test 33 — `HistoricalRevisionChecksumIT`: *stored canonical bytes are the reproducible
 * source of truth*. Persist a revision, reload `config_canonical_json` **cold** (a raw JDBC read, not
 * the persistence-context cache), recompute SHA-256 → it equals the stored checksum byte-for-byte
 * (PLAN §5). Exercises the Phase-5 persistence stack (source-config + revision adapters).
 */
class HistoricalRevisionChecksumIT : AbstractIntegrationTest() {

    @Autowired
    private lateinit var sources: SourceConfigRepository

    @Autowired
    private lateinit var revisions: RevisionRepository

    @Autowired
    private lateinit var users: UserRepository

    @Test
    fun `stored canonical bytes reproduce the checksum after a cold reload`() {
        val admin = users.create("checksum-it@test.local", "{noop}not-a-real-hash", Role.ADMIN)

        val source =
            sources.create(
                NewSourceConfig(
                    api = "Azora",
                    displayName = "Azora",
                    language = "ar",
                    engine = "generic",
                    status = SourceLifecycleStatus.DRAFT,
                    position = 0,
                    baseUrl = "https://azoramoon.co",
                    adult = false,
                ),
            )

        // Canonicalize a real-shaped stanza and checksum it from the SAME parsed model (PLAN §5).
        val model = SourceConfigFixtures.validGenericSource(api = "Azora")
        val canonical = SourceConfigParser.canonicalSource(model)
        val checksum = CanonicalJson.checksum(canonical)

        val created =
            revisions.create(
                NewRevision(
                    sourceConfigId = source.id,
                    revisionNumber = 1,
                    configCanonicalJson = canonical,
                    checksum = checksum,
                    canonVersion = CanonicalJson.CANON_VERSION,
                    status = RevisionStatus.DRAFT,
                    createdBy = admin.id,
                    notes = null,
                ),
            )

        // Cold reload: raw JDBC straight from the DB (bypasses the JPA first-level cache).
        val coldJson =
            jdbcTemplate.queryForObject(
                "SELECT config_canonical_json FROM source_config_revisions WHERE id = ?",
                String::class.java,
                created.id,
            )
        val coldChecksum =
            jdbcTemplate.queryForObject(
                "SELECT checksum FROM source_config_revisions WHERE id = ?",
                String::class.java,
                created.id,
            )

        assertEquals(canonical, coldJson, "stored canonical bytes must match what was written")
        assertEquals(checksum, coldChecksum, "stored checksum column must match the computed checksum")
        assertEquals(checksum, Sha256.hexUtf8(coldJson!!), "SHA-256 of the cold-read bytes must reproduce the checksum")
        assertEquals(CanonicalJson.CANON_VERSION, "kcj-1")

        // The port read reconstructs the same immutable content.
        val viaPort = revisions.findBySourceAndNumber(source.id, 1)
        assertEquals(canonical, viaPort?.configCanonicalJson)
        assertEquals(checksum, viaPort?.checksum)
    }
}
