package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import java.sql.ResultSet
import java.util.UUID

/** Initial gate transition only. Every other column, including both leases/epoch/scan/checkpoint, is untouched. */
internal object TestInitialAdmissionSqlV1 {
    private const val GLOBAL = "'00000000-0000-0000-0000-000000000000'::uuid"
    private const val FINGERPRINT = "sha256(convert_to((to_jsonb(c) || jsonb_build_object('row_xmin', c.xmin::text))::text, 'UTF8'))"
    val controls = """
        SELECT c.data_scope_id, c.desired_generation, c.desired_configuration_hash,
            (c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL AND isfinite(c.updated_at)
                AND c.test_only = (c.data_scope_id <> $GLOBAL)
                AND c.desired_generation > 0 AND octet_length(to_jsonb(c)::text) BETWEEN 1 AND 524288) IS TRUE AS valid,
            $FINGERPRINT AS fingerprint
        FROM complaint_journal_control c WHERE c.data_scope_id IN ($GLOBAL, ?::uuid) ORDER BY c.data_scope_id LIMIT 3
    """.trimIndent()

    val release = """
        WITH expected AS MATERIALIZED (SELECT ?::uuid AS scope, ?::bytea AS global_fingerprint, ?::bytea AS scope_fingerprint),
            sampled AS MATERIALIZED (SELECT clock_timestamp() AS sampled_at)
        UPDATE complaint_journal_control c
        SET maintenance_closed = false, creation_closed = false, updated_at = s.sampled_at
        FROM expected e CROSS JOIN sampled s
        WHERE c.data_scope_id IN ($GLOBAL, e.scope) AND c.test_only = (c.data_scope_id = e.scope)
            AND c.maintenance_closed AND c.creation_closed AND c.pending_projection_token IS NULL
            AND isfinite(s.sampled_at) AND s.sampled_at >= c.updated_at
            AND s.sampled_at >= '1970-01-01T00:00:00Z'::timestamptz AND s.sampled_at < '10000-01-01T00:00:00Z'::timestamptz
            AND $FINGERPRINT = CASE WHEN c.data_scope_id = $GLOBAL THEN e.global_fingerprint ELSE e.scope_fingerprint END
        RETURNING c.data_scope_id
    """.trimIndent()
}

/** Bounded exact physical preimages, including lease state/xmin; not a gate or admission ticket. */
internal class TestInitialAdmissionControlV1(row: ResultSet) {
    val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
    private val generation = row.requiredTestActivationLong("desired_generation")
    private val hash = row.getBytes("desired_configuration_hash")?.copyOf()
    private val fingerprint = checkNotNull(row.getBytes("fingerprint")).copyOf()

    init {
        requireRegistration(row.requiredTestActivationBoolean("valid") && fingerprint.size == 32 && (hash == null || hash.size == 32))
    }
    fun requireSame(other: TestInitialAdmissionControlV1) {
        requireRegistration(scope == other.scope && generation == other.generation && hash.contentEquals(other.hash) && fingerprint.contentEquals(other.fingerprint))
    }
    fun fingerprint(): ByteArray = fingerprint.copyOf()
    fun identityArguments(): Array<Any?> = arrayOf(generation, hash?.copyOf())
    override fun toString(): String = "TestInitialAdmissionControlV1(bounded-comparison,no-authority)"
}

internal class TestInitialAdmissionControlsV1(rows: List<TestInitialAdmissionControlV1>, scope: UUID) {
    private val global = rows.single { it.scope == UUID(0L, 0L) }
    private val scoped = rows.single { it.scope == scope }
    init { requireRegistration(rows.size == 2 && scope != UUID(0L, 0L)) }
    fun requireSame(other: TestInitialAdmissionControlsV1) { global.requireSame(other.global); scoped.requireSame(other.scoped) }
    fun releaseArguments(scope: UUID): Array<Any?> {
        requireRegistration(scope == scoped.scope)
        return arrayOf(scope, global.fingerprint(), scoped.fingerprint())
    }
    fun globalIdentityArguments(): Array<Any?> = global.identityArguments()
    override fun toString(): String = "TestInitialAdmissionControlsV1(two-exact-preimages,no-authority)"
}
