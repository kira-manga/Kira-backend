# TEST run sealing: barrier and paid audit only

This source slice adds a two-transaction operation to the normal runtime root. Its only
entry is an existing, live `ComplaintTestNamespaceRegistrationV1`; rows, copied identities,
or an audit ID cannot issue one.

1. `COMPLAINT_TEST_RUN_SEAL` participates in M and the existing fresh closed-gate check
   under READ COMMITTED, then locks only the exact registered run row. An ACTIVE run
   becomes SEALED using PostgreSQL `clock_timestamp()`. It takes no E, control, capacity,
   catalog or audit row lock. An exact SEALED replay preserves its timestamp and xmin.
2. Only after that original transaction has committed and actually released can
   `COMPLAINT_TEST_RUN_SEALED_AUDIT` enter M, E, global control, TEST control, all 22
   capacity counters, the SEALED run, and the bounded audit lookup, in that order.
   It inserts one fixed SYSTEM `COMPLAINT_TEST_RUN_SEALED` audit at the original
   `sealed_at`, spending only `TestTerminalCapacityChargesV1.AUDIT` from the run's unused
   reserve and global `testReserved` into actual usage, in the same transaction.
   Exact audit replay performs no writes or charges; duplicate or contradictory rows fail.

The audit phase validates the current locked ledger against the retained capacity policy,
not a snapshot of unrelated counters at registration. Existing enrolled-count changes
within the signed limit do not by themselves prevent sealing. The unused AUDIT_ROWS
component distinguishes the first terminal audit from its paid replay; enrollment audits
are independently creation-charged. Later terminal progress is outside this operation.
Free capacity, recovery reserve and daily admission remain unchanged. This operation does
not spend `TERMINAL_RUN_DELTA` or claim that the complete terminal tuple is durable.

The separate [first ordinary seal source slice](COMPLAINT_TEST_ORDINARY_SEAL_V1.md)
consumes this already-paid barrier/audit through the same live registration. Its
cold HTTP-fixture-only intake does not change this operation or open deployment.

Each attempt has one original finite budget and sticky failures. Losing an acknowledgment
does not rehabilitate that original attempt. After actual resource cleanup, a new attempt
using the same still-live registration can resume a committed seal/audit without renewing
registration, rewriting `sealed_at` or charging twice. Unresolved physical/Spring custody
continues to block entry through the existing resource guards. No cross-phase provider
reservation is created by this operation.

This is not drain, final-ordinary-seal, denial, manifest, terminal publication, retention or
purge authority. It adds no CLI, provider facade, new-process registration recovery or
schema migration. The existing delayed-enrollment test remains unchanged. The connected
cases reuse real PROJECT plus independent-runtime registration fixtures, but do not claim
execution coverage for genuinely launched/enrolled runs or new-process recovery.

Validation status for this slice: source inspection and `git diff --check` only. No build,
test, analyzer, service or CI execution was performed; authored assertions are not execution
evidence.
