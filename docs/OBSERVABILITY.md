# Observability and production response

Production exposes health and Prometheus metrics on the internal management port `9090`; the public
ingress routes only application port `8080`. Network policy permits management traffic only from the
`monitoring` namespace. Health details are never returned: liveness is process-only, while readiness
requires the application state, PostgreSQL, and Redis.

Use `deploy/observability/prometheus-scrape.yaml` as a provider-neutral scrape example and load
`deploy/observability/alerts.yaml` into Prometheus-compatible alerting. The backup controller/platform
must export `kira_backup_last_success_timestamp_seconds`; the monthly restore job must export
`kira_restore_test_last_success_timestamp_seconds`. These external metrics are not fabricated by the
application.

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
- **Backup/restore alert:** page the database owner, verify storage/checksums/WAL, run an isolated
  restoration drill, and treat an unverified backup as unavailable until proven restorable.
