# Database backup and disaster recovery

This repository provides policy, role automation, selected-byte backup/restore commands and a
disposable PostgreSQL test. It does **not** establish that a managed backup service, installed
helper, writer freeze, disaster-recovery drill or achieved RPO/RTO exists. Repository verification
and the external operational gates below are separate.

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

Prefer encrypted provider-native snapshots plus continuous WAL/PITR. The logical layer supports
**Linux, Python 3.11+ standard library, PostgreSQL 17 clients and GNU tar**. It requires the Linux
`renameat2(RENAME_NOREPLACE)` primitive, hard links on the destination filesystem and successful
file/directory `fsync`. Unsupported primitives fail closed; there is no overwriting rename fallback.
These local return values are not proof of the storage provider's crash/power-loss guarantees.

Use the three executable scripts with their reviewed sibling `scripts/db/backup_bundle.py` from
one protected checkout/installation. Do not mix versions. The helper's underscore commands are
internal wrapper plumbing, **not** guard bypasses: `_backup`, `_restore-db` and `_restore-media`
enforce the same prerequisites themselves. No script is an authorization boundary against hostile
root, the executing user or a party able to replace its Python/client executables.

Before capture, exclude **all** relevant database/media writers and positively drain their in-flight
work, including tutorial ADMIN mutations. Keep that exclusion through the whole capture. A container
pause is **not drain**: a media deletion before its DB commit can create an internally inconsistent
pair even when both hashes are correct. Set the explicit attestation only after verification:

```sh
# Supply PGHOST, PGDATABASE, PGUSER and secret-manager credentials first.
export PGPORT=5432 PGSSLMODE=verify-full
export KIRA_BACKUP_WRITERS_FROZEN_AND_DRAINED=yes
scripts/db/backup.sh /secure/backups/kira-20260911T120000Z.dump /var/lib/kira/tutorial-media
```

`PGHOST`, `PGDATABASE` and `PGUSER` are required. `PGPORT` defaults to 5432 and `PGSSLMODE` to
`verify-full`. Supply `PGSSLROOTCERT` and `PGPASSWORD` or a protected `.pgpass` through the platform;
do not put credentials in command arguments, receipts, inventory or shell transcripts. An explicitly
different SSL mode is permitted for disposable local fixtures, not evidence of production TLS.

The attestation is a prerequisite, not proof of quiescence. The restricted Server3 receiver uses a
separate root-owned one-invocation writer marker, never untrusted SSH environment; its pause/daemon
STOP obligation and reconciliation procedure are in [the Server3 runbook](../deploy/server3/README.md).

The portable producer uses one new private same-filesystem stage and checks `pg_dump`, custom-dump
`pg_restore --list`, tar creation and the closed archive profile. It exclusively links completed files
without overwriting existing names and publishes **`STEM.bundle.json` last**. Output files are:

- `STEM.dump` — PostgreSQL custom dump, no ownership/ACL restoration;
- `STEM.media.tar.gz` — matched tutorial media;
- `STEM.bundle.json` — the only new completed-pair selection manifest;
- `STEM.dump.manifest` — diagnostic TOC text, **not** the selection manifest;
- `STEM.dump.sha256` and `STEM.media.tar.gz.sha256` — diagnostic relative-name checksums only.

New producers do not emit the old `.bundle.sha256` authority. They refuse collisions with it too.
On failure, do not select partials or advance an inventory entry. Only proved-owned newly linked
files may be removed; incomplete/potentially writable staging is retained for operator reconciliation.
Previously existing backups survive. Even a visible final link after storage/cleanup failure does
not override the failed command. A new successful bundle's reported SHA-256 must enter a separately
trusted inventory with its custody/provenance; computing a hash by itself does not create provenance.
Retain the exact pair, manifest and inventory in encrypted immutable off-host storage. Alert when the
last operationally successful backup is older than 15 minutes or a verification gate fails.

## Selected-byte manifest and helper ABI

V1 manifests are UTF-8 JSON, at most 4,096 bytes, with **exactly** these keys/shapes:

```json
{"schema":"kira.backup-bundle.v1","dump":{"name":"STEM.dump","bytes":123,"sha256":"LOWERCASE_64_HEX"},"media":{"name":"STEM.media.tar.gz","bytes":456,"sha256":"LOWERCASE_64_HEX"}}
```

`STEM` matches `[A-Za-z0-9][A-Za-z0-9_-]{0,95}`. The manifest is `STEM.bundle.json`; the three
explicit files must be siblings with these exact names and regular nonsymlink files. Path components
cannot be symlinks. Hash and parse the same opened manifest bytes. Duplicate/unknown keys, extra
JSON, booleans/floats for lengths, nonpositive or greater-than-`2^63-1` lengths, and non-lowercase
64-hex SHA-256 values are refused. This small backup manifest is **not** the source-config `kcj-1`
serialization contract. Verification pins exact manifest bytes, not a reserialized interpretation.

The shared public ABI is:

```text
create --bundle B --dump D --media M
verify --bundle B --dump D --media M --expected-sha256 HEX [--legacy-two-record]
publish-directory --stage S --target T
```

Invoke via `python3 -I -B scripts/db/backup_bundle.py COMMAND ...`. `create` checks the completed
members and media profile, exclusively creates v1 and prints its digest plus newline. Producers
must also perform the explicit PostgreSQL TOC gate. `verify` **requires** the independently trusted
pin, measures/hashes only the selected files and prints exactly that digest plus newline. It gives
**matched-byte identity only**, not SQL safety, capture time, provenance, DB/media consistency,
successful restoration or application readiness. No checksum-file pathname is executed or followed.

`publish-directory` is silent on success. It checks a private owned nonsymlink stage (directories
700/files600, including ancillary regular files), owned non-group/other-writable immediate parents,
absent target and same filesystem; it syncs and uses `renameat2(RENAME_NOREPLACE)`. It never deletes
or replaces an existing target. The source writers must already be conclusively finished, and the
operator must retain exclusive path/parent custody. Advisory locks and permissions do not establish
that custody against arbitrary same-user/root writers.

### Installed receiver helper custody

Server3 calls the **same reviewed source**, installed as `/usr/local/libexec/kira-backup-bundle.py`,
with `/usr/local/libexec/kira-backup-bundle.sha256`. The pin file contains exactly the trusted reviewed
lowercase SHA-256 followed by one newline. Both must be regular nonsymlink root-owned files, not
group/other-writable; every ancestor must be trusted, nonsymlink, root-owned and not writable by
non-root. The receiver verifies custody and installed bytes against that pin before invocation.
Install helper and pin from the independently reviewed source/hash together; hashing an arbitrary
installed candidate into a fresh pin is not provenance. Portable checkout invocations require their
own equivalent operator code/executable custody; the helper does not authenticate its own source.
Installation, actual modes/ancestors, reviewed-byte correspondence and package versions still need
external verification. No installed helper/pin change is implied by a repository change.

### Explicit old two-record profile — NEW backend history only

This is **not W06 Firestore recovery**. Old `STEM.bundle.sha256` selection is supported only with
`--legacy-two-record` and an independently trusted pin of that exact old manifest. Its maximum is
8,192 bytes and it must contain exactly two ASCII newline-terminated records, dump then media:

```text
64-lowercase-hex-dump-digest  /old/absolute/path/STEM.dump
64-lowercase-hex-media-digest  /old/absolute/path/STEM.media.tar.gz
```

The separator is two spaces or one space plus `*`. Original absolute paths are **inert metadata**;
only their safe same-stem basenames identify the explicitly selected sibling files. They may no
longer exist. Escaped checksums, backslashes, control characters, `..` components, wrong order,
duplicate/extra records or unmatched basenames are refused. Selected lengths are measured during
snapshotting. Relocation works; keeping valid originals cannot legitimize altered relocated bytes.
There is no implicit fallback, single-sidecar authority or resealing of history. Missing trusted
historical provenance/pin is an operator **STOP**, not permission to invent it.

### Closed media archive profile

Inspection is streaming, before any target mutation. Resolved GNU long-name/PAX names and sizes
are checked before extraction; metadata declared lengths are capped **before** payload allocation.
The supported finite profile is:

| Bound | Maximum |
|---|---:|
| Archive entries, including metadata headers | 100,000 |
| Normalized path UTF-8 bytes / components | 1,024 / 32 |
| Ordinary member bytes | 512 MiB |
| Cumulative ordinary expanded bytes | 32 GiB |
| Each GNU/PAX/long-name metadata payload | 64 KiB |

Only ordinary files/directories are accepted. Links of either kind, sparse files, devices, FIFOs,
other special types, absolute/traversing/backslash/control-containing paths, duplicate normalized
paths and file-as-parent conflicts fail. Leading `./` and one directory trailing slash are deliberately
normalized; interior empty/dot/dotdot components fail. `.`/`./` root directories and empty media are
valid. PAX keys are limited to path/size and ignored timestamp/owner fields; no sparse/link extensions.
The tar must terminate correctly, with no nonzero trailer and at most 10 KiB of additional zero
padding; gzip CRC/truncation failures are errors. Reads/decompression and duplicate tracking are
bounded; no default `extractall` is used.

Extraction creates directories700/files600 under one owned private sibling stage and ignores archive
owners, ACLs, setuid and other special modes. Available bytes/inodes are checked; implicit directories
are included in a conservative inode budget. These limits do not assert 32 GiB is available on a VPS,
reserve space, or override smaller host filesystem limits. Failed, conclusively finished extraction
stages are cleaned only when their owned identity is unchanged. Later application-user access/ownership
handoff is a separately controlled platform step; do not solve it by making staging world-writable.

## Disposable logical restore: two stages, one selected pair

Exact positional CLI (the old one-file/independent-media forms are no longer supported):

```text
verify-restore.sh BUNDLE EXPECTED_SHA256 DUMP MEDIA SOURCE_VERSION NEW_ATTEMPT_DIR NEW_MEDIA_TARGET [--legacy-two-record]
restore-media.sh ATTEMPT_DIR
```

Both enforce `PGDATABASE=kira_restore_*` with a nonempty suffix,
`KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST=yes`, `KIRA_ENVIRONMENT` not `production`, the PG endpoint/TLS
requirements above and `KIRA_RESTORE_CUSTODY_CONFIRMED=yes`. Name/opt-in guards reduce accidents;
they are not proof a connection is isolated. The operator must hold exclusive **disposable database,
attempt and target-parent custody** through both stages; prevent all independent writers, drop/recreate,
credential/endpoint changes and path replacement. This attestation is not a database isolation lock.

`NEW_ATTEMPT_DIR` must be new and private; its parent must already exist and be owned/non-other-writable.
`NEW_MEDIA_TARGET` must be **absent**, even an existing empty directory is refused. Its immediate parent
must already exist, be owned/non-group/other-writable and have no symlink ancestors. Use distinct
absolute paths under operator custody. The helper never empties/replaces an unrelated target.

```sh
export PGHOST=isolated-restore-db PGPORT=5432 PGDATABASE=kira_restore_drill PGUSER=restore_operator
export PGSSLMODE=verify-full KIRA_ENVIRONMENT=restore-test
export KIRA_ALLOW_DESTRUCTIVE_RESTORE_TEST=yes KIRA_RESTORE_CUSTODY_CONFIRMED=yes
# EXPECTED_BUNDLE_SHA256 comes from the trusted inventory, not a fresh hash/reseal of untrusted files.
scripts/db/verify-restore.sh /secure/selected/drill.bundle.json "$EXPECTED_BUNDLE_SHA256" \
  /secure/selected/drill.dump /secure/selected/drill.media.tar.gz 12 \
  /secure/attempts/drill-001 /secure/media/drill-001
scripts/db/restore-media.sh /secure/attempts/drill-001
```

Do not create the attempt or media target first. Supply local secret-manager credentials/certificates
without copying them into records. Each new attempt has one UUID and an exclusive per-attempt lock.
The helper opens the selected inputs without following links, checks regular files/lengths/hashes,
copies them in bounded chunks into private exclusive snapshot files, syncs them, and restores **only
those frozen copies**. Source originals are never consumed again. Snapshot/free-space, TOC or tar
failure cannot start the destructive restore.

| Attempt file | Meaning |
|---|---|
| `request.json` (`kira.restore-attempt.v1`) | Immutable UUID, profile/manifest pin, all three names/lengths/hashes, bounded source version, absolute intended target and nonsecret host/port/database/user. |
| `db-started.json` | Written durably before `pg_restore`; binds UUID and request digest. Started without a DB receipt is unverified/STOP. |
| `db.json` (`kira.restore-db.v1`) | Written only after restore **and** the source-history predicate; binds request/UUID/selected bytes, endpoint, actual DB identity/OID, source version and history digest. |
| `media-started.json` | Binds that request and exact intended target **before** extraction/publication. Incomplete stage means STOP. |
| `pair.json` (`kira.restore-pair.v1`) | Written only after no-clobber media publication; binds request/UUID, DB-receipt digest and target path/device/inode. This is local paired-stage evidence, not deployment success. |
| `STOP.json` | Best-effort sticky failure disposition: operator reconciliation only. Absence is not success after interruption/storage failure. |

`pg_restore` retains `--exit-on-error --clean --if-exists --no-owner --no-acl --single-transaction`.
After it returns successfully, fixed SQL returns machine JSON. The helper requires nonempty versioned
Flyway history, **every row** `success=true`, strictly ordered installed ranks, and the last installed
**versioned** row exactly equal to `SOURCE_VERSION`. A failed earlier row is not hidden by a successful
latest row. Versions are at most 64 ASCII numeric characters separated by dots/underscores, compared
as returned, never interpolated into SQL or selected using lexical `MAX(version)`.

The history digest covers each row's installed rank, version, type, script, checksum and success.
The media stage accepts **only the attempt**, rechecks all frozen bytes and the request/DB receipt,
then queries actual DB identity/OID and full successful history again, including immediately before
publication. This detects identity/history replacement, not arbitrary same-history business-data
mutations: retained exclusive custody is mandatory. A name or historical receipt cannot permanently
authenticate a freely mutable/dropped/recreated database. Media A cannot be replaced by separately
valid media B. This is not a DB/filesystem atomic transaction.

### Failure disposition — no same-attempt retry

Any nonzero/interrupted command, started but incomplete stage, receipt write failure or ambiguous
publication is **STOP**. Keep the exact attempt and any published target. There is no automatic
reconcile, rollback, deletion of a published target or same-attempt retry. A completed invocation
cannot be replayed to return cached success; a repeat refuses without rewriting completed history.
Positively reconcile the original action and its effects, then use a **new** attempt/fresh disposable
database/target when required. Never infer SQL rollback from a later failed history query.

Receipts/manifests use exclusive temporary files, no-clobber hard links, fsync and exact-inode cleanup.
A link may become visible and then fsync/cleanup fail. That invocation **failed**, even if the JSON is
readable. The helper removes only its exact new link and attempts sticky STOP; storage can reject
both unlink and STOP. In that case there is **no fictitious durable negative proof**. External custody
must prohibit advancing **any failed attempt**, regardless of visible receipt files. If media was
published but its pair receipt failed, retain it as unverified and report that partial outcome; no
filesystem/DB rollback is claimed. Catchable error handling is not SIGKILL/power-loss proof.

Database-stage or paired-stage receipts must not advance operational restore-freshness telemetry,
authorize traffic or assert application integrity. A source-V12 backup legitimately passes with
`SOURCE_VERSION=12` while V13 is pending. Exact-release migration/target-version/Flyway checksum
validation is a separate subsequent gate, never hidden inside the as-restored source predicate.

## Restoration and PITR procedure

1. Declare the incident, freeze writes/rollouts, record the requested recovery timestamp, and preserve
   evidence. Do not modify the affected database in place.
2. Provision a new isolated PostgreSQL instance and restore the newest base backup plus WAL to the
   selected point. For a logical verification target only, use the two-stage selected-pair procedure
   above; the scripts intentionally refuse in-place production restoration.
3. Verify TLS, roles/grants, Flyway history checksums, row counts, constraints, publication pointers,
   latest signed document checksum, admin availability, tutorial media checksums, and completion/audit
   retention expectations. Complete the bound media stage into its absent target, then arrange
   least-privilege application access and point `KIRA_TUTORIAL_MEDIA_DIRECTORY` at it. Start the backend in quarantine so startup
   validation checks every published file/reference before traffic switches. This supports
   relocation without preserving the old host path.
4. Run the exact release image migration Job and separately assert its expected target version plus
   successful Flyway validation/checksums. Flyway clean, baselining, out-of-order migrations, and
   reverse migrations remain disabled. A failed migration is repaired only after the cause is fixed
   and a forward-compatible recovery migration is reviewed and tested.
5. Point a quarantined application deployment at the restored database and run authentication,
   publication, ETag/signature, and read-only API smoke tests. Compare the restored timestamp with the
   15-minute RPO.
6. Switch traffic using the platform's controlled endpoint/DNS mechanism, monitor error/latency and
   database saturation, then resume writes. Preserve the old instance until the incident is closed.
7. Record achieved RPO/RTO, checksums, commands, approvals, and gaps; remediate any missed target.

## Verification scope and external gates

The sibling `scripts/ci/test_backup_bundle.py` uses the actual helper/wrappers with finite fixtures
and explicit no-fallthrough PG command stubs: selected-pair relocation/mixing, profiles, tar bounds,
snapshots, predicates, locks, no-clobber publication and real partial-link/rename failure effects.
It is discovered with the existing image/receiver unittest suite, not a new framework. Stub results
do not prove PostgreSQL semantics, daemon termination, actual writer drain or crash durability.

`DatabaseBackupRestoreIT` uses real `postgres:17.6-alpine`, custom dump **file-copy streams** (never
decoded exec stdout), the real helper/restore scripts, representative data/media, V12 and V13 source
verification, separate V13 migration/validation, real stored history failures and between-stage
history mutation. Linux Python/GNU tar and Docker CLI are required; fixed test-only bridges invoke
the exact container's real PG17 clients so no host PG version or mock SQL can supply the oracle.
The CLI producer's command/failure ordering is covered by the offline tests; the real IT creates
the dump directly into a container file before transferring bytes. Tests must actually run under a
separately authorized validation batch before any passing claim is made.

**EXTERNAL VERIFICATION REQUIRED:** independently trusted backup/helper inventory; installed code,
pin, ancestors and clients; actual writer freeze/drain and exclusive custody; daemon late-effect
reconciliation; storage capacity/fsync/crash behavior; encrypted immutable off-host/PITR/retention;
monthly restore drills; installation-key recovery; quarantine startup/reference integrity, exact
release images, traffic/monitoring readiness and achieved RPO/RTO. W06 remains excluded. Local stage
receipts cannot substitute for any of these operational proofs.

## Release rollback and failed migration

Application-only releases roll back to the previous immutable image digest. Schema migrations are
forward-only and expand/contract: deploy additive schema first, deploy compatible code, backfill in
bounded batches, and remove obsolete schema only in a later release. If deployment fails after an
additive migration, roll the application back while retaining the compatible schema. If a migration
partially fails, keep traffic on the prior release, diagnose from Flyway/database logs, and ship a
tested forward-recovery migration—never edit an applied migration or invoke `flyway clean`.
