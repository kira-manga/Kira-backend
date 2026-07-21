# Database backup and disaster recovery

This repository provides policy, least-privilege role automation, backup/restore commands, and a
disposable restoration test. It does not claim that any managed backup service is configured.

## Targets and ownership

- Default recovery point objective (RPO): **15 minutes**. Configure continuous WAL archiving/PITR or
  provider-native equivalent to retain at least 15-minute recovery granularity.
- Default recovery time objective (RTO): **2 hours**, including restore, integrity validation,
  migration validation, application rollout, and business smoke tests.
- Retain PITR/WAL for 14 days, daily logical backups for 35 days, monthly backups for 12 months, and
  keep at least one encrypted copy in a separate failure domain/account. Legal policy may require a
  longer window; never shorten it silently.
- The database/platform owner schedules backups and alerts; the service owner verifies a disposable
  restoration at least monthly and before schema-changing releases.

## Privilege separation

Run `scripts/db/create-roles.sql` as the database owner. Application pods receive a login granted only
the `kira_runtime` group role. The one-shot migration Job receives a separate login granted
`kira_migrator`. Runtime pods have no schema-create/DDL privilege, and Flyway is disabled in the prod
application profile. Credentials are injected by the platform secret manager and rotated separately.

## Backup

Prefer encrypted provider-native snapshots plus continuous WAL/PITR. As the portable logical layer,
freeze tutorial ADMIN mutations, then run
`scripts/db/backup.sh /secure/absolute/path/kira-YYYYmmddTHHMMSSZ.dump /var/lib/kira/tutorial-media`
with `PGHOST`, `PGDATABASE`, `PGUSER`, `PGPASSWORD` (or a `.pgpass` supplied by the secret manager),
`PGSSLROOTCERT`, and `PGSSLMODE=verify-full`. Store the matched dump, media archive, individual
checksums, bundle checksum, and manifest together in encrypted immutable storage. Alert when the
most recent successful backup is older than 15 minutes or checksum/catalog verification fails.

## Restoration and PITR procedure

1. Declare the incident, freeze writes/rollouts, record the requested recovery timestamp, and preserve
   evidence. Do not modify the affected database in place.
2. Provision a new isolated PostgreSQL instance and restore the newest base backup plus WAL to the
   selected point. For a logical verification target only, use `scripts/db/verify-restore.sh`; its
   database-name, environment, and explicit-authorization guards prevent accidental production use.
3. Verify TLS, roles/grants, Flyway history checksums, row counts, constraints, publication pointers,
   latest signed document checksum, admin availability, tutorial media checksums, and completion/audit retention expectations.
   Restore media into a new empty directory/volume with
   `KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST=yes scripts/db/restore-media.sh BACKUP.media.tar.gz EMPTY_TARGET`,
   then point `KIRA_TUTORIAL_MEDIA_DIRECTORY` at it. Start the backend in quarantine so startup
   validation checks every published file/reference before traffic switches. This supports
   relocation without preserving the old host path.
4. Run the exact release image migration Job. Flyway clean, baselining, out-of-order migrations, and
   reverse migrations remain disabled. A failed migration is repaired only after the cause is fixed
   and a forward-compatible recovery migration is reviewed and tested.
5. Point a quarantined application deployment at the restored database and run authentication,
   publication, ETag/signature, and read-only API smoke tests. Compare the restored timestamp with the
   15-minute RPO.
6. Switch traffic using the platform's controlled endpoint/DNS mechanism, monitor error/latency and
   database saturation, then resume writes. Preserve the old instance until the incident is closed.
7. Record achieved RPO/RTO, checksums, commands, approvals, and gaps; remediate any missed target.

## Release rollback and failed migration

Application-only releases roll back to the previous immutable image digest. Schema migrations are
forward-only and expand/contract: deploy additive schema first, deploy compatible code, backfill in
bounded batches, and remove obsolete schema only in a later release. If deployment fails after an
additive migration, roll the application back while retaining the compatible schema. If a migration
partially fails, keep traffic on the prior release, diagnose from Flyway/database logs, and ship a
tested forward-recovery migration—never edit an applied migration or invoke `flyway clean`.
