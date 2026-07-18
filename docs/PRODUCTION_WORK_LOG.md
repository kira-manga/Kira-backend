# Production hardening work log

This log records the release-preservation and production-hardening campaign started on 2026-07-18.
It contains no credentials or secret material.

## Baseline preservation

- Baseline branch: `main`
- Baseline commit: `59eb81c725c5c6e804837a1b85d5ccc3f43e5505`
- Commit message: `chore: preserve current backend baseline`
- Target remote: `https://github.com/kira-manga/Kira-backend.git`
- Safety scan: no credential, private-key, token, local environment, IDE, build-output, or generated
  artifact was staged. `.gitignore` was tightened for environment variants, private-key/keystore
  formats, IDE state, and common generated output before the baseline commit.
- Push status: externally blocked. `gh auth status` reports the configured GitHub token is invalid,
  and HTTPS Git authentication is unavailable (`could not read Username for 'https://github.com':
  Device not configured`). No force-push or history rewrite was attempted.

## Hardening branch

- Branch: `production-hardening`
- Created from baseline: `59eb81c725c5c6e804837a1b85d5ccc3f43e5505`

Batch commits, verification results, release identifiers, and any remaining external blockers are
appended here as work completes.

## Batch 1 — reproducible release and delivery path

- Release version set to `1.0.0`; changelog and release procedure added.
- Added a pinned, non-root, multi-stage OCI build; immutable version/SHA tagging and GitHub release
  workflow; SBOM, provenance, dependency vulnerability, secret, formatting, lint, static-analysis,
  coverage, and full Testcontainers CI gates.
- Added production Kubernetes topology with rolling deployment, readiness/liveness probes, graceful
  drain, disruption budget, ingress body/rate limits, and documented rollout/rollback.
- Added fail-closed registration defaults and production startup validation for profiles, JWT policy,
  HTTPS origins, trusted proxies, and verifying PostgreSQL TLS.
- GitHub Actions dependencies and container bases are pinned to immutable commits/digests.
- Local verification: `ktlintCheck` passed; `detekt` passed; focused production security tests passed;
  full Testcontainers suite passed (256 tests, 0 failures); production image build passed with image
  manifest `sha256:4641d9a86017b5be5bb2d0bbe31ab48489c5f3e741e00492fa7e7e5b27632178`.
- Docker runtime repair: started installed Colima and installed the missing Homebrew Buildx plugin.
  Testcontainers requires the documented Colima socket environment variables in this sandbox.

## Batches 2–5 — fail-closed security and operations

- Added explicit single-/multi-instance topology validation and a shared Redis authentication
  throttle using atomic, expiring, bounded counters. Redis failures fail closed.
- Disabled completions by default; isolated echo to dev/test; added a production HTTPS provider,
  per-user/global rate limits, daily quota, global concurrency, a bounded queue, corrected queue and
  provider timeouts/cancellation, and scheduled prompt/result retention. Multi-instance admission is
  coordinated atomically through Redis.
- Separated runtime and Flyway migration identities, disabled runtime migrations in production, added
  a one-shot migration entry point/job, least-privilege role SQL, TLS validation, backup/restore
  automation, a disposable two-database restoration test, and documented RPO/RTO/PITR/forward recovery.
- Added Prometheus metrics for HTTP/JVM/Hikari/auth/completion/publication/import, an internal management
  port, corrected readiness/liveness and network policy, structured correlation logging, scrape config,
  alert rules, and operator response guidance.
- Focused verification: formatting and detekt passed; 19 focused unit/integration tests across 10 suites
  passed with real PostgreSQL and Redis containers; database backup/restore and Prometheus scrape tests
  passed.
