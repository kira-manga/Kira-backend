# Logical-backup quarantine capture v1 — source candidate

**NOT_COMPILED / NOT_RUN / NOT_QUALIFIED.** This is a dormant operator primitive, not a bean, CLI,
HTTP route, JDBC/campaign registration, accepted restore source or deployment approval.

`ComplaintLogicalBackupCaptureV1.prepare(input).capture()` is one-shot. Its only completed variant
is **QUARANTINED / NOT_ACCEPTED**. No caller checkpoint, imported Boolean or local manifest can
upgrade it. There is no signing, REGISTER_SOURCE, catalog mutation, restore identity installation
or traffic-opening path.

## Deliberately missing authority

This first slice does **not** acquire journal M/E, rotate/advance an epoch, write a control/gate row,
manufacture an authorization closure, or reuse TEST historical recurrence as LIVE/pre-run authority.
The `complaint_journal_control` **relation lock is only an observation aid**, not a V6 journal
boundary. No control-row value is read or rewritten. The exporter uses RR READ WRITE because
PostgreSQL forbids this lock mode in a read-only transaction; its closed SQL performs no row writes.

Genuine pre-boundary reconciliation, committed/fully covered boundary cuts, durable authorization
closure, post-capture reconciliation, source/writer/restore/catalog identity binding, acceptance and
new-backend restore integration remain **internal unfinished implementation**. This primitive does
not make those dependencies external or complete. No migration or old accepted-source schema changes.

The selected media archive is **bytes only**. Its supplied hash, safe-tar validation and pairing in
the v1 manifest do not prove media writer exclusion/drain or SQL/media consistency. Every result
records `media_writer_exclusion_and_drain=NOT_PROVED`. A genuine separately enforced exclusion/drain
must span selection/export before any later acceptance design can use a pair. The existing backup
wrapper's freeze/drain requirement is unchanged; this primitive never calls `_backup` or pretends
that its snapshot-less dump used an exported snapshot.

## Supported native profile

* Linux; direct TCP **127.0.0.1 only**, same network namespace. No Docker port forwarding, NAT,
  proxies, SSH tunnels, Unix sockets, IPv6, DNS/multihost/service configuration or alternate endpoint.
  The server's actual local/client address and port must agree with the child socket observation.
* PostgreSQL server **17.6** and reviewed **REL_17_6** psql/pg_dump/pg_restore builds. The operator
  supplies exact SHA-256 pins and absolute nonsymlink executable paths; there are no default pins,
  PATH discovery or version-only approval. A different build requires independent source/behavior
  qualification; simply supplying its digest does not provide that qualification.
* Trusted installed dynamic loader/libraries, kernel/procfs and exclusive operator filesystem
  custody are prerequisites. ELF image hashes do not attest loaded libraries or hostile root/same-uid
  mutation. All path components must be nonsymlink, root/operator-owned and not group/world-writable.
  Use reviewed local storage, not network/FUSE filesystems. `/tmp` ancestry is deliberately refused.
* verify-full TLS with a certificate valid for 127.0.0.1; SCRAM and required channel binding.
  One exact private 0600 `.pgpass` input line for the selected host/port/database/user, no wildcard
  fields. A staged private copy is removed only after local native custody is known ended. Passwords
  are never command arguments, environment values, record hashes or logs. The environment is cleared.
* Same fixed role for exporter and dump, with reviewed table-lock/dump/statistics visibility rights.
  This code grants no privilege and does not assume a SELECT-only backup role can take the guard.
  The guard must be the actual permanent ordinary, non-extension-member public table.
* One custom dump, no filtering/parallel workers/arbitrary options. pg_dump stdout goes to an owned
  CREATE_NEW/0600 bounded file, never terminal logs. Child stderr is discarded, not recorded.

## Native import handoff

The psql owner exports a real RR snapshot while holding a short ACCESS EXCLUSIVE lock on that fixed
guard. The actual pinned `pg_dump --format=custom --snapshot=<returned-id>` later waits for its
AccessShareLock. In the reviewed PostgreSQL REL_17_6 ordering, `setup_connection` successfully imports
the snapshot before `getSchemaData`/`getTables` asks for these locks. Startup, same xmin, a log line
or application-name equality is **not** the acknowledgment.

The implementation binds the actual long-lived psql/pg_dump image through `/proc/<owned-pid>/exe`
and its SHA, Process PID/start, network namespace, child-owned socket FD/inode and loopback TCP tuple.
Two fresh fixed native observations match that tuple to one exact database/role/backend start and
transaction, with the requested guard lock blocked **only** by the exporter holding its granted
exclusive lock. Application name is supplemental; duplicates, replacement sockets, other blockers,
unsupported identities and cached/ambiguous observations refuse. `stats_fetch_consistency=none`
and an explicit `pg_stat_clear_snapshot()` precede each observation in the RR exporter.

The exported/diagnostic `snapshot.xmin` keeps the full canonical unsigned64 `xid8` decimal from
`pg_snapshot_xmin`. The supplemental `pg_stat_activity.backend_xmin` comparison uses only its
explicitly derived low 32 bits, expressed as canonical unsigned32 decimal. It neither assumes epoch
zero nor compares the two different-width texts directly. Xmin equality is never import proof.

Only this original's retained acknowledgment permits ROLLBACK. The fixed release response must
confirm the original PID and absence of its guard, followed by actual successful psql/pipe-body exit.
The dump then continues on the imported snapshot without exporter/E. A subsequent dump failure still
refuses. This source-grounded ordering and local identity scheme are **not runtime-qualified**.

## Bounds, files and cleanup

There is one concrete JVM capture slot and a cooperative private-parent file lock; neither is an app
admission capability. At most exporter plus dump run together. Version/TOC/helper commands are fixed,
sequential and retained by the same original, not a public process framework or observer connection.

The original total allowance is 120 seconds; handoff is capped at 5 seconds, dump at 60, with no cap
renewing the original total. Guard acquisition has a 100 ms lock timeout; exporter statements 1 s,
idle transaction/session fallback 5 s. pg_dump's 1 s initial table-lock timeout is **not** its connect
or catalog-discovery budget. Server idle fallback is not a cleanup receipt or proof of an exact
5-second wall-clock guard duration if the operator process/OS stalls. Native/regular-file calls still
depend on the supported OS/storage behaving; expiry never manufactures successful disposal.

Dump bytes are pipe-limited to 2 GiB; selected media to 512 MiB; executable images 64 MiB, replies 4 KiB,
proc tables 256 KiB each and FD enumeration 64. TOC uses real pinned pg_restore. Completed dump/media
bytes go through unchanged `backup_bundle.py create` and `verify`, with its exact source pin. Preserve
the v1 manifest's raw bytes/newline: its raw SHA is **not kcj-1** or the catalog descriptor commitment.

All outputs stay in a fresh private `.logical-backup-q-*` stage; nothing is published or clobbered.
`capture.json` is bounded local diagnostic metadata, not a signed/canonical acceptance document.
Failed stages remain for operator reconciliation; there is no tree deletion or same-attempt retry.
Cleanup gets a separate 3-second close-only allowance. Unknown start/exit, live pipe body, observed
unexpected descendants, file-lock cleanup, or unconfirmed post-delivery guard release retains the
original slot/handles. Killing a local process is not proof the server released a delivered lock.
There is no timeout refund or reset API. A lost/retained original requires operator reconciliation;
restarting a JVM is not itself evidence of server cleanup.

Credential-file custody enters `MAY_EXIST` **before** creation/write/fsync/identity capture. If any
of those operations fails without retained file facts, cleanup stays `RETAINED_UNKNOWN`; it does not
reacquire ownership from a possibly substituted path, delete it or refund the original slot. A known
copy reaches `REMOVED` only after identity-checked deletion and directory fsync both succeed. A failed
delete/fsync also retains unknown custody. This partial-write path is source-corrected, not fault-tested.

## Authored verification, all NOT_RUN

`LogicalBackupCaptureRecordsV1Test`: closed inputs/snapshot tokens, strict bounded native JSON,
full unsigned64 xmin validation and low32 wraparound comparison, unchanged parent deadline,
fixed no-DML/no-epoch SQL, no-I/O cold refusal and one-shot behavior.

`LogicalBackupCaptureIT` is opt-in with `KIRA_QCAP_IT=disposable-local-17.6` in a **fresh isolated test
JVM**. It requires an initially empty explicitly named `kira_qcap_it_*` database on an already
reviewed host-local TLS/SCRAM server. It does not launch/install a server or use a Docker mapped port.
Its minimal guard fixture has an ordinary marker, not fabricated journal/epoch/checkpoint history.
Selectors cover wrong actual image pin, real import/exporter-release while a genuine dump remains
blocked on a second fixture table, exclusion of a later inserted row in real pg_restore output,
unchanged marker/v1 bytes, and bounded guard refusal with permanent unknown-custody retention. The
guard-refusal selector is last because it intentionally does not reset the capture slot.

Explicit `KIRA_QCAP_IT_` inputs: `PORT`, `DATABASE`, `USER`, `PASSFILE`, `TRUST_FILE`, `TRUST_SHA256`,
private `ROOT`, `BUNDLE_HELPER`; `PSQL`, `PG_DUMP`, `PG_RESTORE`, `PYTHON` and each corresponding
`_SHA256`. These are external test qualification inputs, not deployment authority. Stages are retained.

Still requiring separately authorized native cases: exporter/child death around import/release;
spoofed/duplicate/replaced native identity; wrong snapshot/DB, stale stats, other blockers; privilege
refusal; interrupted/unknown starts and cleanup tails; dump byte/whole-deadline exhaustion; TOC/helper
failures, filesystem drift and partial credential creation/write/fsync/post-write identity failures.
No test/build/compiler/analyzer/SQL/native command was run for this slice.
