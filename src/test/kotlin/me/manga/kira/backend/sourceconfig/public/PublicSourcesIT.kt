package me.manga.kira.backend.sourceconfig.public

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import me.manga.kira.backend.sourceconfig.domain.model.IconSpec
import org.junit.jupiter.api.Test

/**
 * Public list + by-api coverage of PLAN §4.1: `GET /sources` (summary fields, document order, the
 * `lifecycle`/`engine` filters incl. retired-as-removed, unknown-value 400, draft-only absent,
 * no-document-yet → `[]`) and `GET /sources/{api}` (active/disabled/retired 200 with the right
 * lifecycle shape, removed → 410, unknown/draft-only → 404), plus the `/document` `appVersion`
 * validation and `/document/meta` shape/404.
 */
class PublicSourcesIT : AbstractAdminSourceIT() {

    private fun publishNew(
        api: String,
        mutate: (me.manga.kira.backend.sourceconfig.domain.model.SourceConfig) -> me.manga.kira.backend.sourceconfig.domain.model.SourceConfig = { it },
    ) {
        val base = SourceConfigFixtures.validGenericSource(api)
        createSource(mutate(base)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
    }

    // --- GET /sources -------------------------------------------------------------------------------

    @Test
    fun `a single source summary exposes every field`() {
        publishNew("Fields") {
            it.copy(
                displayName = "Fields Display",
                language = "ar",
                siteState = "ADULT_18_PLUS",
                icon = IconSpec(remoteUrl = "https://cdn.example.com/fields.png"),
            )
        }
        getPublicSources().andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].api") { value("Fields") }
            jsonPath("$[0].displayName") { value("Fields Display") }
            jsonPath("$[0].language") { value("ar") }
            jsonPath("$[0].engine") { value("generic") }
            jsonPath("$[0].lifecycle") { value("active") }
            jsonPath("$[0].siteState") { value("ADULT_18_PLUS") }
            jsonPath("$[0].adult") { value(true) }
            jsonPath("$[0].baseUrl") { value("https://example.com") }
            jsonPath("$[0].iconRemoteUrl") { value("https://cdn.example.com/fields.png") }
            jsonPath("$[0].revisionNumber") { value(1) }
            jsonPath("$[0].publishedAt") { exists() }
        }
    }

    @Test
    fun `the list is ordered by document position not by api`() {
        // Created Zeta first (position 0) then Alpha (position 1) — document order is by position, so
        // the alphabetically-later "Zeta" must come FIRST.
        publishNew("Zeta")
        publishNew("Alpha")
        getPublicSources().andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].api") { value("Zeta") }
            jsonPath("$[1].api") { value("Alpha") }
        }
    }

    @Test
    fun `the lifecycle filter maps retired to removed and filters correctly`() {
        publishNew("Act") // active
        publishNew("Dis").also { disable("Dis").andExpect { status { isOk() } } } // disabled
        publishNew("Ret").also {
            disable("Ret").andExpect { status { isOk() } }
            retire("Ret").andExpect { status { isOk() } } // retired → served as "removed"
        }

        getPublicSources("lifecycle" to "active").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].api") { value("Act") }
        }
        getPublicSources("lifecycle" to "disabled").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].api") { value("Dis") }
        }
        getPublicSources("lifecycle" to "removed").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].api") { value("Ret") }
        }
        getPublicSources("lifecycle" to "disabled,removed").andExpect {
            jsonPath("$.length()") { value(2) }
        }
        getPublicSources().andExpect { jsonPath("$.length()") { value(3) } }
    }

    @Test
    fun `legacy sources are never exposed even through the engine filter`() {
        publishNew("Gen")
        importBundled(
            me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument(
                schemaVersion = 1,
                sources = listOf(SourceConfigFixtures.validLegacySource("Leg")),
            ),
        ).andExpect { status { isOk() } }
        getPublicSources("engine" to "generic").andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].api") { value("Gen") }
        }
        getPublicSources("engine" to "legacy").andExpect {
            jsonPath("$.length()") { value(0) }
        }
        getPublicSource("Leg").andExpect { status { isNotFound() } }
    }

    @Test
    fun `an unknown lifecycle or engine filter value is 400`() {
        publishNew("Any")
        getPublicSources("lifecycle" to "bogus").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("INVALID_LIFECYCLE_FILTER") }
        }
        getPublicSources("engine" to "bogus").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("INVALID_ENGINE_FILTER") }
        }
    }

    @Test
    fun `a draft-only source never appears in the list`() {
        // A draft (never published) plus a published source: only the published one is listed.
        createSource(SourceConfigFixtures.validGenericSource("Draft")).andExpect { status { isCreated() } }
        publishNew("Pub")
        getPublicSources().andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].api") { value("Pub") }
        }
    }

    @Test
    fun `no document ever published yields an empty array`() {
        getPublicSources().andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(0) }
        }
    }

    // --- GET /sources/{api} -------------------------------------------------------------------------

    @Test
    fun `by-api active serves the stanza with no lifecycle key`() {
        publishNew("ActiveOne")
        getPublicSource("ActiveOne").andExpect {
            status { isOk() }
            header { string("Content-Type", "application/json;charset=UTF-8") }
            jsonPath("$.api") { value("ActiveOne") }
            jsonPath("$.lifecycle") { doesNotExist() }
        }
    }

    @Test
    fun `by-api disabled and retired carry the app lifecycle value`() {
        publishNew("DisOne").also { disable("DisOne").andExpect { status { isOk() } } }
        getPublicSource("DisOne").andExpect {
            status { isOk() }
            jsonPath("$.lifecycle") { value("disabled") }
        }

        publishNew("RetOne").also {
            disable("RetOne").andExpect { status { isOk() } }
            retire("RetOne").andExpect { status { isOk() } }
        }
        getPublicSource("RetOne").andExpect {
            status { isOk() }
            jsonPath("$.lifecycle") { value("removed") }
        }
    }

    @Test
    fun `by-api removed is 410 and unknown or draft-only is 404`() {
        publishNew("Gone").also {
            disable("Gone").andExpect { status { isOk() } }
            retire("Gone").andExpect { status { isOk() } }
            remove("Gone").andExpect { status { isOk() } }
        }
        getPublicSource("Gone").andExpect {
            status { isGone() }
            jsonPath("$.errors[0].code") { value("SOURCE_REMOVED") }
        }

        getPublicSource("NoSuchSource").andExpect {
            status { isNotFound() }
            jsonPath("$.errors[0].code") { value("SOURCE_NOT_FOUND") }
        }

        createSource(SourceConfigFixtures.validGenericSource("DraftOnly")).andExpect { status { isCreated() } }
        getPublicSource("DraftOnly").andExpect {
            status { isNotFound() }
            jsonPath("$.errors[0].code") { value("SOURCE_NOT_FOUND") }
        }
    }

    // --- /document appVersion + /document/meta ------------------------------------------------------

    @Test
    fun `document appVersion is validated`() {
        publishNew("Av")
        getPublicDocument(appVersion = "1.2.3").andExpect { status { isOk() } }
        getPublicDocument(appVersion = "1").andExpect { status { isOk() } }
        // A single '-' or '+' suffix of [A-Za-z0-9.-] is accepted (PLAN §4.1 pattern).
        getPublicDocument(appVersion = "1.2.3-rc.1").andExpect { status { isOk() } }
        getPublicDocument(appVersion = "1.2.3.4+build.9").andExpect { status { isOk() } }
        getPublicDocument(appVersion = "not-a-version").andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("INVALID_APP_VERSION") }
        }
        getPublicDocument(appVersion = "9".repeat(65)).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("INVALID_APP_VERSION") }
        }
    }

    @Test
    fun `document meta reports the latest shape and 404 when none`() {
        getPublicDocumentMeta().andExpect {
            status { isNotFound() }
            jsonPath("$.errors[0].code") { value("NO_PUBLISHED_DOCUMENT") }
        }

        publishNew("Meta")
        val pointer = latestPointer()!!
        val checksum =
            jdbcTemplate.queryForObject(
                "SELECT checksum FROM published_documents WHERE document_revision = ?",
                String::class.java,
                pointer,
            )!!
        getPublicDocumentMeta().andExpect {
            status { isOk() }
            header { string("Cache-Control", "public, max-age=300, no-transform") }
            header { string("X-Content-Type-Options", "nosniff") }
            jsonPath("$.revision") { value(pointer) }
            jsonPath("$.schemaVersion") { value(1) }
            jsonPath("$.checksum") { value(checksum) }
            jsonPath("$.publishedAt") { exists() }
        }
    }

    @Test
    fun `document 404 when nothing is published`() {
        getPublicDocument().andExpect {
            status { isNotFound() }
            jsonPath("$.errors[0].code") { value("NO_PUBLISHED_DOCUMENT") }
        }
    }
}
