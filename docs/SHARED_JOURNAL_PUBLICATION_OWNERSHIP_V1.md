# Dormant shared journal publication ownership v1

Source-only implementation; not activation, full-D/current authority, native-deadline qualification,
or a deployment-wide concurrency guarantee. There is no bean, controller, worker, executor, readiness
flag, distributed semaphore, or changed source-config/journal wire contract in this slice.

## Accounting and injection

`JournalPublicationLanesV1` is an explicit, retained in-process owner of the existing immutable J
configuration. All connected publisher factories must receive the **same instance**, including
replacement factories. Exact lane and J identity are checked; matching descriptors/copies are not
substitutes. There is no per-request/factory default that creates another budget. A future runtime
must retain this owner across replacement; creating a new root while old owners remain would still
duplicate the budget and is not implemented as a supported lifecycle operation.

Admission uses a short bookkeeping-only `tryLock`: contention refuses immediately, without a queue.
The actual admitted objects, not a configured-N array/pool, are retained in sets. Counts widen before
addition; J's `maximumPublicationLanes = N` and `routinePublicationLanes = R` are not the separate
deletion pool's fixed four/three limits. Total occupancy is at most N, routine at most R, and new
routine reservations yield while any privacy owner remains. The owner-delete-all factory always
selects privacy work; there is no caller-controlled priority flag. Routine reservations are currently
unstarted accounting only, with no routine network entry or permission in this implementation.

J custody deliberately does **not** use `LocalPersistencePermit`. No registry lock is held across
AUTH, S3/KMS construction, publication, or resource close. Metric snapshots are not permissions or
quiescence receipts.

## Connected order and original lifetime

The old dormant `ComplaintOwnerDeleteAllCoordinator.prepare` lower API remains available. The
connected continuation uses `prepareForPublication`:

1. Real authenticated preflight commits/releases, then semantic admission runs connection-free.
2. New work reserves J before entering AUTH with its separate deletion-persistence ownership.
3. Actual committed/released Prepared custody, including its private SQL issuer, is checked before
   any provider construction. The one publication clock starts here, **after AUTH but before KMS/S3
   construction**, not when reserving the slot or anew at `publish`.
4. The reservation owns concrete construction, publication and cleanup, and returns readback only
   after the original resource close succeeds. J is released before separate VERIFY/APPLY phases.

An AUTH/ingress failure closes only a never-started reservation; its state transition atomically
prevents a later start. Authorized reload bypasses semantic charging and reserves only for Prepared
work. RecordedVerified and completed/rejected results bypass publication entirely. Existing genuine
verification/application results are returned, not mapped to new HTTP success statuses.

Concrete publisher, KMS and S3 construction custodians retain every returned raw HTTP transport,
bounded wrapper, SDK client and final owner if a later construction/close fails. A raw factory or SDK
build that throws **without returning an owner** has unobservable internals: known returned resources
are still closed, but the original attempt stays charged. No absent resource or thrown exception is
claimed as proof of quiescence. Failed cleanup is sticky; repeated close cannot silently clear it or
free a replacement slot.

## Stop, time and remaining limitations

Factory close stops that factory's admissions and closes its idle reservations. Shared close stops
all new admissions permanently. For a running/cleaning reservation, close records a stop request and
reports incomplete cleanup; it neither waits under the registry lock nor invents a native abort
completion. The original synchronous caller still owns cleanup. Only its actual successful return
and required resource cleanup release occupancy; stop prevents promotion to VERIFY. Unresolved
owners remain reachable/charged, even after another factory is created on the same shared owner.

All connected construction/publication/final-cleanup checks use one shrinking clock. An expired
clock or interruption cannot skip actual cleanup. Successful late cleanup can release genuinely
finished ownership, but the expired attempt cannot return successful readback to VERIFY. A clock
check, a raw close counter, timeout or cancellation alone cannot release occupancy. This is not a
hard bound on DNS, SDK construction, native request/abort/body/client close, or JVM copies of keys.
Those qualification requirements remain open, as do current full-D/catalog/restore-horizon authority,
provider policy/credentials, durable recovery/coordinator wiring, and replica/surge aggregate quotas.

Standalone publisher/KMS APIs retain their lower dormant behavior. They do not independently acquire
J and must not be wired as a bypass around the connected shared factory.
