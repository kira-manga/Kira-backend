# Fixed overlap2 delivery, fresh reconciliation and same-owner projection

`CatalogSignerRotationDeliveryV1` is an internal programmatic owner for **signed PREPARED2 →
conditional PRIMARY publication → COMPLETE/head2/pending2 → separate PROJECT**. It is not a
service, CLI, ordinary runtime launch, general publisher or activation3 implementation. The same
`recover` entry also reconciles actual PREPARED1, pending2 or already-projected2 for NEW backend
data. No old Firestore data is imported or recovered by this route.
At recovery source handoff, compilation, statics, tests and runtime/provider execution are **NOT_RUN**.
Acceptance belongs only to a later primary-controlled exact-source gate packet.

## Entry and retained authority

Use the unchanged acquired TARGET D7 inputs with
`ComplaintDesiredProcessAssemblyV1.assembleTargetSignerRotationDelivery`, then explicitly prepare
that same assembly with `prepareTargetSignerRotationDelivery`. The immutable delivery purpose is
selected before root/pool binding. Only the original coordinator, scanner and Timer can start;
ordinary, deletion and epoch participants remain sealed. There is no installation-operator root,
Sign executor, G1 author/finalizer or ordinary projection/epoch/cutoff executor on this route.
The caller separately retains and retires the actual process assembly; delivery cleanup is not
evidence of process-pool/Timer retirement.

`begin(process)` owns one original allowance and the coordinator's existing shared refresh slot.
Choose exactly one `publish(request, primaryPutCredentials, primaryReadCredentials,
replicaReadCredentials)` or `recover(request, primaryReadCredentials, replicaReadCredentials)`.
Recovery structurally accepts no PUT or Sign credentials. Neither method accepts a freeze result,
caller tuple, raw proof, lease receipt, pending token or supplied cleanup claim as authority.

The owner reopens only the already provisioned Linux allocation. It requires the exact original
allocation/B record, canonical intent/approvals and T0/Tn, both returned randomized signatures,
both SQL persistence records, exact frozen envelope and `FREEZE_OUTCOME`. It reads and validates
the actual signed PREPARED2/head1 snapshot before any publication. Recovery reads the actual
snapshot and rejects foreign, later or contradictory lifecycle tuples. Existing Sign2 continuations
reject every downstream delivery leaf and every partial content/marker pair.

Actual fixed2 raw verification and closed original SDK/native cleanup precede a fresh full-B
DB-time lease acquisition. B includes desired generation/hash, database/restore/event-writer
identities, **the actual accepted1/hash1 or accepted2/hash2**, current trust hash and catalog writer;
raw tail2 never replaces accepted1, and a fresh B2 acquisition is never relabelled as B1.
Every present freeze/publication/COMPLETE/PROJECT owner and token is historical, not leadership.
The new owner differs from all those recorded owners and the actual locked previous lease owner;
the locked token must meet the strongest historical floor before its overflow-checked increment.
The current acquisition must be fresh, finite, unexpired and keep both gates already closed.
An actual locked exact2/P/history READ, another cleaned raw round and a full-preimage RECHECK
precede any new publication arm.

## One publication attempt; raw evidence is not an ACK

Only a newly **CREATED** durable publication arm grants a one-shot claim, spent before actual
native/SDK construction. The unchanged primary adapter performs one `If-None-Match: *` PUT with
the exact frozen2 bytes, expected owner, SHA-256/length/type, COMPLIANCE retention and pinned
regional endpoint. There is no replica PUT or Sign client. The arm retains the actual acquisition
full B, owner, token and exact expiry as private recovery history.

An ACK is preserved only after actual original provider cleanup and is observed-only. It never
substitutes for independent raw primary/replica evidence. Lost ACK or PUT failure returns a bounded
failure after cleanup where provable; this owner does not retry or continue readback after that
failed PUT. A later owner may only read the providers; it cannot retry PUT or Sign:

| Actual signed PREPARED2 and durable history | Outcome |
|---|---|
| No arm, successor absent in both namespaces, fresh publish owner | One newly armed conditional PUT |
| Existing arm, successor still absent | Recovery-required failure; never another PUT |
| Publication arm but no COMPLETE arm, exact primary-only2 | `AWAIT_REPLICATION`; SQL remains PREPARED2/head1 |
| Publication arm, exact dual2 and matching PREPARED SQL | Preserve both independent canonical copy blobs; one fresh DB-only COMPLETE grant |
| Externally visible2 without an arm, conflicting or later tail | Refuse |

Every raw round uses the acquired cold D7 policy. Unpublished retention belongs to observed G1;
primary-only/dual2 retention belongs to signed G2. Existing raw common retention/time/version and
trust/writer/approver checks are not weakened. All provider rounds are bounded by the original
allowance, at most 10 seconds, and by the original acquisition-dispatch window once acquired.

## COMPLETE and separate PROJECT

Before COMPLETE, exact primary/replica evidence and a durable COMPLETE arm are required. A new
arm is created only when absent. An older arm stays immutable and spent; it supplies historical
intent, not the new retry grant. The fresh grant comes from this owner's actual lease and released
locked reconciliation. `publish` never reuses an older finalization arm.
The named transaction takes the existing shared epoch fence, LIVE control lock, later DB-time
lease check, exclusive catalog lock, unfiltered bounded exact2 history lock and original P/capacity
locks. It rechecks the full G1 preimage and same frozen2 plus current full B/owner/token/exact expiry.
Capacity is already prepaid: verify actual catalog count2 and reserved storage without charging
or refunding. Both gates must already be closed; there is no epoch1 requirement.

COMPLETE changes only operation2 copies/state/completed time and accepted2/hash2/pending=op2
(plus `updated_at`). Its rereads use B2/pending2. Only known commit **and actual original phase
release/refund** can retain this effect's private pending continuation. The existing durable
COMPLETE outcome is written only when the arm belongs to this exact acquisition. A new effect
under a different lease leaves the old arm's missing outcome absent, even after known success.
The old campaign is permanently closed locally; its original dispatch start is captured before
close. No stale B1 renewal, relinquishment or acquisition is used across the transition.

`PROJECTION_PENDING` is diagnostic only. The original owner, release lock, refresh slot, budget,
exact grant operation/input/observation and original lease deadline remain retained. The grant is
either this owner's actual COMPLETE or its fresh committed/released `PENDING_RECHECK`, never a
read relabelled as COMPLETE. Call
`project()` separately on that same owner; it accepts no transferable argument and spends its
private eligibility before arm/phase entry. PROJECT locks/revalidates B2/pending2 and the exact
completed2 timestamp/copies, changes only `projected_at` and pending=NULL/`updated_at`, then rereads
B2/pendingNULL. A missing PROJECT arm may be created under this new owner. An existing arm is
not overwritten, and a different acquisition never fills its missing PROJECT outcome. G1's entire
row, identities, D, writers/trust, gates, epoch, retention leases,
checkpoint/seals and capacity remain unchanged. PROJECTED requires actual original cleanup.

## Fresh cold/ambiguous-outcome reconciliation

The same original Linux durable root must be exclusively reacquired with its complete freeze
prefix and every present historical delivery record. Existing outcomes must match their own
corresponding arm acquisition exactly (only B1 to B2 changes across COMPLETE), frozen bytes and
actual DB timestamps. Missing outcomes are **not synthesized** from observation or a later lease.
Partial content/marker pairs, contradictory outcomes or missing pre-arm dual evidence refuse.

| Actual locked lifecycle, exact signed history and both copies | Fresh recovery action |
|---|---|
| B1/null, signed PREPARED2; old COMPLETE arm may exist, no COMPLETE outcome or PROJECT arm | One DB-only COMPLETE, then private pending and separate `project()` |
| B2/exact pending op2, completed/unprojected2; old PROJECT arm may exist, no PROJECT outcome | No COMPLETE; retain a new private pending grant, then separate `project()` |
| B2/null, completed/projected2, original COMPLETE and PROJECT arms | Exact no-op reconciliation; neither COMPLETE nor PROJECT runs |

Pending2 uses a separately named fixed acquisition SQL leaf under the existing retained delivery
acquire phase. Its pre-entry arguments are actual B2 plus exact op2; after locking it compares the
full old lease preimage, then samples DB time in the later CAS. It requires both gates closed,
unowned/expired state, token floor/MAX fencing and exact released reread. No pending renewal or
relinquishment path is added; ordinary null-pending SQL is unchanged. Projected2 also requires a
fresh purpose-bound B2 lease using that unchanged null-pending SQL. Its only DML is lease metadata;
`projected_at`, `completed_at`, history, copies, accepted head/pending and capacity do not change.

Cold pending/projected first reads use expected frozen R17 from the original signed custody and
actual raw C6. They capture actual G1+2 full33 rows under locks, authenticate the released G1, then
require another **actual cleaned raw round** and exact retained full-history recheck. Subsequent
arguments include captured G21; C6 comes from that second raw round, not a fabricated DB observation.
The original prefix did not store the lost precrash full33 preimage: this first locked capture
cannot claim to reconstruct it, but preserves every captured column thereafter.

Prepared/pending states use unchanged `Overlap2Readback`. Already-projected2 uses the unchanged
genuine **Accepted2** `ProjectedHeadReadback`, narrowed by the cold D7 policy to this exact signed
overlap2. No Pending snapshot or reader-profile conversion substitutes for Accepted2 provenance.
Retention still requires signed creation plus ten years and sampled now plus attempt plus two years.

A read-only reconciliation may leave historical outcome gaps permanently absent. Repeated fresh
recovery remains possible because authority comes from actual new locked evidence, not a newly
invented receipt or an overwritten arm. No new journal, signed approval or qualification artifact
is introduced. Genuine committed-UNKNOWN transport loss and known-COMMITTED afterCommit failure
must be reported separately in qualification evidence; source support for both DB states proves
neither runtime scenario was exercised.

## Explicit limitations and cleanup

An unknown commit, late return, cancellation, fatal error
or uncertain actual phase/native cleanup issues no pending/terminal success. Original phase and
shared-slot uncertainty remain sticky; ThreadLocal emptiness or a replacement owner cannot repair
them. Closing an unprojected pending owner permanently forfeits its in-process continuation.
A genuinely fresh owner uses a new single original allowance and new lease, not the old pending
deadline or eligibility. An active UNKNOWN slot refuses another owner on that same coordinator;
a fresh process never counts as cleanup proof for the old one. Original root custody must actually
be released before another process can acquire it, and the DB lease must be unowned or actually expired.

This slice ends at projected overlap2. Immediate activation3, old-key retirement, general retry,
lost-signature recovery and complete interruption-safe rotation recovery are not provided.
Human approval authentication/delivery, BYO credentials, independently provisioned durable-root
custody and physical storage qualification remain external prerequisites. Software fsync/reread
does not qualify hardware or prevent trusted-custodian rollback/root replacement.
