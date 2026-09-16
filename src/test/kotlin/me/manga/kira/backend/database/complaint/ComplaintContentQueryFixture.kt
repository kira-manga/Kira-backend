package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import java.security.MessageDigest
import java.sql.Connection
import java.time.Instant
import java.util.UUID

val QUERY_TIME: Instant = Instant.parse("2026-01-01T00:00:00Z")
val QUERY_LIVE: UUID = UUID.fromString(LIVE_SCOPE)
val QUERY_TEST: UUID = UUID.fromString(TEST_SCOPE)

data class QueryContentRow(
    val id: UUID,
    val scope: UUID,
    val owner: Int,
    val slot: Int,
    val kind: String,
    val type: String?,
    val status: String,
    val created: Instant,
    val updated: Instant,
    val token: String = "",
    val legacy: Int = 0,
) {
    val visible: Boolean get() = kind != "NOTICE" && owner != 256 && (owner == 0 || scope == QUERY_TEST || slot % 10 != 0)
    val ownerId: UUID? get() = if (owner == 0) null else fixtureUuid(0x701, owner)
    val parentId: UUID? get() = if (kind == "REPLY") fixtureUuid(0x702, owner * 100 + 1) else null
    val closureActor: UUID? get() = if (status == "CLOSED") fixtureUuid(0x705, 1 + (owner - 65) % 8) else null
}

/** Expected rows are constructed in Kotlin, not selected from the SQL query being tested. */
class ComplaintContentQueryFixture {
    val rows: List<QueryContentRow> = buildList {
        for (owner in 1..288) {
            val test = owner > 256
            for (slot in 1..if (test) 64 else 80) {
                add(
                    QueryContentRow(
                        fixtureUuid(0x702, owner * 100 + slot), if (test) QUERY_TEST else QUERY_LIVE, owner, slot,
                        if (slot in 2..9) "REPLY" else "REPORT",
                        if (slot == 11 && owner in 17..32) "FEATURES" else "TECHNICAL",
                        when {
                            slot == 11 && owner in 1..16 -> "PLANNED"
                            slot == 11 && owner in 65..128 -> "CLOSED"
                            else -> "OPEN"
                        },
                        QUERY_TIME.plusSeconds(((slot - 1) / 2).toLong()),
                        QUERY_TIME.plusSeconds(((slot - 1) * 256 + owner - 1).toLong()),
                        when {
                            slot == 11 && owner in 129..144 -> "needleqa"
                            slot == 11 && owner in 145..160 -> "مانجا"
                            else -> ""
                        },
                    ),
                )
            }
        }
        for (legacy in 1..1024) {
            add(
                QueryContentRow(
                    fixtureUuid(0x703, legacy), QUERY_LIVE, 0, 0, "REPORT", "CUSTOM", "UNKNOWN",
                    QUERY_TIME.minusSeconds(legacy.toLong()), QUERY_TIME.minusSeconds(legacy.toLong()), legacy = legacy,
                ),
            )
        }
        for ((index, scope) in listOf(QUERY_LIVE, QUERY_TEST).withIndex()) {
            add(QueryContentRow(fixtureUuid(0x704, index + 1), scope, 0, 0, "NOTICE", null, "PINNED", QUERY_TIME, QUERY_TIME))
        }
    }

    fun seed(connection: Connection) {
        assertEquals(23554, rows.size)
        assertEquals(19384, rows.count { it.visible && it.scope == QUERY_LIVE })
        println("W02_CONTENT_FIXTURE " + queryDigest(rows.joinToString("\n")))
        connection.exec(
            "INSERT INTO complaint_test_runs(data_scope_id,test_only,state,configuration_hash,accounting_version,installation_limit," +
                "enrolled_count,original_reserve,unused_reserve,activation_catalog_generation,activation_catalog_hash,created_at) " +
                "VALUES ('$TEST_SCOPE',true,'ACTIVE',$FIXTURE_DIGEST,1,32,32,$ZERO_VECTOR,$ZERO_VECTOR,1,$FIXTURE_DIGEST,$FIXTURE_INSTANT)",
        )
        connection.insertQueryFixture(
            "users",
            "id,email,password_hash,role,enabled,created_at,updated_at",
            (1..8).map {
                listOf(fixtureUuid(0x705, it), "query-admin-$it@example.test", "synthetic-not-a-login", "ADMIN", true, QUERY_TIME, QUERY_TIME)
            },
        )
        connection.insertQueryFixture(
            "complaint_installation_ids",
            "id,data_scope_id,test_only,state,created_at",
            (1..288).map {
                listOf(
                    fixtureUuid(0x701, it),
                    if (it > 256) QUERY_TEST else QUERY_LIVE,
                    it > 256,
                    if (it == 256) "DELETION_PENDING" else "ACTIVE",
                    QUERY_TIME,
                )
            },
        )
        connection.insertQueryFixture(
            "app_installations",
            "id,data_scope_id,test_only,secret_verifier,platform,state,credential_version,owner_reference,created_at,last_authenticated_at,version",
            (1..288).map {
                listOf(
                    fixtureUuid(0x701, it), if (it > 256) QUERY_TEST else QUERY_LIVE, it > 256, queryBytes("credential-$it"),
                    "ANDROID", if (it == 256) "DELETION_PENDING" else "ACTIVE", 1L, fixtureUuid(0x706, it), QUERY_TIME, QUERY_TIME, 1L,
                )
            },
        )
        connection.insertQueryFixture(
            "complaint_resource_ids",
            "id,data_scope_id,test_only,state,created_at",
            rows.map {
                listOf(
                    it.id,
                    it.scope,
                    it.scope == QUERY_TEST,
                    if (it.owner in 1..256 && it.slot % 10 == 0) "DELETION_PENDING" else "LIVE",
                    it.created,
                )
            },
        )
        insertContent(connection)
        insertLegacy(connection)
        connection.analyzeFixture(
            listOf(
                "users",
                "complaint_installation_ids",
                "app_installations",
                "complaint_resource_ids",
                "complaints",
                "complaint_import_runs",
                "complaint_legacy_records",
            ),
        )
    }

    private fun insertContent(connection: Connection) {
        connection.insertQueryFixture(
            "complaints",
            "id,data_scope_id,test_only,owner_id,ownership,kind,type,status,notice_key,subject,body,parent_resource_id," +
                "platform,os_version,manufacturer,device_model,closure_reason,closed_at,closure_provenance,closure_actor_id," +
                "legacy_collection,legacy_key_id,legacy_document_hmac,legacy_payload_hash,legacy_owner_fingerprint,legacy_reconciliation_code," +
                "created_at,updated_at,version",
            rows.map { row ->
                val notice = row.kind == "NOTICE"
                val legacy = row.legacy > 0
                val closed = row.status == "CLOSED"
                listOf(
                    row.id, row.scope, row.scope == QUERY_TEST, row.ownerId,
                    if (notice) {
                        "SYSTEM"
                    } else if (legacy) {
                        "LEGACY_UNCLAIMED"
                    } else {
                        "INSTALLATION"
                    },
                    row.kind, row.type, row.status,
                    if (notice) "query.notice" else null, if (notice) null else "Query subject",
                    if (notice) null else "shared ${row.token} " + "synthetic fixture text ".repeat(6), row.parentId,
                    if (row.owner > 0) "ANDROID" else null, if (notice) null else "15", if (notice) null else "Fixture",
                    if (notice) null else "Model", if (closed) "Synthetic resolution" else null, if (closed) row.updated else null,
                    if (closed) "ADMIN" else null, row.closureActor, if (legacy) "complaints" else null,
                    if (legacy) "query-key" else null, if (legacy) queryBytes("legacy-${row.legacy}") else null,
                    if (legacy) queryBytes("payload-${row.legacy}") else null,
                    if (legacy) queryBytes("owner-${(row.legacy - 1) / 8}") else null, if (legacy) "MAPPED" else null,
                    row.created, row.updated, 1L,
                )
            },
        )
    }

    private fun insertLegacy(connection: Connection) {
        val run = fixtureUuid(0x707, 1)
        connection.exec(
            "INSERT INTO complaint_import_runs(id,data_scope_id,test_only,snapshot_hash,collection_code,key_id,tool_version," +
                "configuration_hash,cutoff_at,document_count,accepted_count,rejected_count,accounted_count,staged_bytes," +
                "mapping_root,mapping_count,catalog_generation,catalog_hash,state,created_at,sealed_at,promoted_at) " +
                "VALUES ('$run','$LIVE_SCOPE',false,$FIXTURE_DIGEST,'complaints','query-key','query-v1',$FIXTURE_DIGEST," +
                "'2026-01-01T00:00:00Z',1024,1024,0,1024,131072,$FIXTURE_DIGEST,1024,1,$FIXTURE_DIGEST,'PROMOTED'," +
                "$FIXTURE_INSTANT,$FIXTURE_INSTANT,$FIXTURE_INSTANT)",
        )
        connection.insertQueryFixture(
            "complaint_legacy_records",
            "collection_code,key_id,document_hmac,payload_hash,run_id,data_scope_id,test_only,assigned_id,complaint_ref," +
                "reconciliation_code,state,cutoff_at,expires_at,created_at",
            rows.filter { it.legacy > 0 }.map {
                listOf(
                    "complaints", "query-key", queryBytes("legacy-${it.legacy}"), queryBytes("payload-${it.legacy}"), run,
                    QUERY_LIVE, false, it.id, it.id, "MAPPED", "PROMOTED", QUERY_TIME, Instant.parse("2027-02-01T00:00:00Z"), QUERY_TIME,
                )
            },
        )
    }
}

fun queryBytes(value: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))

fun List<QueryContentRow>.createdOrder(): List<QueryContentRow> = sortedWith(
    compareByDescending<QueryContentRow> { it.created }.thenByDescending { it.id.toString() },
)

fun List<QueryContentRow>.updatedOrder(): List<QueryContentRow> = sortedWith(
    compareByDescending<QueryContentRow> { it.updated }.thenByDescending { it.id.toString() },
)

fun List<QueryContentRow>.keys(limit: Int = 50): List<String> = take(limit).map { it.id.toString() }
