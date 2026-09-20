package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintSharedExtensionIT : ComplaintFixtureTest() {
    @Test
    fun `existing source grants stay valid and only the two exact scopes are representable`() {
        assertEquals(
            listOf("source-admin-mutation", "source-admin-mutation"),
            connection.strings("SELECT scope FROM admin_step_up_grants ORDER BY id"),
        )
        connection.exec("UPDATE admin_step_up_grants SET scope='complaint-moderation-mutation'")
        for (scope in listOf("complaint", "source-admin-mutation ", "COMPLAINT-MODERATION-MUTATION", "*", "")) {
            connection.expectSqlFailure("UPDATE admin_step_up_grants SET scope=${sqlText(scope)}", constraint = "chk_admin_step_up_scope")
        }
    }

    @Test
    fun `complaint actions use a closed vocabulary and noncomplaint rows keep null extension fields`() {
        for (action in ACTIONS) {
            connection.withRollbackPoint {
                connection.exec(auditUpdate("action='$action',complaint_data_scope_id='$LIVE_SCOPE',complaint_actor_kind='SYSTEM',actor_user_id=NULL"))
                assertEquals(listOf(action), connection.strings("SELECT action FROM audit_log"))
            }
        }
        for (set in listOf(
            "action='COMPLAINT_UNKNOWN'",
            "action='COMPLAINT_CREATED'",
            "complaint_data_scope_id='$LIVE_SCOPE'",
            "complaint_actor_kind='SYSTEM'",
        )) {
            connection.expectSqlFailure(auditUpdate(set), constraint = "chk_audit_complaint_shape")
        }
        connection.exec(auditUpdate("action='COMPLAINT_CREATED',complaint_data_scope_id='$TEST_SCOPE',complaint_actor_kind='ADMIN'"))
        connection.expectSqlFailure(auditUpdate("action='COMPLAINT_UNKNOWN'"), constraint = "chk_audit_complaint_shape")
        connection.expectSqlFailure(auditUpdate("action='SOURCE_CREATED'"), constraint = "chk_audit_complaint_shape")
    }

    @Test
    fun `complaint actor and scope cannot borrow a user foreign key or pass unknown SQL nulls`() {
        connection.exec(auditUpdate("action='COMPLAINT_CLOSED',complaint_data_scope_id='$LIVE_SCOPE',complaint_actor_kind='ADMIN'"))
        for (set in listOf(
            "actor_user_id=NULL",
            "complaint_data_scope_id=NULL",
            "complaint_actor_kind=NULL",
            "complaint_actor_kind='USER'",
            "complaint_data_scope_id='65000000-0000-5000-8000-000000000001'",
        )) {
            connection.expectSqlFailure(auditUpdate(set), constraint = "chk_audit_complaint_shape")
        }
        connection.expectSqlFailure(auditUpdate("actor_user_id='10000000-0000-4000-8000-000000000009'"), "23503", "fk_audit_actor")
        for (kind in listOf("INSTALLATION", "SYSTEM")) {
            connection.exec(auditUpdate("complaint_actor_kind='$kind',actor_user_id=NULL"))
            connection.expectSqlFailure(auditUpdate("actor_user_id='$ADMIN_ID'"), constraint = "chk_audit_complaint_shape")
        }
    }

    companion object {
        private val ACTIONS = listOf(
            "COMPLAINT_CREATED", "COMPLAINT_REPLIED", "COMPLAINT_CONTENT_EDITED", "COMPLAINT_STATUS_CHANGED", "COMPLAINT_CLOSED",
            "COMPLAINT_DELETE_AUTHORIZED", "COMPLAINT_DELETED", "COMPLAINT_INSTALLATION_ENROLLED", "COMPLAINT_INSTALLATION_DELETE_AUTHORIZED",
            "COMPLAINT_INSTALLATION_DELETED", "COMPLAINT_INSTALLATION_RETIRED", "COMPLAINT_RETENTION_AUTHORIZED", "COMPLAINT_RETENTION_APPLIED",
            "COMPLAINT_RECOVERY_APPLIED", "COMPLAINT_RECOVERY_CONFLICT", "COMPLAINT_IMPORT_SEALED", "COMPLAINT_IMPORT_PROMOTED",
            "COMPLAINT_TEST_RUN_ACTIVATED", "COMPLAINT_TEST_RUN_SEALED", "COMPLAINT_TEST_RUN_PURGED", "COMPLAINT_CATALOG_PROJECTED",
            "COMPLAINT_JOURNAL_RETIREMENT_AUTHORIZED", "COMPLAINT_JOURNAL_RETIRED", "COMPLAINT_CAPACITY_RECONCILED", "COMPLAINT_RESTORE_RECONCILED",
        )

        private fun auditUpdate(set: String): String = "UPDATE audit_log SET $set"
    }
}
