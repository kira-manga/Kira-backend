# Dormant INITIAL_LIVE coordinator lease v1

This leaf implements three fixed PostgreSQL control-row transitions. It does **not**
start a worker, heartbeat, scan, seal, replay or checkpoint; open a route/gate; install
desired state; or establish deployment/restore readiness. A receipt is a historical
committed-and-released transition, **not a live lease or work capability**.

## Retained origin and resource ownership

`CatalogCoordinatorLeaseBindingV1.fromRetained(process, refreshResult)` requires the
actual retained `VersionBoundComplaintProcessConfiguration` and its genuine private
`CurrentAcceptedCatalogRefreshV1.Result`. The result's exact-process check runs
connection-free. There is no raw tuple, supplied D or `CatalogCommonHeadEvidence`
conversion path. Supported binding B is exclusively:

- LIVE sentinel, `test_only=false`, schema 1;
- desired generation and nonnull D computed by the retained process (including J/P);
- database/restore identities and the actual J event writer;
- accepted G1 generation/hash, current trust hash and catalog writer;
- no pending projection.

The immutable binding captures the original coordinator, phase owner, manager and
guarded source. Before entry, during the concrete operation and on its final reread,
the retained process/configuration and selected resource identities are checked
again. SQL buffers are detached before entry, not supplied by a caller under locks.

`CatalogCoordinatorPersistence.lease` owns the fixed executor. Its campaign custody
is stored on that same original coordinator, not on a clonable process/executor
wrapper. A test JDBC probe must use that original resource/custody; a campaign also
retains its exact JDBC template. The original one-slot phase permit, timeout policy,
manager, physical lease and finalizer remain responsible for JDBC release or
quarantine. No additional pool, lifecycle actor or native session is introduced.

The DB leadership lease is distinct from both the physical `PersistenceJdbcLease`
and `catalogRefreshCustody`, the remote-readback slot. None substitutes for another.

## Three fixed transitions

Each named coordinator-only phase locks **only** the exact LIVE control row first.
A later `UPDATE ... RETURNING` statement materializes one `clock_timestamp()` value
`t` shared by its predicate, expiry and `updated_at`. It never uses `now()`, a caller
clock/Instant/duration or a volatile clock projection before the row-lock wait.

The UPDATE compares B and the full locked nullable owner/expiry, token and update-time
preimage. Exactly one returned row, finite timestamps and an exact final reread are
required. Only owner, token, expiry and `updated_at` may change. Epoch, scan request,
gates, catalog/desired pointers, seal/checkpoint bytes, counters and the separate
retention lease are untouched. No checkpoint or open gate is required to acquire.

| Operation | Fixed rule |
| --- | --- |
| `acquire(binding)` | Generate a fresh UUIDv4 before entry. Owner/expiry must be absent or expiry `<= t`; token `n` must be below `Long.MAX_VALUE`. Set owner, token `n+1`, expiry `t+30s`. An unexpired owner is not preempted. |
| `renew(campaign)` | Exact campaign owner/token and B; expiry must be strictly `> t`. Keep owner/token, replace expiry with `t+30s`, never add to the old expiry or resurrect it. |
| `relinquish(campaign)` | Stop locally first. Consume one release attempt against exact B/owner/token, even if its own expiry has passed. Clear owner/expiry and keep the high-water token. Never clear a successor. |

These paths deliberately acquire **no epoch fence, catalog advisory/history lock or
counter lock**. Renewal is therefore independent of an exclusive epoch-fence holder;
the future rotation session is a different, still-absent protocol.

## Continuation, stop and failure

Acquire returns a private campaign plus `CatalogCoordinatorLeaseReceiptV1`; renew and
relinquish return only a historical receipt. Only the actual retained operation can
mint them after known commit **and actual phase release**. Early/between-commit-and-
release reads refuse; an unknown/failed original completion never becomes a receipt
after later cleanup. A fresh operation, not promotion of failed evidence, is required.

The local window uses the original monotonic clock and starts before dispatch. DB
lock wait, response and cleanup delay consume it; return never starts a new 30-second
window. Renewal also requires the prior local window to remain valid until the
released result can replace it. The replacement takes only the actual released
renewal operation, never a caller timestamp/window. This is conservative local
cancellation, not proof of current remote/DB work authority.

`campaign.close()` is an irreversible local stop with **no SQL**. A valid local
campaign makes a second acquire fail locally, not as purported PostgreSQL contention.
After stop (or locally observed expiry), a fresh acquire may try the actual DB CAS;
an unexpired row still refuses. A stopped campaign may attempt its one exact
authority-reducing relinquishment, but may never renew. Old stop/completion paths
compare exact identities and cannot erase or revive a successor.

Every renewal failure stops the original campaign, including pre-reservation nested
entry, wrong resource, configuration drift, clock failure, interruption, SQL failure,
unknown commit and failed completion/return. Restoring prior configuration does not
revive that campaign. A foreign-executor renewal attempt also stops the supplied
original campaign and refuses; it does not transfer ownership. Exceptions remain
bounded, cause-free `PersistencePhaseException` values; no new error logging exists.

## Deliberately missing obligations

- Frozen V6 requires renewal at least every **10 seconds**. No scheduler or heartbeat
  is implemented here. A future heartbeat must run independently of long remote work
  and release every short DB phase.
- No data-producing operation accepts this campaign. Every eventual staging, seal,
  replay or checkpoint commit needs its own locked B/owner/token/DB-expiry checks and
  commit cutoff bounded by remaining lease time. This leaf does not certify them.
- No genuine desired-state installer is supplied. Authorized supersession must
  atomically install a strictly newer desired binding, increment the high-water token
  with overflow checks, clear owner/expiry and preserve closure. There is no
  `forceTakeover`, raw administrative callback or token reset here.
- G2+, TEST, restored-writer activation, non-pooled epoch rotation, provider-complete
  scan/seal/checkpoint production, backup-horizon and recovery claims remain absent.
  W06 remains **SKIPPED—OWNER EXCLUDED**; new-backend-data recovery is still required.

Verification belongs to the primary's explicitly scoped gate. Source checkpoints and
the presence of test cases are not evidence of execution or deployment acceptance.
