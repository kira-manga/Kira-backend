package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.test.web.servlet.get

class SourceOperationalModeIT : AbstractAdminSourceIT() {
    @Test
    fun `enabled and disabled reuse working content while publishing lifecycle changes`() {
        val api = "LifecycleMode"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }

        val disabled =
            setOperationalMode(api, "disabled", issueStepUp()).andExpect {
                status { isOk() }
                jsonPath("$.sourceRevisionNumber") { value(1) }
            }
        assertEquals("disabled", servedDocument(docRevisionOf(disabled)).sources.single().lifecycle)
        assertEquals(1, revisionCount(api))

        val enabled =
            setOperationalMode(api, "enabled", issueStepUp()).andExpect {
                status { isOk() }
                jsonPath("$.sourceRevisionNumber") { value(1) }
            }
        assertEquals("active", servedDocument(docRevisionOf(enabled)).sources.single().lifecycle)
        assertEquals(1, revisionCount(api))
        assertEquals(3, snapshotCount())
    }

    @Test
    fun `three-state mode is protected idempotent and publishes one atomic catalog revision`() {
        val api = "ModeSource"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }

        setOperationalMode(api, "under_maintenance", proof = null).andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_REQUIRED") }
        }
        assertEquals(1, snapshotCount())
        assertEquals(1, revisionCount(api))

        val maintenance =
            setOperationalMode(api, "under_maintenance", issueStepUp()).andExpect {
                status { isOk() }
                jsonPath("$.api") { value(api) }
                jsonPath("$.mode") { value("under_maintenance") }
                jsonPath("$.sourceRevisionNumber") { value(2) }
                jsonPath("$.noOp") { value(false) }
            }
        val maintenanceRevision = docRevisionOf(maintenance)
        assertEquals(2, snapshotCount())
        assertEquals(2, revisionCount(api))
        assertEquals("active", sourceStatus(api))
        assertEquals("UNDER_MAINTENANCE", servedDocument(maintenanceRevision).sources.single().siteState)
        val maintenanceManifest = latestManifestEntry(api)
        assertEquals("active", maintenanceManifest.path("lifecycle").asText("active"))
        assertEquals(2, maintenanceManifest.path("sourceRevision").asInt())
        assertEquals("UNDER_MAINTENANCE", sourceArtifact(api, 2).path("siteState").asText())

        setOperationalMode(api, "under_maintenance", issueStepUp()).andExpect {
            status { isOk() }
            jsonPath("$.documentRevision") { value(maintenanceRevision) }
            jsonPath("$.sourceRevisionNumber") { value(2) }
            jsonPath("$.noOp") { value(true) }
        }
        assertEquals(2, snapshotCount(), "an identical request must not publish")
        assertEquals(2, revisionCount(api), "an identical request must not create source history")

        val disabled =
            setOperationalMode(api, "disabled", issueStepUp()).andExpect {
                status { isOk() }
                jsonPath("$.mode") { value("disabled") }
                jsonPath("$.sourceRevisionNumber") { value(3) }
            }
        val disabledDocument = servedDocument(docRevisionOf(disabled)).sources.single()
        assertEquals("disabled", disabledDocument.lifecycle)
        assertEquals("WORKING", disabledDocument.siteState)
        assertEquals("disabled", sourceStatus(api))
        assertEquals("disabled", latestManifestEntry(api).path("lifecycle").asText())

        val maintenanceAgain =
            setOperationalMode(api, "under_maintenance", issueStepUp()).andExpect {
                status { isOk() }
                jsonPath("$.sourceRevisionNumber") { value(4) }
            }
        assertEquals("active", sourceStatus(api))
        assertEquals(
            "UNDER_MAINTENANCE",
            servedDocument(docRevisionOf(maintenanceAgain)).sources.single().siteState,
        )

        val enabled =
            setOperationalMode(api, "enabled", issueStepUp()).andExpect {
                status { isOk() }
                jsonPath("$.sourceRevisionNumber") { value(5) }
            }
        val enabledDocument = servedDocument(docRevisionOf(enabled)).sources.single()
        assertEquals("active", enabledDocument.lifecycle)
        assertEquals("WORKING", enabledDocument.siteState)
        assertEquals(5, snapshotCount())
        assertEquals(5, revisionCount(api))

        mockMvc.get("/api/v1/admin/sources/$api") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$.siteState") { value("WORKING") }
            jsonPath("$.operationalMode") { value("enabled") }
        }
        mockMvc.get("/api/v1/admin/sources") {
            header("Authorization", "Bearer $adminToken")
        }.andExpect {
            status { isOk() }
            jsonPath("$[0].siteState") { value("WORKING") }
            jsonPath("$[0].operationalMode") { value("enabled") }
        }

        val modeAudits =
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM audit_log WHERE action = 'SOURCE_OPERATIONAL_MODE_CHANGED' AND entity_id = ?",
                Long::class.java,
                api,
            )!!
        assertEquals(4, modeAudits, "only actual transitions are audited")
    }

    @Test
    fun `proof is one time and invalid mode does not consume it`() {
        val api = "ProofSource"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        val proof = issueStepUp()

        setOperationalMode(api, "paused", proof).andExpect {
            status { isBadRequest() }
            jsonPath("$.errors[0].code") { value("INVALID_SOURCE_OPERATIONAL_MODE") }
        }
        setOperationalMode(api, "enabled", proof).andExpect {
            status { isOk() }
            jsonPath("$.noOp") { value(true) }
        }
        setOperationalMode(api, "disabled", proof).andExpect {
            status { isUnauthorized() }
            jsonPath("$.errors[0].code") { value("ADMIN_STEP_UP_REQUIRED") }
        }
    }

    @Test
    fun `states outside the quick control are rejected without publishing`() {
        for (siteState in listOf("STOPPED", "ADULT_18_PLUS")) {
            val api = "Mode${siteState.replace("_", "")}"
            createSource(SourceConfigFixtures.validGenericSource(api).copy(siteState = siteState)).andExpect {
                status { isCreated() }
            }
            publish(api, 1).andExpect { status { isOk() } }
            val snapshotsBefore = snapshotCount()

            setOperationalMode(api, "enabled", issueStepUp()).andExpect {
                status { isConflict() }
                jsonPath("$.errors[0].code") { value("SOURCE_OPERATIONAL_MODE_UNAVAILABLE") }
            }
            assertEquals(snapshotsBefore, snapshotCount())

            mockMvc.get("/api/v1/admin/sources/$api") {
                header("Authorization", "Bearer $adminToken")
            }.andExpect {
                status { isOk() }
                jsonPath("$.siteState") { value(siteState) }
                jsonPath("$.operationalMode") { doesNotExist() }
            }
        }

        val draftApi = "ModeDraft"
        createSource(SourceConfigFixtures.validGenericSource(draftApi)).andExpect { status { isCreated() } }
        val before = snapshotCount()
        setOperationalMode(draftApi, "disabled", issueStepUp()).andExpect {
            status { isConflict() }
            jsonPath("$.errors[0].code") { value("SOURCE_OPERATIONAL_MODE_UNAVAILABLE") }
        }
        assertEquals(before, snapshotCount())
        assertTrue(sourceStatus(draftApi) == "draft")
    }

    private fun latestManifestEntry(api: String) = objectMapper
        .readTree(
            mockMvc.get("/api/v2/source-config/manifest")
                .andExpect { status { isOk() } }
                .andReturn()
                .response
                .contentAsString,
        ).path("sources")
        .first { it.path("api").asText() == api }

    private fun sourceArtifact(api: String, revision: Int) = objectMapper.readTree(
        mockMvc.get("/api/v2/source-config/sources/$api/revisions/$revision")
            .andExpect { status { isOk() } }
            .andReturn()
            .response
            .contentAsString,
    )
}
