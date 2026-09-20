# Complaint driver lifecycle

## Implementation status

This is the internal connection-lifecycle foundation for App #29, not an enabled complaint service.
`PersistenceJdbcLifecycleOwner` is **not wired into Spring, Hikari, application transactions or
HTTP APIs**. Its opaque candidates do not expose JDBC operations. Existing application persistence
continues to use its existing configuration. Do not deploy a complaint cutover based on this layer.

The implementation targets the repository's Java 21 and pgjdbc 42.7.12 pins. Its controlled-runtime
assumptions, database integration, full regression and independent reviews must be verified before
activation. A local lifecycle result is not production authorization or proof of remote session loss.

All implementation types below are under
`common/infrastructure/persistence/`. The additive complaint schema is documented separately in
[`COMPLAINT_SCHEMA.md`](COMPLAINT_SCHEMA.md).

## Ownership and activation

- Construct and retain one `PersistenceJdbcLifecycleOwner` **before** calling `start()`. Construction
  creates inert ownership records and unstarted actors; it does not connect or start background work.
- The root retains one guarded Driver, an ordinary participant, a separate four-slot deletion
  participant, a scanner and a timer controller. Each participant retains one controller, one factory
  worker and one terminal runner per physical slot. Failed actors are not silently replaced.
- Startup starts the scanner and timer controller before the ordinary controller. The latter owns
  common Driver bootstrap. Genuine terminal readiness and factory waiting state precede admission.
- Deletion remains cold until explicit `prepareDeletion()`. Snapshots, requests and observation do
  not activate it. It requires the approved direct-network settings and ready shared-Timer capture.
- `requestShutdown()` permanently forbids new starts and admission before other shutdown actions.
  There is no reopen operation on the same root. Its return value is not a cleanup acknowledgement.

## Request and compatibility policies

`PersistenceOwnedFactoryRequest` starts one system-clock budget before policy selection and
reservation. Ordinary requests retain the configured login allowance; deletion uses its derived
2,000 ms allowance. Neither retry, lock wait nor result packaging resets it.

External admission and final candidate transfer use immediate F→G lock attempts. Only the exact
already-admitted managed factory worker may wait at the opening/PRIMARY bookkeeping cuts. It
rechecks identity, shutdown, original deadline and actual interruption after obtaining G, before
allocating a transport. Failed admissions are not queued or retried internally.

The immutable per-attempt policy distinguishes execution, routing and evidence:

| Policy | Behavior |
|---|---|
| Original provider | Preserve the ordinary configured provider when native tracking is unsupported. Recovery is driver-contract-only; hidden provider work is not certified. |
| Tracked ordinary, weak | Use supported tracked transport without claiming a ready Timer boundary. Ordinary availability does not wait for Timer capture. |
| Tracked ordinary, strong | Bind the exact ready Timer generation and require the tracked terminal conjunction. |
| Tracked deletion, strong | Require supported derived settings and the same strong evidence. Unsupported preparation refuses deletion rather than downgrading it. |

A selected strong attempt never becomes weak after failure. A successful candidate remains LIVE
after its establishment deadline; that deadline does not expire an already-transferred connection.
The candidate exposes only idempotent retirement, not the raw `Connection`, a callback or a pool lease.

## Retirement and capacity reclamation

One exact terminal identity remains attached to each admitted physical record, including failures
before Driver entry and results returning after caller abandonment. Retirement proceeds as follows:

1. Fence new transport/call admission; close retained transports outside ownership locks. Continue
   tracking late constructor returns instead of interpreting an empty snapshot as disposal.
2. Obtain the stable raw/no-raw decision. A still-running opening is not a no-raw result. If raw
   exists, call `abort` on the fixed terminal runner with the direct executor.
3. Drain actual opening, capture and producer scopes. For strong attempts that entered Driver,
   schedule the exact detached Timer boundary. Then invoke the first final JDBC `close`, even when
   abort failed. Never manufacture abort/close success for a no-raw result.
4. Publish resource-phase completion only after required invocation exits and policy-specific
   transport/boundary facts. Failed-but-ended is distinct from successful disposal.
5. Factory cleanup may now return. Caller detachment and factory settlement publish the processing
   receipt. The terminal runner clears its old mailbox/payload before its final body-exit publication.
6. Reclaim capacity only after exact identity, processing, scope, terminal-body and applicable
   transport facts agree. Resource-phase completion alone cannot release a slot.

F is factory coordination, G is the physical-record ledger and T is a transport ledger. The order
is F→G→T. Do not put Driver/native calls, resource closes, waits, arbitrary callbacks or actor starts
under these locks.

`BEFORE_DRIVER` means positively proved no Driver invocation. `TRACKED_DISPOSED` requires the
selected tracked conditions. Weak `DRIVER_CLOSE_RETURNED` and `NO_RAW_DRIVER_RETURN_ONLY` preserve
ordinary compatibility, not native-resource or arbitrary-provider guarantees. Broken processing,
failed/unproved disposal and unknown starts retain ownership. A later no-op close cannot erase an
earlier failed close; no strong-to-weak escape is permitted.

## Shutdown and observation

Controllers seal and drain their actors; the scanner performs a conclusive final reconciliation
and reclamation pass after actual participant drain. Failed-but-ended UNKNOWN records remain owned
without keeping otherwise-idle actors alive. Truly pending work still prevents complete drainage.

The timer controller releases only its own successfully acquired reference, once, after participant,
factory, terminal and scanner drain. It neither cancels a foreign Timer nor releases another user's
reference. A release return and the captured Timer thread's actual termination are separate facts.

Preparation/shutdown observers use one original 10-second budget. They perform no activation,
resource I/O, `join`, target-monitor acquisition or blocking ownership-lock acquisition. Virtual or
overriding/untrusted observer threads are refused; supported virtual candidate callers remain allowed.
Interruption is preserved for the trusted observer. Scheduling delay is not a hard cleanup bound.

| Observation | Interpretation |
|---|---|
| `READY` | The requested preparation has current local admission evidence. |
| `NOT_REQUESTED` / `UNAVAILABLE` | No requested preparation, or preparation cannot provide readiness. |
| `UNSUPPORTED_OBSERVER` | The calling thread does not meet the observer contract. |
| `PENDING` | Required facts are unfinished, contended or not observed within the original budget. Retain the owner. |
| `UNKNOWN` | Known actors may have ended, but failure/retained records prevent complete disposal evidence. |
| `DRIVER_CONTRACT_ONLY_ENDED` | Local obligations ended with sticky weaker compatibility evidence. |
| `TRACKED_LOCAL_ENDED` | The tracked local conjunction ended under the supported-runtime assumptions. Not a production/session certificate. |

Snapshots are diagnostic, potentially mixed observations. Never use zero counts, body flags or a
canceled future as a substitute for the authoritative reclamation/shutdown path.

## Verification and remaining boundaries

`PersistenceJdbcLifecycleTest` runs real-owner scenarios in sanitized child JVMs. Loopback protocol
peers are not PostgreSQL; real JSSE TLS is not production TLS. Tests explicitly label project-lock
or association fault injection as MODEL. Separate disposable PostgreSQL tests must verify constructor
branches and independent session presence/absence; no test may use a candidate raw-JDBC escape.

Preserve positive and negative tests, original timeout settings, observer/receipt negative controls,
meaningful failure cleanup and unfiltered Ktlint/Detekt. Retain evidence before cleaning each batch:
stop its owned Gradle daemon immediately, clean its generated outputs, then stop any clean-task daemon.
Do not stop another task's workers or delete global dependency caches/signing/configuration files.

Still outside this foundation: private Hikari/JDBC facades, bounded timed statement execution,
Spring routing, atomic capacity SQL, audit/grants/step-up, durable complaint operations and APIs,
Android/iOS/Admin replacement, deployment and physical-device verification. Complaint data starts
empty: **no legacy Firestore import, reconciliation or recovery**. Recovery for new backend data
remains a separate required capability.
