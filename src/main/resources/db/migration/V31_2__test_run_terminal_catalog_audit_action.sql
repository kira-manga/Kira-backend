-- Terminal PROJECT persists and rereads this dedicated scoped audit action.
-- Extend only the closed vocabulary; preserve the existing actor/scope checks and rows.
ALTER TABLE audit_log
    DROP CONSTRAINT chk_audit_complaint_shape,
    ADD CONSTRAINT chk_audit_complaint_shape CHECK ((
        (left(action, 10) COLLATE "C" <> 'COMPLAINT_' AND complaint_data_scope_id IS NULL AND complaint_actor_kind IS NULL)
        OR (action COLLATE "C" IN ('COMPLAINT_CREATED', 'COMPLAINT_REPLIED', 'COMPLAINT_CONTENT_EDITED',
                'COMPLAINT_STATUS_CHANGED', 'COMPLAINT_CLOSED', 'COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED',
                'COMPLAINT_INSTALLATION_ENROLLED', 'COMPLAINT_INSTALLATION_DELETE_AUTHORIZED',
                'COMPLAINT_INSTALLATION_DELETED', 'COMPLAINT_INSTALLATION_RETIRED',
                'COMPLAINT_RETENTION_AUTHORIZED', 'COMPLAINT_RETENTION_APPLIED', 'COMPLAINT_RECOVERY_APPLIED',
                'COMPLAINT_RECOVERY_CONFLICT', 'COMPLAINT_IMPORT_SEALED', 'COMPLAINT_IMPORT_PROMOTED',
                'COMPLAINT_TEST_RUN_ACTIVATED', 'COMPLAINT_TEST_RUN_SEALED', 'COMPLAINT_TEST_RUN_PURGED',
                'COMPLAINT_TEST_RUN_TERMINAL_CATALOG_PROJECTED',
                'COMPLAINT_CATALOG_PROJECTED', 'COMPLAINT_JOURNAL_RETIREMENT_AUTHORIZED', 'COMPLAINT_JOURNAL_RETIRED',
                'COMPLAINT_CAPACITY_RECONCILED', 'COMPLAINT_RESTORE_RECONCILED')
            AND complaint_scope_valid(complaint_data_scope_id, complaint_data_scope_id <> '00000000-0000-0000-0000-000000000000'::uuid)
            AND ((complaint_actor_kind = 'ADMIN' AND actor_user_id IS NOT NULL)
                OR (complaint_actor_kind IN ('INSTALLATION', 'SYSTEM') AND actor_user_id IS NULL)))
    ) IS TRUE);
