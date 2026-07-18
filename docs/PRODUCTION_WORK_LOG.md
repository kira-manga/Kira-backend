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
