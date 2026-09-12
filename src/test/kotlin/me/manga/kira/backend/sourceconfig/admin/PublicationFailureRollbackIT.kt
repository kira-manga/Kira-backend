package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** A failure at the final snapshot write must roll back every earlier publish mutation. */
class PublicationFailureRollbackIT : AbstractAdminSourceIT() {
    override val bootstrapCatalogBeforeEach: Boolean = true

    @Test
    fun `interrupted snapshot publication leaves no partial state`() {
        val api = "InterruptedPublish"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        val before = publicState()
        jdbcTemplate.execute(
            """
            CREATE OR REPLACE FUNCTION fail_snapshot_insert() RETURNS trigger AS ${'$'}${'$'}
            BEGIN
              RAISE EXCEPTION 'injected snapshot failure';
            END;
            ${'$'}${'$'} LANGUAGE plpgsql
            """.trimIndent(),
        )
        jdbcTemplate.execute(
            "CREATE TRIGGER fail_snapshot_insert_trigger BEFORE INSERT ON published_documents " +
                "FOR EACH ROW EXECUTE FUNCTION fail_snapshot_insert()",
        )
        try {
            publish(api, 1).andExpect { status { is5xxServerError() } }

            assertEquals("draft", sourceStatus(api))
            assertPublicStateUnchanged(before)
            assertEquals(
                "draft",
                jdbcTemplate.queryForObject(
                    "SELECT status FROM source_config_revisions WHERE source_config_id = " +
                        "(SELECT id FROM source_configs WHERE api = ?)",
                    String::class.java,
                    api,
                ),
            )
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS fail_snapshot_insert_trigger ON published_documents")
            jdbcTemplate.execute("DROP FUNCTION IF EXISTS fail_snapshot_insert()")
        }
    }
}
