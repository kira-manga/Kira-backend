package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.UUID

/** Representative storage queries, not yet the W05 HTTP/repository implementation. */
class ComplaintContentQueryIT : ComplaintPostgresTest() {
    private lateinit var schema: ComplaintSchema
    private val fixture = ComplaintContentQueryFixture()
    private val visibleLive get() = fixture.rows.filter { it.visible && it.scope == QUERY_LIVE }.updatedOrder()

    @BeforeAll
    fun seedQueryRows() {
        schema = database.createSchema()
        schema.flyway().migrate()
        schema.connection().use { connection ->
            connection.autoCommit = false
            fixture.seed(connection)
            connection.commit()
        }
    }

    @AfterAll
    fun removeQuerySchema() {
        if (::schema.isInitialized) schema.close()
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan", "auto"])
    fun `owner pages retain ties omit pending identities and isolate test scope`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            session.query("owner_first", "$OWNER_SELECT ORDER BY c.created_at DESC,c.id DESC LIMIT ?::integer") { first ->
                session.query(
                    "owner_next",
                    "$OWNER_SELECT AND (c.created_at,c.id)<(?::timestamptz,?::uuid) " +
                        "ORDER BY c.created_at DESC,c.id DESC LIMIT ?::integer",
                ) { next ->
                    for ((owner, scope, limit) in listOf(
                        Triple(1, QUERY_LIVE, 50),
                        Triple(1, QUERY_LIVE, 25),
                        Triple(1, QUERY_LIVE, 49),
                        Triple(256, QUERY_LIVE, 50),
                        Triple(1, QUERY_TEST, 50),
                        Triple(257, QUERY_TEST, 50),
                    )) {
                        val expected = fixture.rows.filter { it.visible && it.owner == owner && it.scope == scope }.createdOrder()
                        val args = listOf(fixtureUuid(0x701, owner), scope)
                        val indexes = setOf("idx_complaints_owner_page")
                        first.verify(args + limit, expected.keys(limit), indexes)
                        for (offset in expected.indices.step(limit).drop(1) + listOf(expected.size).filter { it > 0 }) {
                            val cursor = expected[offset - 1]
                            next.verify(args + listOf(cursor.created, cursor.id, limit), expected.drop(offset).keys(limit), indexes)
                        }
                        assertEquals(expected.size, expected.map { it.id }.distinct().size)
                    }
                }
            }
            session.query(
                "owner_capacity",
                "SELECT count(*) FROM complaints WHERE owner_id=?::uuid AND data_scope_id=?::uuid " +
                    "AND kind IN ('REPORT','REPLY')",
            ) { query ->
                query.verify(listOf(fixtureUuid(0x701, 1), QUERY_LIVE), listOf("80"), setOf("idx_complaints_owner_page"))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan"])
    fun `time-only owner cursor loses the known equal-time boundary witness`(mode: String) {
        val expected = fixture.rows.filter { it.visible && it.owner == 1 && it.scope == QUERY_LIVE }.createdOrder()
        val cursor = expected[48]
        val witness = expected[49]
        assertEquals(cursor.created, witness.created)
        assertNotEquals(cursor.id, witness.id)
        val incomplete = expected.filter { it.created < cursor.created }
        assertTrue(incomplete.none { it.id == witness.id })
        assertNotEquals(expected.drop(49).keys(), incomplete.keys())
        ComplaintPreparedSession(database, schema, mode).use { session ->
            session.query(
                "owner_time_only_mutant",
                "$OWNER_SELECT AND c.created_at<?::timestamptz ORDER BY c.created_at DESC,c.id DESC LIMIT ?::integer",
            ) { query ->
                query.verify(listOf(fixtureUuid(0x701, 1), QUERY_LIVE, cursor.created, 49), incomplete.keys(49))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan", "auto"])
    fun `admin filtering and literal configuration search return exact visible rows`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            val statusCases = listOf("PLANNED", "OPEN", "CLOSED", "UNKNOWN")
            session.query("admin_status", "$ADMIN_SELECT AND c.status=?::text $ADMIN_ORDER") { query ->
                // Auto gets eight ordinary executions before diagnostic EXPLAIN can affect its history.
                if (mode == "auto") {
                    query.warmUp(
                        (statusCases + statusCases).map { status ->
                            listOf<Any>(QUERY_LIVE, status, 50) to visibleLive.filter { it.status == status }.keys()
                        },
                    )
                }
                repeat(2) {
                    for (status in statusCases) {
                        val required = if (mode == "force_custom_plan" && status == "PLANNED") setOf("idx_complaints_status_page") else emptySet()
                        query.verify(listOf(QUERY_LIVE, status, 50), visibleLive.filter { it.status == status }.keys(), required)
                    }
                }
            }
            verifyAdmin(
                session,
                "type",
                "c.type=?::text",
                listOf("FEATURES"),
                visibleLive.filter { it.type == "FEATURES" },
                "idx_complaints_type_page",
            )
            verifyAdmin(
                session,
                "ownership",
                "c.ownership=?::text",
                listOf("LEGACY_UNCLAIMED"),
                visibleLive.filter { it.legacy > 0 },
                "idx_complaints_ownership_page",
            )
            verifyAdmin(session, "scope", "true", emptyList(), visibleLive, "idx_complaints_scope_page")
            verifyAdmin(
                session,
                "combined",
                "c.status=?::text AND c.type=?::text",
                listOf("OPEN", "FEATURES"),
                visibleLive.filter { it.status == "OPEN" && it.type == "FEATURES" },
                "idx_complaints_type_page",
            )
            val lower = QUERY_TIME.plusSeconds(2400)
            val upper = QUERY_TIME.plusSeconds(2600)
            val window = visibleLive.filter { it.updated >= lower && it.updated < upper }
            verifyAdmin(
                session,
                "date",
                "c.updated_at>=?::timestamptz AND c.updated_at<?::timestamptz",
                listOf(lower, upper),
                window,
                "idx_complaints_scope_page",
            )
            session.query("admin_search", "$ADMIN_SELECT AND $SEARCH_PREDICATE $ADMIN_ORDER") { query ->
                for (term in listOf("needleqa", "مانجا", "absentqa", "shared")) {
                    val expected = if (term == "shared") visibleLive else visibleLive.filter { it.token == term }
                    val required = if (mode == "force_custom_plan" && term in listOf("needleqa", "مانجا")) setOf("idx_complaints_search") else emptySet()
                    query.verify(listOf(QUERY_LIVE, term, 50), expected.keys(), required)
                }
            }
            session.query("admin_next", "$ADMIN_SELECT AND (c.updated_at,c.id)<(?::timestamptz,?::uuid) $ADMIN_ORDER") { query ->
                val cursor = visibleLive[49]
                query.verify(listOf(QUERY_LIVE, cursor.updated, cursor.id, 50), visibleLive.drop(50).keys(), setOf("idx_complaints_scope_page"))
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["force_custom_plan", "force_generic_plan"])
    fun `parent actor legacy and keyed mapping lookups have selective supporting access`(mode: String) {
        ComplaintPreparedSession(database, schema, mode).use { session ->
            session.query("parent", "SELECT id FROM complaints WHERE parent_resource_id=?::uuid AND data_scope_id=?::uuid ORDER BY id LIMIT 50") { query ->
                val parent = fixtureUuid(0x702, 101)
                val children = fixture.rows.filter { it.parentId == parent }.map { it.id.toString() }.sorted()
                assertEquals(8, children.size)
                query.verify(listOf(parent, QUERY_LIVE), children, setOf("idx_complaints_parent"))
                query.verify(listOf(parent, QUERY_TEST), emptyList(), setOf("idx_complaints_parent"))
            }
            session.query("closure", "SELECT id FROM complaints WHERE closure_actor_id=?::uuid ORDER BY id LIMIT 50") { query ->
                val actor = fixtureUuid(0x705, 1)
                val closed = fixture.rows.filter { it.closureActor == actor }.map { it.id.toString() }.sorted()
                assertEquals(8, closed.size)
                query.verify(listOf(actor), closed, setOf("idx_complaints_closure_actor"))
                query.verify(listOf(fixtureUuid(0x705, 99)), emptyList(), setOf("idx_complaints_closure_actor"))
            }
            legacyQueries(session)
            session.query("notice", "SELECT id FROM complaints WHERE kind='NOTICE' AND data_scope_id=?::uuid AND notice_key=?::text") { query ->
                query.verify(listOf(QUERY_LIVE, "query.notice"), listOf(fixtureUuid(0x704, 1).toString()), setOf("uq_complaints_notice_key"))
            }
        }
    }

    @Test
    fun `parent content erasure preserves children while ledger erasure detaches only nullable content reference`() {
        schema.connection().use { connection ->
            connection.autoCommit = false
            try {
                val parent = fixtureUuid(0x702, 101)
                val childrenSql = "SELECT id::text||':'||version::text FROM complaints WHERE parent_resource_id='$parent' ORDER BY id"
                val children = connection.strings(childrenSql)
                assertEquals(8, children.size)
                connection.exec(
                    "DELETE FROM complaints WHERE id='$parent'; " +
                        "UPDATE complaint_resource_ids SET state='DELETED',deleted_at=$FIXTURE_INSTANT WHERE id='$parent'",
                )
                assertEquals(children, connection.strings(childrenSql))
                connection.expectSqlFailure("DELETE FROM complaint_resource_ids WHERE id='$parent'", "23503", "fk_complaints_parent")
                val legacy = fixtureUuid(0x703, 1)
                connection.exec("DELETE FROM complaints WHERE id='$legacy'")
                assertEquals(
                    listOf("true|$legacy|$LIVE_SCOPE"),
                    connection.strings(
                        "SELECT (complaint_ref IS NULL)::text||'|'||assigned_id::text||'|'||data_scope_id::text " +
                            "FROM complaint_legacy_records WHERE assigned_id='$legacy'",
                    ),
                )
            } finally {
                connection.rollback()
            }
        }
    }

    @Test
    fun `foreign keys have leading support indexes and direct canonical bytes stay out of keys and INCLUDE`() {
        assertEquals(
            emptyList<String>(),
            schema.strings(
                """
            SELECT k.conname FROM pg_constraint k JOIN pg_class c ON c.oid=k.conrelid
            WHERE k.contype='f' AND c.relnamespace=current_schema()::regnamespace
              AND (k.conname LIKE 'fk_complaint_%' OR k.conname LIKE 'fk_installation_%' OR k.conname='fk_app_installations_reservation')
              AND NOT EXISTS (SELECT 1 FROM pg_index i WHERE i.indrelid=k.conrelid AND i.indisvalid AND i.indisready
                AND i.indpred IS NULL AND i.indexprs IS NULL AND i.indnkeyatts>=cardinality(k.conkey)
                AND ARRAY(SELECT v FROM unnest(i.indkey::smallint[]) WITH ORDINALITY a(v,n)
                          WHERE n<=cardinality(k.conkey) ORDER BY n)=k.conkey)
            ORDER BY k.conname
                """.trimIndent(),
            ),
        )
        // Bounded direct-attribute guard only: expression dependencies and differently named payloads
        // require the migration's separate source review; this is not a universal descriptor detector.
        assertEquals(
            emptyList<String>(),
            schema.strings(
                """
            SELECT c.relname||'.'||a.attname FROM pg_index i JOIN pg_class c ON c.oid=i.indrelid
            JOIN pg_attribute a ON a.attrelid=c.oid AND a.attnum=ANY(i.indkey)
            WHERE c.relnamespace=current_schema()::regnamespace AND c.relname LIKE 'complaint_%'
              AND a.atttypid='bytea'::regtype AND a.attname LIKE '%bytes'
                """.trimIndent(),
            ),
        )
    }

    private fun legacyQueries(session: ComplaintPreparedSession) {
        session.query(
            "legacy_identity",
            "SELECT id FROM complaints WHERE legacy_collection=?::text " +
                "AND legacy_key_id=?::text AND legacy_document_hmac=?::bytea",
        ) { query ->
            query.verify(
                listOf("complaints", "query-key", queryBytes("legacy-1")),
                listOf(fixtureUuid(0x703, 1).toString()),
                setOf("uq_complaints_legacy_identity"),
            )
            query.verify(listOf("complaints", "query-key", queryBytes("absent")), emptyList(), setOf("uq_complaints_legacy_identity"))
        }
        session.query(
            "legacy_owner",
            "SELECT id FROM complaints WHERE legacy_key_id=?::text " +
                "AND legacy_owner_fingerprint=?::bytea ORDER BY id LIMIT 50",
        ) { query ->
            query.verify(
                listOf("query-key", queryBytes("owner-0")),
                (1..8).map { fixtureUuid(0x703, it).toString() },
                setOf("idx_complaints_legacy_owner"),
            )
            query.verify(listOf("query-key", queryBytes("absent")), emptyList(), setOf("idx_complaints_legacy_owner"))
        }
        session.query("legacy_content", "SELECT assigned_id FROM complaint_legacy_records WHERE complaint_ref=?::uuid AND data_scope_id=?::uuid") { query ->
            query.verify(listOf(fixtureUuid(0x703, 1), QUERY_LIVE), listOf(fixtureUuid(0x703, 1).toString()), setOf("idx_complaint_legacy_content"))
            query.verify(listOf(fixtureUuid(0x703, 1), QUERY_TEST), emptyList(), setOf("idx_complaint_legacy_content"))
        }
        session.query(
            "legacy_mapping",
            "SELECT assigned_id FROM complaint_legacy_records WHERE collection_code=?::text " +
                "AND key_id=?::text AND document_hmac=?::bytea",
        ) { query ->
            query.verify(
                listOf("complaints", "query-key", queryBytes("legacy-1")),
                listOf(fixtureUuid(0x703, 1).toString()),
                setOf("pk_complaint_legacy_records"),
            )
            query.verify(listOf("complaints", "query-key", queryBytes("absent")), emptyList(), setOf("pk_complaint_legacy_records"))
        }
    }

    private fun verifyAdmin(
        session: ComplaintPreparedSession,
        tag: String,
        predicate: String,
        arguments: List<Any>,
        expected: List<QueryContentRow>,
        customIndex: String,
    ) {
        session.query("admin_$tag", "$ADMIN_SELECT AND $predicate $ADMIN_ORDER") { query ->
            query.verify(
                listOf(QUERY_LIVE) + arguments + 50,
                expected.keys(),
                if (session.mode == "force_custom_plan") setOf(customIndex) else emptySet(),
            )
        }
    }

    companion object {
        private const val OWNER_SELECT = """
            SELECT c.id,c.created_at,c.updated_at,c.version,c.type,c.status,c.subject,c.body FROM complaints c
            JOIN complaint_resource_ids r ON (r.id,r.data_scope_id)=(c.id,c.data_scope_id)
            JOIN app_installations a ON (a.id,a.data_scope_id)=(c.owner_id,c.data_scope_id)
            JOIN complaint_installation_ids i ON (i.id,i.data_scope_id)=(a.id,a.data_scope_id)
            WHERE c.owner_id=?::uuid AND c.data_scope_id=?::uuid
              AND r.state='LIVE' AND a.state='ACTIVE' AND i.state='ACTIVE'
        """
        private const val ADMIN_SELECT = """
            SELECT c.id,c.updated_at,c.version,c.type,c.status,c.subject,c.body FROM complaints c
            JOIN complaint_resource_ids r ON (r.id,r.data_scope_id)=(c.id,c.data_scope_id)
            LEFT JOIN app_installations a ON (a.id,a.data_scope_id)=(c.owner_id,c.data_scope_id)
            LEFT JOIN complaint_installation_ids i ON (i.id,i.data_scope_id)=(a.id,a.data_scope_id)
            WHERE c.data_scope_id=?::uuid AND c.kind IN ('REPORT','REPLY') AND r.state='LIVE'
              AND (c.owner_id IS NULL OR (a.state='ACTIVE' AND i.state='ACTIVE'))
        """
        private const val ADMIN_ORDER = "ORDER BY c.updated_at DESC,c.id DESC LIMIT ?::integer"
        private const val SEARCH_PREDICATE = "to_tsvector('simple'::regconfig,coalesce(c.subject,'')||' '||coalesce(c.body,'')) " +
            "@@ plainto_tsquery('simple'::regconfig,?::text)"
    }
}
