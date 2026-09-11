# Owned cut ABI 1 — native/core author handshake

Source-only author freeze; **UNCOMPILED / NOT ACCEPTED**. Java8 source; all methods below are public static on
`org.postgresql.jdbc.KiraOwnedJdbcCut`. Nested cell types are public final with
private constructors. No raw getter or generic invocation API. This document is an
author integration contract, not tested artifact acceptance.

```java
void prepareRuntime();
Opening prepareOpening(java.sql.Driver driver, Object entryKey);
void armOpening(Opening opening);
void recordReturned(Opening opening, java.sql.Connection returned);
void endOpening(Opening opening);
int openingState(Opening opening);
void cleanupOpening(Opening opening);

Root attach(Opening opening, java.sql.Connection connection, Object contextKey, Object epochKey);
Owner owner(Root root);
Life life(Owner owner, Object nativeIdentity, Life lifetimeParent, boolean disposable);
boolean isLive(Owner owner, Life life);
boolean revoke(Owner owner, Life life);
int lifeKind(Life life);
int firstCloseState(Life life);
long liveNativeChildren(Root root);
int rootState(Root root);

Invocation prepareInvocation(Owner owner, Object nativeReceiver, Object guardCallKey,
    java.lang.reflect.Method operation, Object[] arguments,
    Object[] knownValues, Life[] knownLives, int[] argumentPositions);
void arm(Invocation invocation);
int invocationState(Invocation invocation);
int drainState(Invocation invocation);
void disarm(Invocation invocation);
void actualEnd(Invocation invocation);

int retentionState(Root root); // Additive method23; see the source-only amendment below.
```

Opening preparation/retention/arm is after genuine opening claim, outside F/G/T,
before connect. `recordReturned` follows the existing adjacent Entry.raw store;
null means no returned connection. After authentic frame checks it publishes the
exact raw exclusion before later constructor-correspondence checks. Failed correspondence
leaves a preowned unresolved flag. Core additionally skips capsule cleanup when nonnull
Entry.raw lacks a normally completed `recordReturned` receipt, retaining UNKNOWN while
still doing the actual raw abort/close. `endOpening` restores only its exact F1 frame.
The capsule retains constructor-first records, including nested constructions.
`cleanupOpening` is a closed terminal operation only after actual opening and
producer end, before the final timer boundary. It excludes the exact normally
returned connection, but performs phase-safe cleanup of failed child factories
and actual close of retained successfully returned native children, including
unexposed metadata children. It does not upgrade a failed first-close receipt.
Native child links stay stable through nested/implicit close and compact afterward;
unarmed ordinary connections do not acquire that retained history. Cleanup failure
is recorded, not thrown over the primary opening/SQL exception. It never calls a partial connection's
public close. State publication is volatile; acquire getters below are data-only.

Opening state bits: ENDED=1, UNRESOLVED=2, CLEANUP_FAILED=4, CLEANUP_ENDED=8,
CONSTRUCTION_FAILED=16. UNRESOLVED can clear only after successful terminal cleanup;
construction failure alone is not cleanup failure. At cleanup-ended observation,
still-live native children are UNRESOLVED even if no Root was ever attached.
Cleanup-ended unresolved uses
the core UNKNOWN_ENDED route, never an eternal PENDING actor.

Attach is only after selected typed admission/private raw access. Opening identity
and exact normally returned connection must match. A repeated context/epoch tuple
returns its existing live Root; a later **authentically core-admitted** tuple gets
a new immutable Root over the same physical ledger/canonical Lives. The tuple cache
is weak, not an old-context history. Core must reject unselected/unauthenticated use;
passing arbitrary keys alone is not admission. Old Owners are never rebound.
ORIGINAL_PROVIDER transport policy is unchanged. Owner captures actual current
Thread and is an immutable public access credential, not a native-resource
constructor-thread pin.

Life is canonical native-resource identity. Known public guards retain their own
immutable Owner separately; core still checks context.sameOwner/requireCurrent for
new use. `isLive` validates root and Life/real parent revocations for this already
admitted Owner; terminal seal is not asynchronous all-Life revocation. `revoke` is
monotone, original-Owner-thread-only; it reports first logical revocation, not native
close completion. Core supplies `disposable` only for facade-only resources; native
Statement/ResultSet custody was already registered by the driver. Life kinds:
PASSIVE=0, NATIVE_STATEMENT=1, NATIVE_RESULT_SET=2, FACADE_DISPOSABLE=3.
First-close states: NEVER=0, IN_PROGRESS=1, RETURNED=2, FAILED=3. Only native methods
write native first-close facts. Facade-only first-close accounting remains core-owned.

Root state bits: CLEANUP_FAILED=1, UNCERTAIN=2. Native count is canonical open/
first-close-in-progress/constructing receiver custody, not facade count; failed-ended
native outcomes leave a sticky failure/uncertainty flag instead of a false live
operation. Core copies fixed values outside F/G/T before dropping its admitted call.
No scanner traversal of these graphs/getters under G is authorized.

## Source-only method23 amendment — retained state, not terminal reclamation

The exact additional descriptor is
`retentionState(Lorg/postgresql/jdbc/KiraOwnedJdbcCut$Root;)I`, public static.
Closed bits: **FAILURE=1, UNRESOLVED=2, UNPROVED_RETENTION=4**; bits may combine.
Only exact integer0 supplies the native retained-state fact. Unknown bits, missing/wrong
descriptor, observation exception or unavailable/non-profile state deny reuse. Null Root
returns UNRESOLVED. Core retains physical uncertainty before packaging observation failure.
No existing rootState, firstCloseState, liveNativeChildren or terminal canReclaim meaning changes.

Observe outside F/G/T and only after all old producers are genuinely sealed and actually
drained, including disarmed invocation finalizers, foreign cancellation and abort. A null
native current frame or an idle statement alone does not prove that cut. Core binds the
observation to the exact Entry/epoch/one return attempt and separately proves physical readiness,
transaction/reset outcomes and transfer authority. This method neither calls isClosed/SQL nor
proves transport/timer/remote completion. It does not compact, close, clear, revoke, rebind or
reset anything. Existing completed first-close nodes need not already have been compacted.

Unfinished opening/construction/invocation/transfer/batch or inconsistent native bookkeeping
denies reuse. Physical first-close/cleanup failure and uncertainty survive all fresh Roots.
Actual physical constructor/counter/factory/first-close facts and exact receiver representations
are checked; metadata emptiness is not a substitute for the native parameter/list/row contents.
Only these live retained shapes qualify in this initial profile:

* An exact PgPreparedStatement in one of the same physical TypeInfoCache's eight actual fields,
  with successful factory, FIRST_NEVER/not revoked, idle live native state and no cancel timer.
  The reader covers **all eight real fields**, not only membership of nodes still in native child
  custody. Every nonnull field must refer to its canonical linked live statement; an actually
  first-closed statement still held by a cache field denies reuse even after ordinary compaction
  removed its Native node. Stock TypeInfo factories replace null fields, not closed receivers.
  Its actual CachedQuery, parameter image, result wrappers and empty C/Q queues must match the
  fixed profile. CachedQuery must be exact, non-function and String-keyed; its actual Query must
  be the pinned core.v3 SimpleQuery (exact binary name and defining loader, since the class is
  package-private), not a Query extension, subclass or composite. No Query method is invoked to
  classify it. Every cached result requires its **own FIRST_RETURNED**. The complete parameter
  image must contain no Owner or known-Life dependency. Fixed TypeInfo scalar setters retain
  String, binary scalar byte[], unset slots or the native SQL-NULL marker (exact Object.class,
  not a new raw sentinel getter). Deferred StreamWrapper/ByteStreamWriter/other representations
  do not qualify even if their known-Life dependency set is empty.
* An exact, inert **unexposed** PgResultSet shell whose real physical parent has its own
  successful FIRST_RETURNED. The shell itself remains successful-factory, FIRST_NEVER/not
  revoked and counted. Actual tuples must be an empty concrete ArrayList, with no cursor,
  refcursor, delete statement, current row, insert/update state, row buffer or retained input
  work. A nonnull original query must be identical to the closed parent's actual prepared query;
  the same exact SimpleQuery profile applies, and the real parent class must be exactly
  PgStatement or PgPreparedStatement. Other parent/query representations are conservatively
  refused in this initial profile. There is no describe-origin marker or method-name whitelist.

Exposure is deliberately not represented by this native summary: the separate core lease
ledger, keyed by canonical Life.cell, requires every exposed disposable's **own FIRST_RETURNED**.
Thus an exposed inert shell cannot pass combined consent merely because this method returns0.
Hidden PgArray.getResultSet plain-parent Statements are **not** a third allowed live shape;
they require genuine first close or conservative physical retirement. Other hidden/error-path
children remain physically owned for terminal disposal, not forgotten after return refusal.

This amendment is uncompiled/unexecuted source, not qualification of the retained old final JAR
(which lacks method23). Successor source/delta manifests are in
`review/working/app-29-real-pool-native-retention-02/` at workspace root; the previous01 packet
and its pause/before manifests remain historical. Historical vendor
manifests/patches, qualified artifacts and consumer artifact pins are not replaced by this edit.

## Existing invocation contract (unchanged)

Known arrays are same-length; each position indexes the containing top-level argument.
Nested array/map/collection leaves can use that position; the leaf need not be
`arguments[position]`. Include public/delegated known identities as needed, with
the same Life. A position/identity carrier alone grants no retention or success credit.
The helper validates actual receiver/list/slot and actual
stored representation/explicit native transfer lineage, not merely this carrier.
Metadata/stream/etc direct native calls also get their own Invocation. `cancel` is
the sole foreign-thread dispatch allowed and never replaces the ordinary root stack.

Invocation bits: CLEANUP_FAILED=1, UNCERTAIN=2; these belong to that exact causal
invocation, not just aggregate Root. Core authenticates its mapped same-thread
unended ancestor before observing failure; finish remains top/actual/one-use.
Primitive getters remain usable for authentic retained cells after disarm/actualEnd;
raw A and eligible pins are released, but the completed phase is preserved.
Prepared/failed-arm cells can end on their actual Thread without erasing uncertain
custody. A mismatched installed top is not force-unwound; its uncertainty is retained.
Drain states: NOT_BATCH=0, ENTERED=1, BEGUN=2, QUERY_CLEARED=3,
PARAMETERS_CLEARED=4, COMMITTED=5, PRE_ENDED=6, UNKNOWN=7.
Coverage is native, never inferred from Method.invoke return/throw. Current C/Q/A
and native arrays remain driver-private. New Q after COMMITTED survives outer end.

Capture raw output in core escrow before reconciliation/wrapping. `disarm` restores
the exact previous native frame, after reconciliation/guarding/bookkeeping;
`actualEnd` releases only eligible per-invocation pins and retains uncertain custody.
Always reach the existing core GuardCall.finish in nested finally even on a bridge
failure. Public misuse refuses safely; native observation finalizers only publish
preowned data and do not replace stock exceptions.

The engine-only public cross-package methods and opaque EngineKey/Binding/Image/Pin
types are not backend control ABI. They require the one privately constructed,
cold-installed EngineKey and expose no raw getter. There is no public ParameterList
extension. Foreign cancellation does not compact/mutate the original lineage's graph;
ordinary compaction waits for the outermost Invocation end, preserving ancestor data.
