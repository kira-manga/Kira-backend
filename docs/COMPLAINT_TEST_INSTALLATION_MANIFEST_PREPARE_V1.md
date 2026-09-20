# TEST installation-manifest PREPARE — partial connected edge

**SOURCE_AUTHORED / NOT_COMPILED / NOT_RUN. Nonauthor review is required.** This
slice prepares installation-manifest outbox rows only. It is not terminal
publication, accepted provider evidence, a purge, a release gate or a deployed
TEST-intake qualification. Existing V14/V21, TEST encoding, HMAC routing, fixed22
accounting and normal coordinator ownership are reused without a migration or
protocol change.

## Actual predecessor, not a supplied success token

`TestRunOrdinaryDrainV1.prepareInstallationManifest()` connects the next edge to
`TestRunInstallationManifestV1.begin(drain).prepare()`. The retained drain must
have actually completed its strict post-denial ORDINARY seal, including native
close, committed VERIFY, physical/Spring release and its final original checks.
Neither a result enum, a bare registration, a parsed seal/progress object nor the
earlier local-only first-seal result grants entry.

This is still the existing first/current-writer profile. It uses the exact live
registration/process, full-D controls, J/HMAC consumers, independently retained
retention declaration and original completed cut. A fresh manifest original may
reuse that same completed drain; it does not rerun the finished drain or weaken
its ordinary-only progress guard. A restarted process has no reconstructed drain
authority in this slice. Authenticated deployed intake, installed denial/IAM,
backup horizon and retained-key availability require their separate evidence.

## Fixed phases and bounded comparisons

Every phase enters the existing normal catalog-coordinator root: READ COMMITTED,
M(shared), E(exclusive), global/scoped controls, existing publication/recovery
locks when present, all22 counters, then the registered SEALED run and sidecar.
The paid sealing audit, exact strict seal-set/sidecar, previous control history,
ordinary completed cut and exact run remainder are rechecked. No callback-defined
SQL or alternative pool is introduced.

1. **CAPTURE** acquires a new current database-time fencing token. It can take over
   the exact successfully completed drain's lease immediately, but an unrelated
   holder must actually be absent or expired. Two complete reservation-led reads
   restart at the beginning, use UUID keyset pages16, and continue to an empty
   page while the SEALED run is locked. Raw reservation/credential metadata and
   xmin, complete membership, target root/counts, grouping and high-water evidence
   must agree. The existing source reducer keeps DELETED as DELETED and otherwise
   targets RETIRED, including no-credential RECOVERY_RESERVED; pending deletion,
   orphan/wrong-scope credentials and invalid pairs refuse.
2. The first successful CAPTURE merges exactly those two actual source reads with
   the unchanged ordinary cut in the existing bounded progress blob. It does not
   buy the already-paid lifetime run envelope again. On retries, both actual
   reads use the new fence. A separate digest-only fold reproduces the stored old
   source prefix over those newly read raw rows. It neither issues a read under
   the old fence nor invents old timestamps or replaces historical progress bytes.
3. **CHUNK** rereads the selected exact bounded range and, if present, loads its
   publication/reservation/sidecar winner. The full source retains at most4096
   scalar chunk descriptors and the existing single <=500-entry plaintext chunk;
   the selected CHUNK's list is consumed and released before PREPARE. Terminal
   codec/HMAC binding and retention construction occur only after committed
   connection-free release, with the same original enclosing budget.
4. **PREPARE** locks/reloads the exact slot again and rereads its entire bounded
   range. Raw metadata, entry hash, count and UUID boundaries must match the
   captured descriptor, binding the private original's immutable codec-created
   bytes to that actual source. For an absent next contiguous slot, publication,
   recovery reservation and canonical sidecar are inserted, reloaded and paid
   atomically. Existing exact winners are compared, never rewritten or repaid;
   missing/partial/unpaid/conflicting rows fail closed rather than being repaired.
5. **COMPLETE** requires every slot, repeats both whole-source reads and the full
   ordinary relation/cut comparison, then releases only this exact current lease.
   The diagnostic result is `ALL_CHUNKS_PREPARED_NO_NETWORK`, not a dispatch token.

The full source is not rescanned for every chunk. CAPTURE and COMPLETE each do
two complete reads; CHUNK and PREPARE each reread one bounded range. The existing
J scan/staging bounds apply, with the original scan deadline and the unchanged
**two-second per-phase cap**. Large-N feasibility under those limits is NOT_RUN
and not accepted. The existing source contract requires the actual reservation
count to equal the locked enrolled count; this slice invents no new enrollment
accounting for restored placeholders.

## Once-only reserve transfer

Each manifest costs the existing publication + physical recovery row + sidecar:

```
JOURNAL_PUBLICATIONS  +1
RECOVERY_RESERVATIONS +1
STORAGE_BYTES         +1,619,264 = 278,528 + 1,340,736
```

Exactly that vector moves from run unused/global TEST reserve to actual in the
same transaction as all three inserts. Global free and recoveryReserved do not
change. The recovery row stays RESERVED with the exact fixed22 ZERO promise and
NULL converted fields. It is not an ordinary APPLIED/CONVERTED event.

The required run remainder is the original reserve minus enrollment shares, the
paid RUN_SEALED audit, the already-paid **1,068,608-byte lifetime run delta**, the
ordinary sidecar and the exact number of paid manifest slices. The run delta is
neither charged a second time nor released by progress growth or a retry. No new
audit, scan row, refund or capacity reopening is added.

Rollback, UNKNOWN commit, acknowledgment loss, stale fence and unresolved
physical/Spring cleanup cannot produce success or another phase. Failed originals
remain unusable. A fresh original must be connection-free and obey the actual
lease rules; tests explicitly expire failed leases rather than pretending that
an error revokes a live holder.

## Evidence and deliberate stop

Authored source definitions cover the genuine predecessor, atomic payment and
visibility, exact fresh-fence replay, actual second-pass/selected-chunk metadata
drift, stale fence, beforeCommit rollback, afterCommit acknowledgment loss,
deferred UNKNOWN, unresolved release and unpaid-winner refusal. Pure definitions
cover501 mixed identities/unsigned order, independent descriptor frames, old/new
fence comparison and sticky failure. **None has been compiled or executed for
this slice.** The connected fixture's synthetic IAM/horizon and earlier-history
inputs are not externally installed denial or restore evidence.

No STS/KMS/S3 call, wire freeze, manifest VERIFIED state, TEST_RUN_PURGE,
TERMINAL seal, terminal-prefix denial/catalog acceptance, PURGING/PURGED,
manifest-driven retirement, final capacity release or restore convergence is
implemented here. The run remains SEALED; later terminal columns remain NULL.
