package me.manga.kira.backend.database.complaint

import java.sql.Connection
import java.time.Instant
import java.util.Base64
import java.util.UUID

val QUERY_CUTOFF: Instant = Instant.parse("2026-04-01T00:00:00Z")

data class QueryPublication(
    val name: String,
    val kind: String = "OWNER_DELETE",
    val count: Int = 1,
    val state: String = "PREPARED",
    val scope: UUID = QUERY_LIVE,
    val writer: UUID = fixtureUuid(0x714, 1),
    val epoch: Long = 1,
    val created: Instant = QUERY_TIME,
) {
    val event: String get() = Base64.getUrlEncoder().withoutPadding().encodeToString(queryBytes(name))
    val key: String get() = "query/$name"
    val version: String get() = "version-$name"
}

data class QueryReceipt(val number: Int, val special: Boolean, val expiry: Instant?) {
    val actorKind: String get() = if (!special && number in 49..56) "ADMIN" else "INSTALLATION"

    // The 50/51 boundary has equal expiry/kind/actor but different keys. Later actors have
    // smaller keys, and the later kind reuses actors/keys, challenging every cursor suffix.
    val key: UUID get() = fixtureUuid(
        if (special) 0x711 else 0x710,
        if (special) {
            number
        } else {
            when (number) {
                in 49..51 -> 500 + number - 48
                in 52..54 -> 100 + number - 51
                in 55..56 -> 200 + number - 54
                in 57..64 -> 100 + (number - 57) % 3
                else -> number
            }
        },
    )
    val actor: UUID get() = fixtureUuid(
        if (special) 0x712 else 0x713,
        if (special) {
            number
        } else {
            when (number) {
                in 49..51 -> 1
                in 52..54 -> 2
                in 55..56 -> 3
                in 57..64 -> 1 + (number - 57) / 3
                else -> number
            }
        },
    )
    val identity: String get() = if (special) "$actor:$key" else "$actorKind:$actor:$key"
    val complete: Boolean get() = expiry != null
    val completion: Instant? get() = expiry?.minusSeconds(691200)
    val publication: QueryPublication get() = QueryPublication(
        "${if (special) "special" else "ordinary"}-$number",
        if (special) "OWNER_DELETE_ALL" else "OWNER_DELETE",
        if (special) 0 else 1,
        if (complete) "APPLIED" else "PREPARED",
    )
}

class ComplaintReceiptQueryFixture {
    val ordinary = (1..16384).map { number ->
        QueryReceipt(
            number,
            false,
            when {
                number <= 48 -> QUERY_CUTOFF.minusSeconds((80 - number).toLong())
                number <= 64 -> QUERY_CUTOFF.minusSeconds(1)
                number <= 16320 -> QUERY_CUTOFF.plusSeconds(number.toLong())
                else -> null
            },
        )
    }
    val special = (1..4096).map { number ->
        QueryReceipt(
            number,
            true,
            when {
                number <= 32 -> QUERY_CUTOFF.minusSeconds((33 - number).toLong())
                number <= 4064 -> QUERY_CUTOFF.plusSeconds(number.toLong())
                else -> null
            },
        )
    }

    fun seed(connection: Connection) {
        println("W02_RECEIPT_FIXTURE " + queryDigest((ordinary + special).joinToString("\n")))
        connection.insertQueryPublications(ordinary.filterNot { it.complete }.map { it.publication } + special.map { it.publication })
        connection.insertQueryFixture(
            "complaint_idempotency_receipts",
            "actor_kind,actor_id,idempotency_key,operation,fingerprint,target_ids,data_scope_id,test_only,state,outcome," +
                "response_status,problem_code,publication_ref,created_at,authorized_at,completed_at,expires_at",
            ordinary.map { row ->
                listOf(
                    row.actorKind, row.actor, row.key,
                    if (row.actorKind == "ADMIN") {
                        "ADMIN_EDIT"
                    } else if (row.complete) {
                        "OWNER_EDIT"
                    } else {
                        "OWNER_DELETE"
                    },
                    queryBytes("fingerprint"),
                    QueryUuidArray(listOf(fixtureUuid(0x716, row.number))), QUERY_LIVE, false,
                    if (row.complete) "COMPLETED" else "AUTHORIZED_DELETE", if (row.complete) "REJECTED" else null,
                    if (row.complete) 404 else null, if (row.complete) "COMPLAINT_NOT_FOUND" else null,
                    if (row.complete) null else row.publication.event, row.completion?.minusSeconds(10) ?: QUERY_TIME,
                    if (row.complete) null else QUERY_TIME, row.completion, row.expiry,
                )
            },
        )
        connection.insertQueryFixture(
            "complaint_installation_ids",
            "id,data_scope_id,test_only,state,created_at,terminal_at",
            special.map { row ->
                listOf(row.actor, QUERY_LIVE, false, if (row.complete) "DELETED" else "DELETION_PENDING", QUERY_TIME, row.completion)
            },
        )
        connection.insertQueryFixture(
            "app_installations",
            "id,data_scope_id,test_only,secret_verifier,platform,state,credential_version,owner_reference,created_at,last_authenticated_at,version",
            special.filterNot { it.complete }.map {
                listOf(
                    it.actor, QUERY_LIVE, false, queryBytes("special-credential"), "ANDROID", "DELETION_PENDING", 1L,
                    fixtureUuid(0x717, it.number), QUERY_TIME, QUERY_TIME, 1L,
                )
            },
        )
        connection.insertQueryFixture(
            "installation_deletion_receipts",
            "installation_id,deletion_key,submitted_credential_version,fingerprint,data_scope_id,test_only,state,outcome,response_status," +
                "publication_ref,external_event_id,external_epoch,external_object_version,external_ciphertext_hash," +
                "created_at,authorized_at,completed_at,expires_at",
            special.map { row ->
                listOf(
                    row.actor, row.key, 1L, queryBytes("fingerprint"), QUERY_LIVE, false,
                    if (row.complete) "COMPLETED" else "AUTHORIZED_DELETE", if (row.complete) "APPLIED" else null,
                    if (row.complete) 204 else null, row.publication.event, if (row.complete) row.publication.event else null,
                    if (row.complete) 1L else null, if (row.complete) row.publication.version else null,
                    if (row.complete) queryBytes("ciphertext") else null, QUERY_TIME, QUERY_TIME, row.completion, row.expiry,
                )
            },
        )
        connection.analyzeFixture(
            listOf(
                "complaint_idempotency_receipts",
                "installation_deletion_receipts",
                "complaint_journal_publications",
                "complaint_installation_ids",
                "app_installations",
            ),
        )
    }
}

/** Schema sentinels only: descriptor/hash shapes do not authenticate any provider event. */
fun Connection.insertQueryPublications(rows: List<QueryPublication>) {
    val bytes = "{\"synthetic\":true}".toByteArray(Charsets.UTF_8)
    val hash = queryBytes("{\"synthetic\":true}")
    insertQueryFixture(
        "complaint_journal_publications",
        "event_id,data_scope_id,test_only,writer_generation,journal_epoch,event_kind,target_count,routing_key_id,object_key,canonicalizer," +
            "event_bytes,semantic_hash,state,created_at,object_version,ciphertext_hash,object_created_at,retain_until,verified_at," +
            "verification_bytes,verification_hash,applied_at",
        rows.map { row ->
            val verified = row.state != "PREPARED"
            listOf(
                row.event, row.scope, row.scope != QUERY_LIVE, row.writer, row.epoch, row.kind, row.count, "query-route", row.key, "kcj-1",
                bytes, hash, row.state, row.created, if (verified) row.version else null, if (verified) queryBytes("ciphertext") else null,
                if (verified) row.created else null, if (verified) row.created.plusSeconds(31536000) else null,
                if (verified) row.created else null, if (verified) bytes else null, if (verified) hash else null,
                if (row.state == "APPLIED") row.created else null,
            )
        },
    )
}
