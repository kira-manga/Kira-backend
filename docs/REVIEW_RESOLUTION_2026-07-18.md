# Adversarial review resolution

This is the disposition of every finding in `REVIEW_2026-07-12.md` against the production-hardening
branch. The original review remains immutable historical evidence.

## High and medium findings

| Finding | Disposition |
|---|---|
| R2-H1 header-name bypass | Resolved: names are trimmed, RFC-token validated, normalized, and regression tested. |
| R4-H1 unbounded model | Resolved: the controller rejects models over 128 characters before persistence. |
| R1-M1 email/IP-only throttle | Resolved: identity and aggregate IP spray limits use bounded atomic Redis state in multi-instance production. |
| R1-M2 login timing oracle | Resolved: unknown/disabled candidates perform a decoy password hash check. |
| R1-M3 framework 4xx mapped as 500 | Resolved: method/media/binding/type failures have explicit sanitized 4xx mappings; DB conflicts map to 409. |
| R1-M4 missing body cap | Resolved: application-wide 256 KiB and import 5 MiB limits are enforced before MVC; ingress mirrors them. |
| R2-M5 recursive visibility graph | Resolved on backend and app with Kahn traversal plus source/document complexity ceilings. |
| R3-M6 duplicate create race | Resolved: insert is flushed in the transaction and a uniqueness race maps to `SOURCE_ALREADY_EXISTS`; DB conflicts never leak SQL. |
| R3-M7 import publishes drafts | Resolved: draft-only heads are reported in `skippedDraft` and never mutated or published. |
| R3-M8 partial import order | Resolved: payload sources receive payload order, catalog-only sources retain relative order afterward, and one snapshot covers the transaction. |
| R3-M9 admin list N+1 | Resolved: head/current/latest revision metadata is returned by one joined query. |
| R4-M10 queue/timeout/pool | Resolved: fixed workers, bounded queue, independent queue wait and provider execution timeouts, cancellation, and overload responses. |
| R4-M11 JSON charset | Resolved: MVC JSON and problem JSON responses explicitly declare UTF-8. |

## Low findings

| Finding | Disposition |
|---|---|
| R1-L1 access log identity | Resolved: authenticated id/role are retained as request attributes and restored for the outer access log. |
| R1-L2 JWT system clock | Resolved: token issuance uses the injected `Clock`. |
| R2-L3 icon count | Closed as stale review prose: the backend catalog is the app's exact 40-key set and the check is advisory. |
| R2-L4 malformed port classification | Resolved: an unparseable authority is `URL_INVALID`; an actually absent host remains `URL_HOST_MISSING`. |
| R2-L5 first unknown-key warning | Accepted by design: compatibility import emits one bounded advisory without reflecting arbitrary payload structure; strict authoring rejects the exact offending key. |
| R2-L6 strategy KDoc count | Closed: catalog documentation and the mirrored whitelist agree. |
| R2-L7 NaN/Infinity number default | Resolved on backend and app: defaults must parse to a finite number. |
| R3-L8 rollback first-publish | Resolved: rollback requires an existing published baseline and otherwise returns 409. |
| R3-L9 assembly inner join omission | Resolved: a left join plus non-null assertion aborts and rolls back materialization instead of serving a partial document. |
| R3-L10 removed skip accounting | Accepted lifecycle semantics: terminal removed sources are always reported as skipped and can never be revived by import. |
| R3-L11 sequence visibility | Resolved operationally: least-privilege role automation grants runtime `USAGE, SELECT` on sequences; startup remains fail-closed. |
| R3-L12 no-op audit precision | Resolved: import timestamps use the same whole-second precision as snapshots. |
| R3-L13 concurrency test gap | Partially superseded: publication/revision races remain covered; import now has a forced mid-transaction failure rollback test and duplicate inserts fail through the constrained path. |
| R4-L14 non-positive timeout | Resolved: timeout, queue timeout, retention, and cleanup interval reject zero/negative values at binding. |
| R4-L15 NAND named XOR | Resolved by V8: exactly one of result/error must be non-null. |
| R4-L16 reflected source id | Resolved: not-found/gone/conflict details are generic while stable codes retain meaning. |
| R4-L17 interrupted outcome persistence | Resolved: the interrupt flag is cleared for the terminal transaction and restored afterward. |
| R4-L18 lenient ETag syntax | Resolved: only the exact quoted strong entity-tag matches; malformed and weak values return the body. |
