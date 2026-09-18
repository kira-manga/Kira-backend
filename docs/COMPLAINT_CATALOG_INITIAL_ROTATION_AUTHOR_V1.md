# Initial D7 signer-rotation author session

This programmatic source candidate supplies a distinct initial-author route to the existing
first-overlap Freeze and same-campaign continuation. It stops at **signed PREPARED generation 2,
accepted head still G1**. It is not the fresh-process PREPARED2 no-Sign recovery purpose, a
general runtime launch, CLI, service, bean, activation gate or qualification result.

## Retained construction and use

Retain the original `ComplaintDesiredProcessAssemblyV1`, then use its TARGET-only method
`assembleTargetSignerRotationAuthor(inputs, acquiredTargetBindings, sealerCredentials)`.
No operator composition/password is acquired. The actual parsed/acquired D7 must already have
desired generation 1, the nonprojected G1 reader and both ordered signing keys. The same runtime
principal, endpoint/trust, complete canonical D/hash and optional epoch/sealer/coverage inventory
are retained; this does not retrofit D7 onto an already-selected different first D.

```kotlin
val author = CatalogSignerRotationInitialAuthorV1.begin(assembly.target)
// Retain author before preparation, including its failure paths.
assembly.prepareTargetSignerRotationAuthor(author.bootstrapBudget)
author.prepare(primaryReadCredentials, replicaReadCredentials)
val first = author.beginFreeze()
first.freeze(request, oldSigningCredentials, newSigningCredentials,
    primaryReadCredentials, replicaReadCredentials)
```

`prepare` runs the real existing `CurrentAcceptedCatalogRefreshV1` pipeline: actual released
snapshot, pinned SDK/raw dual-location G1 verification and provider close, optional COMPLETE of
signed PREPARED1, and **mandatory PROJECT, including its already-current/no-op case**. Only its
genuine privately retained Result can produce the binding and the one actual lease ACQUIRE.
No synthetic refresh/projection, supplied campaign or portable bootstrap result is returned.

After a failed invocation has positively completed its original cleanup, another explicit
`author.beginFreeze()` can use the **same** retained campaign. Its existing `continueSecondSign`
method still requires the previous exact invocation and known-unattempted Sign2 prefix; its
existing `resume` still requires both returned signatures and never Signs. A spent prior
deadline does not erase historical proven cleanup, but unresolved cleanup cannot be repaired
by a new invocation. All existing durable input, prefix, binding and lease checks remain.

## Closed purpose and budgets

The immutable, mutually exclusive `catalogSignerRotationAuthoring` purpose is selected before
pool binding. The root stays `UNKNOWN`; generic UNKNOWN startup/checkout is not opened and
`CONTROLLED_TEST_ONLY` is not used. Ordinary/deletion and optional epoch starts/capture are
permanently sealed. Only the original scanner, Timer and coordinator prepare. Only snapshot,
genesis, lease and signer-rotation executors are constructed.

Exactly seven private phase paths are permitted:

- `COMPLAINT_CATALOG_SNAPSHOT`
- `COMPLAINT_CATALOG_GENESIS_COMPLETE`
- `COMPLAINT_CATALOG_GENESIS_PROJECT`
- `COMPLAINT_COORDINATOR_LEASE_ACQUIRE`
- `COMPLAINT_CATALOG_SIGNER_ROTATION_READ`
- `COMPLAINT_CATALOG_SIGNER_ROTATION_PREPARE`
- `COMPLAINT_CATALOG_SIGNER_ROTATION_SIGNATURE`

The allowlist alone grants nothing. Before publication/permit acquisition each entry requires
the exact session, original caller/process/coordinator and selected stage; rotation additionally
requires its actual retained Freeze attempt. G1 PREPARE/Sign, lease renew/relinquish, desired
installation/operator authority, epoch/cutoff/seal, overlap COMPLETE/PROJECT, PUT and activation
are unavailable. Existing G1 SQL/isolation semantics are preserved, not replaced by recovery.

Three distinct bounds remain: the session starts a fixed **60s startup/bootstrap allowance**;
each Freeze/continuation starts the unchanged writer-configured **1–30,000ms** allowance; the
one campaign retains its original **DB-time 30s lease** and conservative dispatch-time local
window. No bootstrap, retry, child or cleanup reacquires/renews that lease or an old budget.
Each SQL phase retains its 2s cap; SDK/readback caps stay beneath their actual owning allowance.
READY-session work does not recheck the already-finished bootstrap deadline.

## Cleanup and status

One original coordinator refresh slot belongs to the session through bootstrap and all children.
Actual refresh/native owners and SQL contexts are retained before effects or throwable post-return
checks. Each exact context must positively prove finalization, Spring settlement, permit refund
and acquisition quiescence. Exception flags, another phase or later ThreadLocal absence are not
release evidence. An UNKNOWN ACQUIRE outcome is independently sticky even after physical
retirement. Fatal, cancellation and interruption signals are retained before lower bounding.

The caller must attempt both `author.close()` and the original `assembly.close()` even when one
fails, preserving the strongest failure. Author close stops the private campaign locally and
releases the parent slot only after its actual child/provider/phase cleanup is proven; it never
issues SQL relinquishment. Separately observe the original assembly's `requireCleanup` under
its explicit retained lifecycle allowance. A failed cleanup is not silently retried or declared
successful because the process later has no active ThreadLocal.

Human approval authentication/delivery, trusted durable-root provisioning and BYO credentials
remain external. No schema/grants, approval format, custody protocol or provider algorithm is
introduced. This source handoff ran **no compilation, static checks, tests, builds, CI, services
or providers**. D7 and fresh recovery remain unvalidated dependencies here; acceptance requires
the parent's exact-source review and focused execution evidence.
