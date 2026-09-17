# Fixed APPLY continuation outcomes

This is a dormant producer boundary, not an HTTP handler, background retry,
erasure permission or live-runtime qualification. The existing throwing
`ComplaintOwnerDeleteAllApplyPhaseExecutor.apply(work, proof)` is unchanged in
meaning. Its named `applyForContinuation(work, proof)` entry runs the same single
fixed APPLY attempt and returns `OwnerDeleteAllApplyOutcomeV1`:

- The original `CommittedOwnerDeleteAllApplyV1` object, unchanged, only when its
  original phase proves both known commit and original-holder cleanup.
- A privately constructed `OwnerDeleteAllReconciliationPendingV1`, with no status,
  erasure acknowledgement, identity, proof bytes or caller-set permission fields.
  It retains the actual authenticated work and committed VERIFY proof privately.
  It does **not** claim that APPLY committed, rolled back, or erased content.

## Provenance and release cut

1. The existing APPLY store authenticates both private issuers, the original
   work/event/verifier pairing, same routing owner and strict canonical proof
   before entering the persistence phase. A real VERIFY or the verification
   store's strict `resume(RecordedVerified)` is required. No caller record,
   foreign result, reconstructed proof or publication label can substitute.
2. The original phase must retain the exact concrete APPLY operation. That
   operation must reach `completedFor(originalPhase)`, including its fixed SQL,
   accounting/audit completion and final retention/state checks.
3. The phase privately observes its actual root COMMIT dispatch, after the
   completed-operation and original-holder guards. During the original finalizer
   it captures whether that dispatched attempt could not release a committed
   result. An early SQL/invariant failure, an undispatched completion, or a later
   call to the public diagnostic constructor cannot create that observation.
4. Pending release requires the original caller, closed/refunded original phase,
   finished Spring/root completion and genuine acquisition quiescence. An
   unresolved-cleanup attempt remains a failure even if its original caller later
   reconciles the quarantine. A different thread's empty context is insufficient.
5. A sticky APPLY-only veto records raw Error, cancellation and interruption
   types at existing manager/JDBC/return failure owners before adaptation or
   secondary-failure suppression. It only denies outcomes; it cannot grant one.
   No raw Throwable or diagnostic cause/suppressed graph is retained or returned.
   The same original caller is sampled/restored outside ownership locks before
   pending release. Existing bounded failure precedence/envelopes remain intact.
6. The concrete producer performs `requireConnectionFree()` before releasing
   either result. Its eventual continuation/HTTP consumer must still perform its
   own original ingress and response-readiness checks.

`PersistencePhaseException.databaseOutcome` and `.cleanupProven` are diagnostics,
not evidence accepted by this boundary. Database outcomes are never assigned or
reclassified here: the existing lower native observation remains authoritative.
A lost real COMMIT response can therefore yield pending after proven cleanup
without being labelled erasure success. A real committed-tail failure can also
yield only pending; a separate exact retry independently proves its result while
preserving the original stored verification/completion times.

## Focused source evidence

`OwnerDeleteAllApplyOutcomeIT` covers original result identity and release gates,
foreign issuer/routing capture refusal, representative early/final-work failures,
undispatched completion, postcommit signals/fatals, and genuinely unresolved
original Spring cleanup. Its lost-COMMIT selector reuses the existing APPLY
protocol relay and independent committed-state witness; it does not invent an
UNKNOWN result, replace a JDBC holder or duplicate the lower protocol matrix.
The legacy throwing-API relay selector retains its original assertions.

These sources require independent compilation/runtime validation; this document
is not test evidence. This producer adds no HTTP202 mapping, Retry-After value,
route activation, admission/publisher retry or provider call. Full-D/current
authority, accepted-backup retention horizon, native deadline/cleanup,
provider/deployment qualification and rollout prerequisites remain separate.
