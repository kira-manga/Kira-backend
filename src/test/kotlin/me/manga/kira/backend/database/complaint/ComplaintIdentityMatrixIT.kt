package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ComplaintIdentityMatrixIT : ComplaintFixtureTest() {
    @Test
    fun `legacy identity ignores changed payload and notice uniqueness applies only within one scope`() {
        val newId = fixtureUuid(0x801, 1).toString()
        connection.exec(connection.copyRowSql("complaint_resource_ids", mapOf("id" to "'$newId'"), "id='$LEGACY_COMPLAINT_ID'"))
        connection.expectSqlFailure(
            connection.copyRowSql(
                "complaints",
                mapOf("id" to "'$newId'", "legacy_payload_hash" to FIXTURE_DIGEST),
                "id='$LEGACY_COMPLAINT_ID'",
            ),
            "23505",
            "uq_complaints_legacy_identity",
        )
        connection.expectSqlFailure(
            connection.copyRowSql("complaints", mapOf("id" to "'$newId'"), "id='$NOTICE_ID'"),
            "23505",
            "uq_complaints_notice_key",
        )
        connection.exec("UPDATE complaint_resource_ids SET data_scope_id='$TEST_SCOPE',test_only=true WHERE id='$newId'")
        connection.exec(
            connection.copyRowSql(
                "complaints",
                mapOf("id" to "'$newId'", "data_scope_id" to "'$TEST_SCOPE'", "test_only" to "true"),
                "id='$NOTICE_ID'",
            ),
        )
        assertEquals(listOf("2"), connection.strings("SELECT count(*) FROM complaints WHERE kind='NOTICE' AND notice_key='fixture.notice'"))
        connection.expectSqlFailure(
            connection.copyRowSql(
                "complaint_resource_ids",
                mapOf("data_scope_id" to "'$OTHER_SCOPE'", "test_only" to "true"),
                "id='$newId'",
            ),
            "23505",
            "pk_complaint_resource_ids",
        )
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("scopeCases")
    fun `credentials content and deletion receipts bind the exact parent scope`(case: ScopeCase) {
        val id = fixtureUuid(0x801, 1).toString()
        val credential = fixtureUuid(0x802, 1).toString()
        val scope = "'${case.child}'"
        val test = (case.child != LIVE_SCOPE).toString()
        val sql = when (case.kind) {
            "credential" -> {
                connection.exec(
                    "INSERT INTO complaint_installation_ids(id,data_scope_id,test_only,state,created_at) " +
                        "VALUES ('$credential','${case.parent}',${case.parent != LIVE_SCOPE},'ACTIVE',$FIXTURE_INSTANT)",
                )
                connection.copyRowSql(
                    "app_installations",
                    mapOf(
                        "id" to "'$credential'",
                        "data_scope_id" to scope,
                        "test_only" to test,
                        "owner_reference" to "'$id'",
                    ),
                    "id='$INSTALLATION_ID'",
                )
            }

            "content" -> {
                connection.exec(
                    "INSERT INTO complaint_resource_ids(id,data_scope_id,test_only,state,created_at) " +
                        "VALUES ('$id','${case.parent}',${case.parent != LIVE_SCOPE},'LIVE',$FIXTURE_INSTANT)",
                )
                connection.copyRowSql(
                    "complaints",
                    mapOf(
                        "id" to "'$id'",
                        "data_scope_id" to scope,
                        "test_only" to test,
                        "notice_key" to "'scoped.fixture'",
                    ),
                    "id='$NOTICE_ID'",
                )
            }

            else -> scopedReceipt(case, credential, scope, test)
        }
        if (case.parent == case.child) connection.exec(sql) else connection.expectSqlFailure(sql, "23503", case.constraint)
    }

    @Test
    fun `notice and legacy ASCII identifiers enforce their own exact endpoints`() {
        for (key in listOf("a", "a".repeat(96), "a._-")) {
            connection.exec("UPDATE complaints SET notice_key=${sqlText(key)} WHERE id='$NOTICE_ID'")
        }
        for (key in listOf("", "Upper", "unsafe/key")) {
            connection.expectSqlFailure("UPDATE complaints SET notice_key=${sqlText(key)} WHERE id='$NOTICE_ID'", constraint = "chk_complaints_text")
        }
        connection.expectSqlFailure("UPDATE complaints SET notice_key=repeat('a',97) WHERE id='$NOTICE_ID'", "22001")
        for ((column, limit) in mapOf("legacy_collection" to 32, "legacy_key_id" to 128)) {
            for (size in listOf(1, limit)) connection.exec("UPDATE complaints SET $column=repeat('a',$size) WHERE id='$LEGACY_COMPLAINT_ID'")
            connection.expectSqlFailure("UPDATE complaints SET $column='' WHERE id='$LEGACY_COMPLAINT_ID'", constraint = "chk_complaints_legacy")
            connection.expectSqlFailure("UPDATE complaints SET $column='ع' WHERE id='$LEGACY_COMPLAINT_ID'", constraint = "chk_complaints_legacy")
            connection.expectSqlFailure("UPDATE complaints SET $column=repeat('a',${limit + 1}) WHERE id='$LEGACY_COMPLAINT_ID'", "22001")
        }
    }

    private fun scopedReceipt(case: ScopeCase, credential: String, scope: String, test: String): String {
        val event = "repeat('Z',42)||'A'"
        connection.exec(
            connection.copyRowSql(
                "complaint_journal_publications",
                mapOf(
                    "event_id" to event,
                    "data_scope_id" to "'${case.parent}'",
                    "test_only" to (case.parent != LIVE_SCOPE).toString(),
                    "object_key" to "'matrix/publication'",
                ),
            ),
        )
        val replacements = mapOf(
            "data_scope_id" to scope,
            "test_only" to test,
            "state" to "'AUTHORIZED_DELETE'",
            "publication_ref" to event,
            "authorized_at" to FIXTURE_INSTANT,
        )
        return if (case.kind == "ordinary") {
            connection.copyRowSql("complaint_idempotency_receipts", replacements + mapOf("idempotency_key" to "'$credential'", "operation" to "'OWNER_DELETE'"))
        } else {
            connection.exec(
                "INSERT INTO complaint_installation_ids(id,data_scope_id,test_only,state,created_at) " +
                    "VALUES ('$credential',$scope,$test,'RECOVERY_RESERVED',$FIXTURE_INSTANT)",
            )
            connection.copyRowSql("installation_deletion_receipts", replacements + ("installation_id" to "'$credential'"))
        }
    }

    data class ScopeCase(val kind: String, val parent: String, val child: String, val constraint: String) {
        override fun toString(): String = "$kind $parent -> $child"
    }

    companion object {
        private const val OTHER_SCOPE = "65000000-0000-4000-8000-000000000002"

        @JvmStatic
        fun scopeCases(): List<ScopeCase> = listOf(
            "credential" to "fk_app_installations_reservation",
            "content" to "fk_complaints_resource",
            "ordinary" to "fk_complaint_receipt_publication",
            "special" to "fk_installation_receipt_publication",
        ).flatMap { (kind, constraint) ->
            listOf(
                LIVE_SCOPE to LIVE_SCOPE,
                TEST_SCOPE to TEST_SCOPE,
                LIVE_SCOPE to TEST_SCOPE,
                TEST_SCOPE to LIVE_SCOPE,
                TEST_SCOPE to OTHER_SCOPE,
            ).map { (parent, child) -> ScopeCase(kind, parent, child, constraint) }
        }
    }
}
