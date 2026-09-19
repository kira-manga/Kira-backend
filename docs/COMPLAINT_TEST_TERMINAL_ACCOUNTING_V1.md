# TEST terminal accounting V1 — bounded declarations only

**Dormant, partial accounting prerequisite.** These pure prices/calculations do
not admit a TEST run, authenticate J, select a durable winner, authorize a delete,
write an audit, persist a progress witness or implement activation/settlement.
No migration, JDBC, provider, DI or dispatch surface is added. The fixed22 counter
encoding, storage MAIN4 and existing phase/authority gates are unchanged.

The fixed audit and serial scan-pool policies below are selected engineering
policies. They are **not proof that any future writer implements them**. Scan and
sidecar prices still require independent qualification; a successfully constructed
plan is not a complete qualified reserve or sufficient issuer input.

## Exact logical inputs

`TestTerminalCapacityChargesV1` promotes scoped TEST prices from existing
`TestTerminalCapacityIT` row/index qualifications and reuses the existing ordinary
ID/audit/resource/publication/physical-reservation vectors. It does not relabel
unscoped LIVE genesis or signer-rotation profiles. Prices are conservative logical
row/index envelopes, not PostgreSQL pages, MVCC/history, WAL, vacuum or disk bounds.

| Symbol | Counter units in addition to STORAGE_BYTES | Bytes |
|---|---|---:|
| I, ID + credential share | INSTALLATION_IDS1, APP_INSTALLATIONS1 | 32768 |
| A, audit (payload<=2048) | AUDIT_ROWS1 | 65536 |
| E, terminal publication + physical recovery row | JOURNAL_PUBLICATIONS1, RECOVERY_RESERVATIONS1 | 278528 |
| C, scoped SINGLE catalog, selected document cap131072 | CATALOG_MUTATIONS1 | 3219968 |
| U, ACTIVE run | TEST_RUNS1 | 5632 |
| DeltaU, terminal run growth | no second run counter | 1068608 |
| Q, TEST control | JOURNAL_CONTROL1 | 1599808 |
| W, SYSTEM NOTICE + resource ID | COMPLAINT_ROWS1, RESOURCE_IDS1 | 32768 |
| F, durable sidecar | storage only | 1340736 |
| Sr, scan-pass run | SCAN_RUNS1 | 3776 |
| Se, scan entry | SCAN_ENTRIES1 | 37696 |

The scoped catalog price is `8*(2*d(131072)+140336)`, where
`d(n)=8*ceil((n+4)/8)`. It includes both documents/evidence and all five indexes;
it is **not** an 8-MiB qualification. U+DeltaU=1074240, the existing terminal row
maximum. Q covers V14/V17 with V19's seven LIVE links NULL. A notice has required
NULL content, key96 and an empty GIN expression; its own charge16384 is separate
from the resource ID16384 and every audit. F reuses the five-index V21 profile.

The proposed scan run envelope has17 columns: heap32+152+64=248 and all three
index tuples56+72+96=224, giving8*(248+224)=3776. The entry has14 columns, including
key/version1024: heap32+80+2256=2368 and all four tuples2120+72+80+72=2344, giving
8*(2368+2344)=37696. Independent evidence must cover exact columns/indexes, maximum
detoasted payloads, SCANNING/COMPLETE/ABANDONED runs, all entry replay states,
and rollback-preserved preexisting rows. Arithmetic alone is not that evidence.

## Finite semantic stages and once-only obligation

`TestTerminalAccountingPlanV1(N,R)` checks N in0..2048000 and positive R; an actual
activation must additionally require N>0. K=ceil(N/500), maximum4096; at most16
seals (15 preterminal plus terminal); H=K+17 sidecars; exactly two notice seeds.
N=0 still has purge and seal obligations. These ceilings establish no genuine
enrollment limit or lineage by themselves.

The closed `TestTerminalAuditStageV1` counts at most **N+7** new lifecycle audits:

| Stage | Maximum | Payment |
|---|---:|---|
| NOTICE_CREATED (`COMPLAINT_CREATED`) | 2 | projection actual |
| RUN_ACTIVATED | 1 | projection actual |
| ACTIVATION_CATALOG_PROJECTED | 1 | projection actual |
| RUN_SEALED | 1 | terminal reserve |
| TERMINAL_CATALOG_PROJECTED | 1 | terminal reserve |
| RUN_PURGED | 1 | purge future promise |
| INSTALLATION_DISPOSITION | N | terminal reserve |

The run/catalog stage names map to their existing `COMPLAINT_TEST_RUN_*` and
`COMPLAINT_CATALOG_PROJECTED` actions. Only genuinely new installation terminal
dispositions write `COMPLAINT_INSTALLATION_RETIRED/DELETED`; unchanged genuine
RETIRED/DELETED records get no duplicate. There is no audit row per retry,
provider call, chunk, seal, scan pass or PURGING batch. Existing paid canonical,
VERIFIED/sidecar, run/catalog evidence and the final scope audit represent those
stages, rather than an unbounded attempt history.

Future typed writers must bind audits to stable semantic-stage identities and
commit them once with the corresponding state/counter progress. The present enum
is not such a writer or replay guard. Sealing holds the run alone; its audit must
be completed in a later counter-before-run transaction without reversing that
order. Ordinary enrollment/content/receipt/deletion work, including their audits,
is still paid as LIVE work, never from this terminal reserve. In particular, TEST
enrollment spends I, not the98304 ordinary enrollment-plus-audit vector.

## Disjoint reserve partition

The plan eagerly constructs checked fixed22 vectors, rejecting arithmetic overflow:

```
activationCatalogPrepareActual = C                          // once, retained
activationProjectionActual    = U + Q + 2W + 4A

enrollmentReserve             = N*I
manifestPublicationsActualReserve = K*E
purgePublicationActualReserve = E
sidecarActualReserve          = H*F
terminalCatalogActualReserve  = C
terminalNonPurgeAuditReserve   = (N+2)*A
scanPoolReserve               = 2*Sr + 2R*Se
purgeFuturePromise            = DeltaU + A
originalUnusedReserve         = sum of these eight disjoint reserve slices
```

Future rows in those slices are not actual until their genuine insertion. The
purge promise is moved from the run's unused reserve to recoveryReserved when
its publication is created, not reserved again. The physical recovery row is
already in E and is **never recursively part of its future promise**.
`manifestFuturePromise=ZERO` is intentional only because this fixed policy pays
the complete physical publication/recovery-row/sidecar lifecycle upfront and
creates no later per-chunk row. It is not permission to omit another obligation.
Terminal publications stay VERIFIED and reservations RESERVED until final removal;
no delete-all APPLIED/retirement/recovery formula is reused. Other catalog operations
retain their separately paid producers; the two C slices cover only this lifecycle.

## Serial two-pass staging and exact recycle

R is actual TEST-J `maximumRetainedVersions`, independent of N. One serial pool
has maximum2 run rows and2R entry rows. `TestTerminalScanPoolV1` rejects nonpositive
R, checked-storage overflow, counts above those dedicated maxima, negative counts,
foreign-counter amounts and bytes differing from exact Sr/Se prices. Its exposed
Long arithmetic ceiling is **not** a reviewed deployment/provider/heap maximum.
The plan's other slices may overflow even for an arithmetically valid pool R.

`unusedAfterRecycle` requires exact priced scan-only vectors and
`removed <= pool.ceiling-unused`. Ledger `recycleTestScanPool` additionally checks
the matching configuration, declared unused<=global testReserved and declared
pool actual<=global actual. It then moves `actual -= removed; testReserved +=
removed`, with free/recoveryReserved unchanged, even while creation is closed.
This is not `refundActual`, which releases units to global free, nor a fresh
creation-dependent `reserveTest` call. The numeric checks prevent exceeding the
declared dedicated pool even when unrelated global actual usage is larger.

Those declarations cannot prove scope, deletion, replay or lease ownership. A
future fenced writer must derive them from the exact owned rows, preserve durable
bounded summaries, delete entries before runs under counter-before-scan-row order,
and credit the run's unused vector atomically with deletes/counters. There is no
overlapping or uncharged abandoned pool: resume or conclusively supersede/clean it
before reuse. Recycled unused capacity is released only at genuine final settlement.

J's `maximumScanStagingBytes` B remains the existing **LP32 framed-fold ceiling**,
not a PostgreSQL row charge. Validate B separately from R, Sr/Se and the pool.
The two ordered installation-table passes are bounded-memory folds, not S3 entry
rows. At most15 writer ranges mean30 denial cuts,60 inventory summaries and60
policy/bound digest references. Final SealSet/DenialSet occupy the run's existing
two65536-byte blobs covered by DeltaU. Reuse before the complete DenialSet exists
requires a future closed, bounded progress encoding in the existing SEALED blob,
preserving completed cut summaries and the source-table high-water witness. The
current full DenialSet is not that codec; exact progress/final fit and raw evidence
authentication/retention must be established before a real writer uses this policy.

## Settlement and remaining qualification gates

The future final atomic PURGED transaction converts retained DeltaU/final audit,
deletes temporary sidecars before publication/control parents, refunds exact
terminal physical rows once, releases only proved-unused reserve, and retains all
run/catalog/installation-ID/audit charges. Ordinary PURGING cannot remove terminal
sidecars early. Per counter, every move preserves
`hard = free + actual + recoveryReserved + testReserved`; no fixed schema baseline
is recursively charged. Old-backup recharge/quarantine needs its own genuine
producer; PURGED replay cannot recreate mutable rows or infer free capacity.

Independent gates must qualify the new profiles/plan/recycle before use. A genuine
issuer must also check actual J/codec bounds and ordinary-plus-terminal object
headroom under R, independent B limits, the **complete** terminal catalog's fit at
selected131072 and record/history ceilings (K<=4096 alone is insufficient), the
bounded durable progress codec, and ordered once-only audit/staging/settlement
writers. Finite accepted-request and restore-horizon facts require authenticated
evidence at their authority gates. No public DTO or boolean waives any obligation;
no complete-reserve or activation-ready claim is made here.
