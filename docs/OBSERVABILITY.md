# Observability and production response

Production exposes health and Prometheus metrics on the internal management port `9090`; the public
ingress routes only application port `8080`. Network policy permits management traffic only from the
`monitoring` namespace. Health details are never returned: liveness is process-only, while readiness
requires the application state, PostgreSQL, and Redis.

Use `deploy/observability/prometheus-scrape.yaml` as a provider-neutral scrape **template** and load
`deploy/observability/alerts.yaml` into Prometheus-compatible alerting. The recovery jobs deliberately
have empty target lists: no external exporter, production overlay or alert delivery is installed by
these files. Empty targets must produce telemetry-unavailable alerts, not a healthy recovery claim.

## External recovery telemetry contract

This is a **required new configuration contract**, not an observation of deployed infrastructure.
The database/platform owner supplies the backup producer; the service/database owners supply the
restore-verification producer and route both roles to an accountable recovery owner.

| Scrape job | Required gauge | Scope labels | Severity |
|---|---|---|---|
| `kira-backup` | `kira_backup_last_success_timestamp_seconds` | `service="kira-backend", environment="production"` | critical |
| `kira-restore-verification` | `kira_restore_test_last_success_timestamp_seconds` | `service="kira-backend", environment="production"` | warning |

For **each** role, configure exactly one authoritative, persistently served floating-point gauge
for this scope. Its value is the Unix timestamp in seconds of the **last verified successful**
operation, including the backup integrity/catalog checks or isolated restoration verification in
`DISASTER_RECOVERY.md`. Persist that value outside an ephemeral job and rehydrate it after producer
restart. Do not substitute exporter startup, scrape time, a scheduled attempt, or an unverified
operation. With no verified history, omit the gauge; never fabricate a recent success. These are
external metrics, not application Actuator metrics.

The scrape configuration owns `job`, `service` and `environment`; retain `honor_labels: false` and
the target labels, not just Prometheus external labels. Provision private monitoring-only endpoints,
verified TLS and any secret-manager-backed authentication in the real overlay. Never put credentials
in this template or expose the producers through the public application ingress. Keep identity
low-cardinality: do not label samples with run IDs, filenames, backup locations, database URLs or
secrets. The automatically attached `instance` is useful for raw diagnostics, not alert identity.

The declared inventory is **one recovery scope per role**. Multiple independent databases, regions,
or other recovery scopes require an explicit expanded inventory, selectors/grouping and rule tests
before use; one surviving series must not represent an independently missing scope. Production
cardinality/type checks are owner obligations; these rules do not validate duplicate authorities or
native histograms masquerading as the required gauge.

### Alert meaning and timing

- `KiraBackupStale` requires a valid last success **older than 900 seconds** continuously for **5m**.
  `KiraRestoreVerificationStale` requires **older than 2678400 seconds (31 days)** for **1h**.
  Their thresholds, holds and severities are unchanged; exact age equality is not stale.
- `KiraBackupTelemetryUnavailable` and `KiraRestoreVerificationTelemetryUnavailable` require
  **5m continuously** absent or invalid telemetry. Invalid means zero/negative, future, NaN or either
  infinity. Only a positive finite value at or before Prometheus evaluation time is valid. There is
  no implicit clock-skew allowance: verify producer/evaluator clock health, rather than shifting a
  timestamp to silence the alert. Nonpositive samples do not also activate the stale branch.
- Both predicates are grouped by `{job, service, environment}` **before** the alert hold. Changing
  exporter instance/producer labels or switching invalid to absent must not reset continuous pending
  time. The grouped alert value is an **indicator of 1**, not age; inspect the raw gauge and
  `time() - <scoped gauge>` for diagnosis.
- Lookback/staleness recognition precedes the unavailable alert's 5m hold. A silent producer can
  remain visible during Prometheus's lookback (commonly 5m), plus scrape/evaluation timing. A stale
  marker may remove it sooner. This is **not** a five-minute physical-failure-to-notification promise;
  Alertmanager routing/grouping adds its own delay. There is no extra range-window grace.
- A brief recovery before 5m resets unavailable pending time. Restoring a valid but old gauge clears
  telemetry-unavailable but starts/retains the appropriate stale-success condition. Only a genuinely
  new verified success restores freshness; restarting an exporter does not.

### Rule validation

From the backend repository, using the pinned Prometheus/promtool **3.14.0** used by CI:

```sh
promtool check rules deploy/observability/alerts.yaml
promtool test rules deploy/observability/alerts.test.yaml
promtool check config deploy/observability/prometheus-scrape.yaml
```

The compact fixture uses a positive fixed epoch to cover the 31-day boundary without simulating a
month. It checks pending/firing, absent/up-independent and wrong-scope inputs, invalid values,
lookback/stale-marker loss, recovery and logical-scope continuity. These commands establish only
rule/configuration behavior: an empty-target template passing syntax validation does **not** prove
production targets, loaded rules, durable metric production or receiver delivery. See the required
external drill in `DEPLOYMENT.md`.

## Dashboard

Build one service dashboard with these bounded, low-cardinality panels:

- availability, pod readiness/restarts, request rate, 4xx/5xx rate, and p50/p95/p99 HTTP latency;
- JVM heap/GC/threads and process CPU;
- Hikari active/max/pending/timeouts plus PostgreSQL availability and query latency from the database
  exporter;
- `kira_auth_throttle_events_total` by dimension/outcome;
- completion executor active workers, queue depth/remaining capacity, admissions, terminal outcomes,
  timeout/error codes, and retention deletions;
- source publication/import event rates and endpoint failures;
- migration Job state, backup freshness, restore-test freshness, Redis availability, and ingress 413,
  429, and timeout counts.

Never add email, user/source IDs, models, prompts, results, tokens, URLs, or raw exception messages as
metric labels. Correlate an incident through the validated `X-Request-Id`/`requestId` structured-log
field. Logs must continue to omit credentials, Authorization/cookies, prompt/result bodies, config
bodies, and SQL parameter values.

## Response playbooks

- **Unavailable/readiness:** check rollout state, pod events, PostgreSQL/Redis TLS and capacity, then
  stop rollout or return to the previous image digest. Do not route to unready pods.
- **5xx/latency/database exhaustion:** correlate request IDs, inspect pool pending/active, slow queries,
  CPU/GC, and downstream latency. Shed completion traffic before increasing DB capacity blindly.
- **Throttle spike:** verify ingress client-IP/proxy settings, distinguish abuse from a Redis outage,
  and do not disable throttling. A shared-throttle outage intentionally fails authentication closed.
- **Completion saturation/timeouts:** disable completions or reduce admission, inspect the provider,
  queue, and global concurrency. Do not enlarge an unbounded queue; this service has a hard bound.
- **Migration/publication/import failure:** stop rollout/publication, preserve logs and audit evidence,
  use the forward-recovery and pointer-consistency runbooks, and never edit an applied migration.
- **Backup/restore stale:** engage the recovery owner (critical backup page; warning restore route),
  verify last-success evidence, storage/checksums/WAL, and perform an authorized isolated restoration
  drill. Treat an unverified backup as unavailable until proven restorable.
- **Recovery telemetry unavailable:** inspect the expected scope's target registration, scrape state,
  durable producer state, raw gauge/type/cardinality and clocks, even if application health and `up`
  are green. Restore truthful telemetry and separately verify freshness. Do not silence the condition
  by inventing a timestamp, removing the scope from monitoring, or routing away its alerts.
