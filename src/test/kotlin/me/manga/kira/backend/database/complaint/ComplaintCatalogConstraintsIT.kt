package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.sql.Connection

class ComplaintCatalogConstraintsIT : ComplaintFixtureTest() {
    @Test
    fun `unsigned approval and complete envelope have separately enforced exact byte and hash bounds`() {
        for ((column, hash, maximum) in listOf(
            Triple("approval_bytes", "approval_hash", 4096),
            Triple("unsigned_bytes", "unsigned_hash", 8388608),
            Triple("envelope_bytes", "envelope_hash", 8388608),
        )) {
            connection.withRollbackPoint {
                val exact = "decode(repeat('ab',$maximum),'hex')"
                connection.exec(catalogUpdate("$column=$exact,$hash=sha256($exact)"))
                assertEquals(listOf(maximum.toString()), connection.strings("SELECT octet_length($column) FROM complaint_catalog_mutations"))
                for (size in listOf(0, maximum + 1)) {
                    val bytes = "decode(repeat('ab',$size),'hex')"
                    connection.expectSqlFailure(
                        catalogUpdate("$column=$bytes,$hash=sha256($bytes)"),
                        constraint = if (column == "envelope_bytes") "chk_complaint_catalog_envelope" else "chk_complaint_catalog_bytes",
                    )
                }
                connection.expectSqlFailure(
                    catalogUpdate("$hash=$FIXTURE_DIGEST"),
                    constraint = if (column == "envelope_bytes") "chk_complaint_catalog_envelope" else "chk_complaint_catalog_bytes",
                )
            }
        }
    }

    @Test
    fun `rotation persists either partial signature but never forms an incomplete signed envelope`() {
        connection.exec(
            catalogUpdate(
                "operation_type='SIGNER_ROTATION_OVERLAP',predecessor_generation=1,successor_generation=2," +
                    "predecessor_hash=$FIXTURE_DIGEST,signer_policy='ROTATION_OVERLAP',signer_one_signature=NULL," +
                    "signer_two_id='fixture-second',signer_two_algorithm='RSASSA_PSS_SHA_256'",
            ),
        )
        for (member in listOf("one", "two")) {
            connection.withRollbackPoint {
                connection.exec(catalogUpdate("signer_${member}_signature=decode('01','hex')"))
                connection.expectSqlFailure(
                    catalogUpdate("envelope_bytes=$FIXTURE_BYTES,envelope_hash=$FIXTURE_HASH"),
                    constraint = "chk_complaint_catalog_envelope",
                )
                assertEquals(listOf("true"), connection.strings("SELECT (signer_${member}_signature IS NOT NULL)::text FROM complaint_catalog_mutations"))
            }
        }
        connection.exec(
            catalogUpdate(
                "signer_one_signature=decode(repeat('ab',1024),'hex')," +
                    "signer_two_signature=decode(repeat('cd',1024),'hex'),envelope_bytes=$FIXTURE_BYTES,envelope_hash=$FIXTURE_HASH",
            ),
        )
        for (column in listOf("signer_one_signature", "signer_two_signature")) {
            for (size in listOf(0, 1025)) {
                connection.expectSqlFailure(catalogUpdate("$column=decode(repeat('ab',$size),'hex')"), constraint = "chk_complaint_catalog_signers")
            }
            connection.expectSqlFailure(catalogUpdate("$column=NULL"), constraint = "chk_complaint_catalog_envelope")
        }
        for (set in listOf("signer_two_id=signer_one_id", "signer_two_algorithm=NULL", "signer_policy='SINGLE'")) {
            connection.expectSqlFailure(catalogUpdate(set), constraint = "chk_complaint_catalog_signers")
        }
    }

    @Test
    fun `single signer policy forbids an extra signer and unsupported policy names`() {
        for (set in listOf(
            "signer_two_id='extra'",
            "signer_two_algorithm='extra'",
            "signer_two_signature=decode('01','hex')",
            "signer_policy='ANY'",
            "signer_one_id=''",
            "signer_one_algorithm=''",
        )) {
            connection.expectSqlFailure(catalogUpdate(set), constraint = "chk_complaint_catalog_signers")
        }
        connection.exec(catalogUpdate("signer_one_signature=NULL"))
        connection.expectSqlFailure(
            catalogUpdate("envelope_bytes=$FIXTURE_BYTES,envelope_hash=$FIXTURE_HASH"),
            constraint = "chk_complaint_catalog_envelope",
        )
    }

    @Test
    fun `complete catalog requires exact dual-copy evidence and cannot skip projection ownership`() {
        connection.expectSqlFailure(catalogUpdate("state='COMPLETED',completed_at=$FIXTURE_INSTANT"), constraint = "chk_complaint_catalog_state")
        connection.markCatalogComplete()
        for (column in listOf("primary_evidence_hash", "replica_evidence_hash", "object_version", "retain_until")) {
            connection.expectSqlFailure(catalogUpdate("$column=NULL"), constraint = "chk_complaint_catalog_copies")
        }
        for (column in listOf("primary", "replica")) {
            connection.expectSqlFailure(
                catalogUpdate("${column}_evidence_bytes=NULL,${column}_evidence_hash=NULL"),
                constraint = "chk_complaint_catalog_state",
            )
        }
        connection.expectSqlFailure(catalogUpdate("object_version='null'"), constraint = "chk_complaint_catalog_copies")
        connection.expectSqlFailure(catalogUpdate("completed_at=NULL"), constraint = "chk_complaint_catalog_state")
        connection.expectSqlFailure(catalogUpdate("projected_at='infinity'"), constraint = "chk_complaint_catalog_times")
        connection.exec(catalogUpdate("projected_at=$FIXTURE_INSTANT"))
    }

    @Test
    fun `both prepared and completed unprojected intents exclude a successor until projection`() {
        val successor = connection.copyRowSql(
            "complaint_catalog_mutations",
            mapOf(
                "operation_token" to "'$SUCCESSOR_TOKEN'",
                "operation_type" to "'EPOCH_SEAL'",
                "predecessor_generation" to "1",
                "predecessor_hash" to FIXTURE_DIGEST,
                "successor_generation" to "2",
                "object_key" to "'fixture/catalog/2'",
            ),
        )
        connection.expectSqlFailure(successor, "23505", "uq_complaint_catalog_pending")
        connection.markCatalogComplete()
        connection.expectSqlFailure(successor, "23505", "uq_complaint_catalog_pending")
        connection.exec(catalogUpdate("projected_at=$FIXTURE_INSTANT"))
        // New intent must be PREPARED, not a copied completed/projection state.
        val fresh = connection.copyRowSql(
            "complaint_catalog_mutations",
            mapOf(
                "operation_token" to "'$SUCCESSOR_TOKEN'", "operation_type" to "'EPOCH_SEAL'", "predecessor_generation" to "1",
                "predecessor_hash" to FIXTURE_DIGEST, "successor_generation" to "2", "object_key" to "'fixture/catalog/2'",
                "state" to "'PREPARED'", "completed_at" to "NULL", "projected_at" to "NULL",
            ),
        )
        connection.exec(fresh)
        assertEquals(listOf("2"), connection.strings("SELECT count(*) FROM complaint_catalog_mutations"))
    }

    @Test
    fun `activation intent can precede a run but scope and predecessor chain are still validated`() {
        connection.exec("DELETE FROM complaint_test_runs")
        connection.exec(
            catalogUpdate(
                "operation_type='TEST_RUN_ACTIVATION',data_scope_id='$TEST_SCOPE',test_only=true," +
                    "predecessor_generation=1,predecessor_hash=$FIXTURE_DIGEST,successor_generation=2",
            ),
        )
        assertEquals(listOf("0"), connection.strings("SELECT count(*) FROM complaint_test_runs"))
        connection.expectSqlFailure(catalogUpdate("operation_type='EPOCH_SEAL',data_scope_id=NULL"), constraint = "chk_complaint_catalog_scope")
        connection.expectSqlFailure(catalogUpdate("data_scope_id='$LIVE_SCOPE',test_only=false"), constraint = "chk_complaint_catalog_operation")
        for (set in listOf("predecessor_generation=0", "successor_generation=65537", "predecessor_hash=decode('00','hex')")) {
            connection.expectSqlFailure(catalogUpdate(set), constraint = "chk_complaint_catalog_chain")
        }
    }

    companion object {
        private const val SUCCESSOR_TOKEN = "63000000-0000-4000-8000-000000000002"
    }
}

fun catalogUpdate(set: String): String = "UPDATE complaint_catalog_mutations SET $set WHERE operation_token='63000000-0000-4000-8000-000000000001'"

fun Connection.markCatalogComplete() = exec(
    catalogUpdate(
        "envelope_bytes=$FIXTURE_BYTES,envelope_hash=$FIXTURE_HASH,object_version='catalog-version-1',retain_until=$FIXTURE_INSTANT," +
            "primary_evidence_bytes=$FIXTURE_BYTES,primary_evidence_hash=$FIXTURE_HASH," +
            "replica_evidence_bytes=$FIXTURE_BYTES,replica_evidence_hash=$FIXTURE_HASH,state='COMPLETED',completed_at=$FIXTURE_INSTANT",
    ),
)
