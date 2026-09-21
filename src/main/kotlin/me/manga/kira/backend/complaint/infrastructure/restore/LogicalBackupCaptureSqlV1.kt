package me.manga.kira.backend.complaint.infrastructure.restore

/**
 * Closed psql protocol. NO DML/DDL, M/E advisory locks, epoch advance, gate write, lease or checkpoint.
 * ACCESS EXCLUSIVE is not allowed in a read-only transaction: the exporter is RR READ WRITE with
 * only these fixed lock/SELECT commands. pg_dump imports it into its own RR READ ONLY transaction.
 */
internal object LogicalBackupCaptureSqlV1 {
    val export = """
        SET statement_timeout = '1000ms';
        SET lock_timeout = '100ms';
        SET idle_in_transaction_session_timeout = '5000ms';
        SET idle_session_timeout = '5000ms';
        SET search_path = pg_catalog;
        SET stats_fetch_consistency = 'none';
        BEGIN ISOLATION LEVEL REPEATABLE READ READ WRITE;
        LOCK TABLE ONLY public.complaint_journal_control IN ACCESS EXCLUSIVE MODE;
        SELECT pg_catalog.json_build_object(
          'kind','export','snapshot',pg_catalog.pg_export_snapshot(),
          'version',pg_catalog.current_setting('server_version_num')::int,
          'database',pg_catalog.current_database(),'role',current_user,
          'database_oid',(SELECT oid::bigint FROM pg_catalog.pg_database WHERE datname=pg_catalog.current_database()),
          'guard_oid',c.oid::bigint,'guard_valid',c.relkind='r' AND c.relpersistence='p' AND NOT c.relisshared
            AND NOT EXISTS (SELECT 1 FROM pg_catalog.pg_depend d WHERE d.classid='pg_catalog.pg_class'::regclass
              AND d.objid=c.oid AND d.deptype='e'),
          'pid',pg_catalog.pg_backend_pid(),
          'backend_started',(extract(epoch FROM a.backend_start)*1000000)::bigint,
          'transaction_started',(extract(epoch FROM a.xact_start)*1000000)::bigint,
          'observed',(extract(epoch FROM pg_catalog.clock_timestamp())*1000000)::bigint,
          'xmin',pg_catalog.pg_snapshot_xmin(pg_catalog.pg_current_snapshot())::text,
          'server_address',pg_catalog.inet_server_addr()::text,'server_port',pg_catalog.inet_server_port(),
          'client_address',pg_catalog.inet_client_addr()::text,'client_port',pg_catalog.inet_client_port())
        FROM pg_catalog.pg_class c JOIN pg_catalog.pg_namespace n ON n.oid=c.relnamespace,
             pg_catalog.pg_stat_activity a
        WHERE n.nspname='public' AND c.relname='complaint_journal_control' AND a.pid=pg_catalog.pg_backend_pid();
    """.trimIndent() + "\n"

    /** Only scalar fields from owned native observations enter this fixed statement. */
    fun observe(exported: LogicalCaptureExportV1, socket: LogicalCaptureSocketV1, application: String): String {
        captureRequire(Regex("kira-qcap-d-[0-9a-f]{32}").matches(application), LogicalCaptureFailureV1.INPUT)
        return """
            DO ${'$'}q${'$'} BEGIN PERFORM pg_catalog.pg_stat_clear_snapshot(); END ${'$'}q${'$'};
            WITH tagged AS MATERIALIZED (
              SELECT pid,backend_start,xact_start,backend_xmin,datid,usename,client_addr,client_port,state,
                     wait_event_type,wait_event,backend_type
              FROM pg_catalog.pg_stat_activity WHERE application_name='$application'
            ), exact AS MATERIALIZED (
              SELECT * FROM tagged WHERE datid=${exported.databaseOid} AND usename=current_user
                AND client_addr='127.0.0.1'::inet AND client_port=${socket.clientPort}
            )
            SELECT pg_catalog.json_build_object('kind','wait','tag_count',(SELECT count(*) FROM tagged),
              'exact_count',(SELECT count(*) FROM exact),'exporter_pid',pg_catalog.pg_backend_pid(),
              'exporter_started',(SELECT (extract(epoch FROM backend_start)*1000000)::bigint
                FROM pg_catalog.pg_stat_activity WHERE pid=pg_catalog.pg_backend_pid()),
              'guard_held',EXISTS (SELECT 1 FROM pg_catalog.pg_locks WHERE pid=pg_catalog.pg_backend_pid()
                AND locktype='relation' AND database=${exported.databaseOid} AND relation=${exported.guardOid}
                AND mode='AccessExclusiveLock' AND granted),
              'observed',(extract(epoch FROM pg_catalog.clock_timestamp())*1000000)::bigint,
              'backend',(SELECT pg_catalog.json_build_object('pid',e.pid,
                'backend_started',(extract(epoch FROM e.backend_start)*1000000)::bigint,
                'transaction_started',(extract(epoch FROM e.xact_start)*1000000)::bigint,
                'xmin',e.backend_xmin::text,'active',e.state='active','client',e.backend_type='client backend',
                'waiting',e.wait_event_type='Lock' AND e.wait_event='relation',
                'guard_waits',(SELECT count(*) FROM pg_catalog.pg_locks l WHERE l.pid=e.pid
                  AND l.locktype='relation' AND l.database=${exported.databaseOid} AND l.relation=${exported.guardOid}
                  AND l.mode='AccessShareLock' AND NOT l.granted),
                'blockers',pg_catalog.pg_blocking_pids(e.pid)) FROM exact e));
        """.trimIndent() + "\n"
    }

    fun release(exported: LogicalCaptureExportV1): String = """
        ROLLBACK;
        SELECT pg_catalog.json_build_object('kind','released','pid',pg_catalog.pg_backend_pid(),
          'guard_absent',NOT EXISTS (SELECT 1 FROM pg_catalog.pg_locks WHERE pid=pg_catalog.pg_backend_pid()
            AND locktype='relation' AND database=${exported.databaseOid} AND relation=${exported.guardOid}
            AND mode='AccessExclusiveLock' AND granted));
        \quit
    """.trimIndent() + "\n"
}
