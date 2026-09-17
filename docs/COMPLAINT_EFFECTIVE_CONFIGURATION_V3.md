# Complaint effective configuration D, version 3 — nonpooled epoch rotation

**Dormant source draft, NOT_TESTED.** This inventory is not activation authority,
measured database capacity, a completed rotation, seal or recovery checkpoint.

`VersionBoundPersistenceConfiguration.bindLifecycleOwnerWithEpochRotation()` opts
one original persistence root into a distinct capacity-one, nonpooled resource.
`VersionBoundComplaintProcessConfiguration.fromRetainedWithEpochRotation(...)`
retains that exact resource from the supplied pools, the original consumers/J/P,
and the retained G1 catalog reader. There is no caller-supplied resource, descriptor,
D/hash or readiness flag. A root without the resource is refused by this factory;
the legacy factory and V1/V2 encoders refuse opted-in roots rather than emit an
incomplete inventory. Unchanged legacy roots retain their existing V1/V2 bytes.

D3 contains the complete V2 inventory, with `schemaVersion:3`, profile
`INITIAL_LIVE_MEMORY_SINGLE_INSTANCE_G1_EPOCH_ROTATION`, and `epochRotation`:

- Actual role, capacity, `pooled:false`, fresh-terminal-only session policy and
  protocol version. This is not a fourth Hikari pool or a reusable checkout.
- Maximum rotation, request-phase, statement and control-lock milliseconds, plus
  `effectiveRotationMillis` from the **same retained J** (positive and at most
  the resource maximum). The latter is a total-operation allowance, not a budget
  to restart for retry, acquisition, request/capture handoff or disposal.
- The actual adopted authentication-version descriptor and public-trust
  SHA-256/byte-count/certificate-count, identical to the original root's values.
- The retained opening policy's recipe/evidence/route, driver URL, login budget
  and public driver properties. No raw password or private material enters D.

The resource belongs to the exact original pools; its descriptor identity and
unchanged configuration join the process's existing retained-graph checks.
Returned canonical/hash bytes remain defensive copies. Mutable lease owners,
tokens, clock samples, request/cutoff state, observed native results and completed
cleanup do not enter D. The DB implementation schema remains1; D3's inventory
version is separate from Flyway's V17 migration.

Protocol1 freezes the intended exclusive-fence-before-control capture, fixed
current-lease/full-binding checks, fresh terminal session and original total-J
budget. Merely encoding these settings does not prove those operations work.
The joined production path and meaningful owned-PostgreSQL/lifecycle tests must
be reviewed and pass before accepting that behavior. Enlarged control-row
storage/P sufficiency, cross-binding recovery, seal/checkpoint completion and
activation remain implementation work; this profile does not waive them.
