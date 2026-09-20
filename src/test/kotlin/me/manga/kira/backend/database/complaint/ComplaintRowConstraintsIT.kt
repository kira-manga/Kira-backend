package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

class ComplaintRowConstraintsIT : ComplaintFixtureTest() {
    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRows")
    fun `malformed identity content and closure rows are rejected`(case: SqlViolation) {
        connection.expectSqlFailure(case.sql, case.state, case.constraint)
    }

    @Test
    fun `all new nonnullable columns reject explicit null with the correct column diagnostic`() {
        for (table in ComplaintMigrationIT.expectedTables) {
            val columns = connection.strings(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() " +
                    "AND table_name=${sqlText(table)} AND is_nullable='NO' ORDER BY ordinal_position",
            )
            assertTrue(columns.isNotEmpty(), table)
            for (column in columns) {
                val error = connection.expectSqlFailure("UPDATE $table SET $column=NULL", "23502")
                assertTrue(error.message.orEmpty().contains("column \"$column\""), "$table: ${error.message}")
            }
        }
    }

    @Test
    fun `required stored timestamps reject both infinities in every new timestamp-bearing table`() {
        for (table in ComplaintMigrationIT.expectedTables) {
            val columns = connection.strings(
                "SELECT column_name FROM information_schema.columns WHERE table_schema=current_schema() " +
                    "AND table_name=${sqlText(table)} AND is_nullable='NO' AND data_type='timestamp with time zone'",
            )
            for (column in columns) {
                for (value in listOf("infinity", "-infinity")) {
                    connection.expectSqlFailure("UPDATE $table SET $column='$value'::timestamptz")
                }
            }
        }
    }

    @Test
    fun `code point and UTF8 boundaries preserve Arabic supplementary combining and permitted whitespace`() {
        val limits = mapOf(
            "subject" to 200,
            "body" to 1000,
            "closure_reason" to 500,
            "app_version" to 64,
            "os_version" to 128,
            "manufacturer" to 128,
            "device_model" to 128,
        )
        for ((column, maximum) in limits) {
            for (character in listOf("ع", "😀", "\u0301")) {
                val exact = character.repeat(maximum)
                connection.exec(ownedUpdate("$column=${sqlText(exact)}"))
                assertEquals(listOf(exact), connection.strings("SELECT $column FROM complaints WHERE id='$OWNED_COMPLAINT_ID'"))
                connection.expectSqlFailure(ownedUpdate("$column=${sqlText(exact + character)}"), constraint = "chk_complaints_text")
            }
        }
        connection.exec(ownedUpdate("body=E'line one\\nline two\\tend'"))
        assertEquals(listOf("line one\nline two\tend"), connection.strings("SELECT body FROM complaints WHERE id='$OWNED_COMPLAINT_ID'"))
        for (column in listOf("subject", "body", "closure_reason")) {
            connection.exec(ownedUpdate("$column='x'"))
            connection.expectSqlFailure(ownedUpdate("$column=''"), constraint = "chk_complaints_text")
        }
        for (column in listOf("app_version", "os_version", "manufacturer", "device_model")) {
            connection.exec(ownedUpdate("$column=''"))
        }
        // PostgreSQL rejects NUL before any text CHECK; do not mistake it for a schema predicate.
        connection.expectSqlFailure(ownedUpdate("body=convert_from(decode('00','hex'),'UTF8')"), "22021")
    }

    @Test
    fun `forbidden C0 DEL and C1 controls cannot be stored`() {
        val forbidden = (1..31).filter { it != 9 && it != 10 } + (127..159)
        for (code in forbidden) {
            connection.expectSqlFailure(ownedUpdate("body='a'||chr($code)||'b'"), constraint = "chk_complaints_text")
        }
    }

    @Test
    fun `legacy closure exception unknown status and nested notice replies are representable`() {
        connection.exec(
            "UPDATE complaints SET status='CLOSED',closure_provenance='LEGACY' WHERE id='$LEGACY_COMPLAINT_ID'",
        )
        assertEquals(
            listOf("true"),
            connection.strings(
                "SELECT (closure_reason IS NULL AND closed_at IS NULL AND closure_actor_id IS NULL)::text " +
                    "FROM complaints WHERE id='$LEGACY_COMPLAINT_ID'",
            ),
        )
        connection.exec(
            "UPDATE complaints SET kind='REPLY',legacy_reconciliation_code='AMBIGUOUS_NOTICE_PARENT' " +
                "WHERE id='$LEGACY_COMPLAINT_ID'",
        )
        connection.exec("UPDATE complaints SET status='PINNED',closure_provenance=NULL WHERE id='$LEGACY_COMPLAINT_ID'")
        connection.exec(ownedUpdate("kind='REPLY',type='CUSTOM',subject=NULL,notice_key='fixture.notice',parent_resource_id='$REPLY_ID'"))
    }

    @Test
    fun `parent erasure preserves child identity version and no-prose reservation`() {
        val before = connection.strings("SELECT parent_resource_id::text||'|'||version::text FROM complaints WHERE id='$REPLY_ID'")
        connection.exec("DELETE FROM complaints WHERE id='$NOTICE_ID'")
        connection.exec("UPDATE complaint_resource_ids SET state='DELETED',deleted_at=$FIXTURE_INSTANT WHERE id='$NOTICE_ID'")
        assertEquals(before, connection.strings("SELECT parent_resource_id::text||'|'||version::text FROM complaints WHERE id='$REPLY_ID'"))
        connection.expectSqlFailure("DELETE FROM complaint_resource_ids WHERE id='$NOTICE_ID'", "23503", "fk_complaints_parent")
    }

    @Test
    fun `admin closure refuses actor deletion and clears its entire tuple on reopening`() {
        // Remove unrelated V13 references; leave the real complaint closure as the only user reference.
        connection.exec("UPDATE complaints SET closure_actor_id='10000000-0000-4000-8000-000000000002' WHERE id='$OWNED_COMPLAINT_ID'")
        connection.exec("DELETE FROM completion_results; DELETE FROM completion_requests")
        connection.expectSqlFailure("DELETE FROM users WHERE id='10000000-0000-4000-8000-000000000002'", "23503", "fk_complaints_closure_actor")
        connection.exec(ownedUpdate("status='OPEN',closure_reason=NULL,closed_at=NULL,closure_provenance=NULL,closure_actor_id=NULL"))
        connection.exec("DELETE FROM users WHERE id='10000000-0000-4000-8000-000000000002'")
    }

    @Test
    fun `installation terminal credential retains only replay tuple for 192 elapsed hours across DST`() {
        connection.exec("SET LOCAL TimeZone='America/New_York'")
        connection.exec("DELETE FROM complaints WHERE owner_id='$INSTALLATION_ID'")
        connection.exec(
            "UPDATE app_installations SET state='DELETED',platform=NULL,owner_reference=NULL,last_authenticated_at=NULL," +
                "deleted_at='2026-03-07T12:00:00-05',verifier_expires_at='2026-03-07T12:00:00-05'::timestamptz+interval '192 hours'",
        )
        assertEquals(listOf("691200.000000"), connection.strings("SELECT extract(epoch FROM verifier_expires_at-deleted_at)::text FROM app_installations"))
        connection.expectSqlFailure(
            "UPDATE app_installations SET verifier_expires_at=deleted_at+interval '8 days'",
            constraint = "chk_app_installations_shape",
        )
        for (column in listOf("secret_verifier", "deleted_at", "verifier_expires_at")) {
            connection.expectSqlFailure("UPDATE app_installations SET $column=NULL", constraint = "chk_app_installations_shape")
        }
    }

    @Test
    fun `scoped owner and parent foreign keys reject different test runs even when all shapes are valid`() {
        val other = "65000000-0000-4000-8000-000000000002"
        connection.exec(
            "UPDATE complaints SET status='OPEN',closure_reason=NULL,closed_at=NULL,closure_provenance=NULL,closure_actor_id=NULL " +
                "WHERE id='$OWNED_COMPLAINT_ID'",
        )
        // Reservations without content/credential consumers are legal storage staging; writers reconcile pairing later.
        connection.exec("INSERT INTO complaint_resource_ids SELECT '61000000-0000-4000-8000-000000000009','$TEST_SCOPE',true,'LIVE',$FIXTURE_INSTANT,NULL")
        connection.exec("INSERT INTO complaint_installation_ids SELECT '60000000-0000-4000-8000-000000000009','$other',true,'ACTIVE',$FIXTURE_INSTANT,NULL")
        connection.exec(
            "INSERT INTO app_installations SELECT '60000000-0000-4000-8000-000000000009','$other',true,secret_verifier,platform,state," +
                "credential_version,'68000000-0000-4000-8000-000000000009',created_at,last_authenticated_at," +
                "deleted_at,verifier_expires_at,version FROM app_installations",
        )
        val copied = "INSERT INTO complaints(id,data_scope_id,test_only,owner_id,ownership,kind,type,status,subject,body," +
            "platform,os_version,manufacturer,device_model,created_at,updated_at,version) SELECT " +
            "'61000000-0000-4000-8000-000000000009','$TEST_SCOPE',true,'60000000-0000-4000-8000-000000000009'," +
            "ownership,kind,type,status,subject,body,platform,os_version,manufacturer,device_model,created_at,updated_at,version " +
            "FROM complaints WHERE id='$OWNED_COMPLAINT_ID'"
        connection.expectSqlFailure(copied, "23503", "fk_complaints_owner")
        connection.exec("INSERT INTO complaint_resource_ids SELECT '61000000-0000-4000-8000-000000000008','$other',true,'LIVE',$FIXTURE_INSTANT,NULL")
        connection.expectSqlFailure(
            "UPDATE complaints SET parent_resource_id='61000000-0000-4000-8000-000000000008' WHERE id='$REPLY_ID'",
            "23503",
            "fk_complaints_parent",
        )
    }

    companion object {
        @JvmStatic
        fun invalidRows(): List<SqlViolation> = identityViolations() + contentViolations()

        private fun identityViolations(): List<SqlViolation> = buildList {
            val reservation = "UPDATE complaint_installation_ids SET "
            for (scope in listOf(
                "'$LIVE_SCOPE',true",
                "'$TEST_SCOPE',false",
                "'65000000-0000-5000-8000-000000000001',true",
                "'65000000-0000-4000-7000-000000000001',true",
            )) {
                add(SqlViolation("reject scope $scope", "$reservation(data_scope_id,test_only)=($scope)", constraint = "chk_complaint_installation_scope"))
            }
            for (state in listOf("'ENABLED'", "'DELETED'", "'RETIRED'")) {
                add(
                    SqlViolation(
                        "reservation state $state without terminal time",
                        "${reservation}state=$state",
                        constraint = "chk_complaint_installation_state",
                    ),
                )
            }
            for (column in listOf("secret_verifier", "platform", "owner_reference", "last_authenticated_at")) {
                add(SqlViolation("active credential missing $column", "UPDATE app_installations SET $column=NULL", constraint = "chk_app_installations_shape"))
            }
            for (size in listOf(0, 31, 33)) {
                add(
                    SqlViolation(
                        "verifier size $size",
                        "UPDATE app_installations SET secret_verifier=decode(repeat('ab',$size),'hex')",
                        constraint = "chk_app_installations_verifier",
                    ),
                )
            }
            add(SqlViolation("credential wrong platform", "UPDATE app_installations SET platform='DESKTOP'", constraint = "chk_app_installations_shape"))
            add(
                SqlViolation(
                    "credential owner reference reuses installation",
                    "UPDATE app_installations SET owner_reference=id",
                    constraint = "chk_app_installations_shape",
                ),
            )
            add(
                SqlViolation(
                    "credential nonpositive version",
                    "UPDATE app_installations SET credential_version=0",
                    constraint = "chk_app_installations_versions",
                ),
            )
            add(
                SqlViolation(
                    "live resource has deletion time",
                    "UPDATE complaint_resource_ids SET deleted_at=$FIXTURE_INSTANT",
                    constraint = "chk_complaint_resource_state",
                ),
            )
        }

        private fun contentViolations(): List<SqlViolation> = buildList {
            for ((set, constraint) in listOf(
                "owner_id=NULL" to "chk_complaints_ownership",
                "status='UNKNOWN',closure_reason=NULL,closed_at=NULL,closure_provenance=NULL,closure_actor_id=NULL" to "chk_complaints_status",
                "kind='NOTICE'" to "chk_complaints_content",
                "kind='MESSAGE'" to "chk_complaints_content",
                "type=NULL" to "chk_complaints_content",
                "type='OTHER'" to "chk_complaints_content",
                "subject=NULL" to "chk_complaints_content",
                "body=NULL" to "chk_complaints_content",
                "notice_key='unsafe/key'" to "chk_complaints_content",
                "platform=NULL" to "chk_complaints_platform",
                "version=0" to "chk_complaints_version",
                "status='OPEN'" to "chk_complaints_closure",
                "legacy_collection='complaints'" to "chk_complaints_legacy",
            )) {
                add(SqlViolation("owned $set", ownedUpdate(set), constraint = constraint))
            }
            for (column in listOf("closure_reason", "closed_at", "closure_provenance", "closure_actor_id")) {
                add(SqlViolation("normal closure missing $column", ownedUpdate("$column=NULL"), constraint = "chk_complaints_closure"))
            }
            for (column in listOf("legacy_collection", "legacy_key_id", "legacy_document_hmac", "legacy_payload_hash", "legacy_reconciliation_code")) {
                add(
                    SqlViolation(
                        "legacy missing $column",
                        "UPDATE complaints SET $column=NULL WHERE id='$LEGACY_COMPLAINT_ID'",
                        constraint = "chk_complaints_legacy",
                    ),
                )
            }
            for (set in listOf("body='text'", "subject='text'", "platform='ANDROID'", "status='OPEN'", "notice_key=NULL")) {
                add(SqlViolation("notice $set", "UPDATE complaints SET $set WHERE id='$NOTICE_ID'", constraint = "chk_complaints_content"))
            }
            for (set in listOf("parent_resource_id=id", "parent_resource_id=NULL")) {
                add(SqlViolation("reply $set", "UPDATE complaints SET $set WHERE id='$REPLY_ID'", constraint = "chk_complaints_parent"))
            }
        }
    }
}

data class SqlViolation(val description: String, val sql: String, val state: String = "23514", val constraint: String? = null) {
    override fun toString(): String = description
}

fun ownedUpdate(set: String): String = "UPDATE complaints SET $set WHERE id='$OWNED_COMPLAINT_ID'"
