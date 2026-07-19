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
- Push status: pushed successfully to `origin/main` before hardening. No force-push or history rewrite
  was used.

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

## Batch 6 — signed documents and app integration

- Added deterministic `kira-source-signature-v1` Ed25519 signatures, persisted key id/chain metadata,
  signed public/meta responses, production startup validation, rotation scripts, and tamper/wrong-key/
  canonicalization/chain tests.
- Implemented the sibling app HTTPS client, pinned-key verification, complete signed cache envelope,
  rollback/replay rejection, bounded delivery, and bundled fallback. Shipping builds require an HTTPS
  origin and public-key pins.
- GitHub authentication is valid, but the backend repository has no signing secret names configured.
  No orphan public pin is treated as production-ready; the exact external key ceremony is documented.

## Batch 7 — robustness resolution

- Replaced recursive visibility validation on backend and app with bounded iterative traversal and
  explicit document/source collection complexity limits.
- Made partial imports adopt payload order in one transaction with a post-write rollback regression;
  batched admin revision metadata; separated completion queue/provider timeouts; hardened interruption,
  ETag, URL, numeric, rollback, JSON charset, assembly consistency, and completion-result constraints.
- Finding-by-finding disposition: `REVIEW_RESOLUTION_2026-07-18.md`.

## Final prerelease verification

- Clean Gradle `build` plus CycloneDX SBOM passed with **283 tests, 0 failures, 0 errors, 0 skipped**.
  This includes Testcontainers PostgreSQL/Redis, Flyway incremental/full migration, disposable database
  backup/restore, authentication/authorization, signing/tampering, publication/import rollback,
  completion quota/throttle/queue/timeout/retention/interruption, and coordination failure tests.
- The production OCI image built as a non-root multi-stage image. The packaged `prod` smoke test passed
  against disposable TLS PostgreSQL after running the migration CLI from the same image; readiness,
  liveness, and Prometheus were verified.
- Strict Kubernetes 1.34.1 schema validation passed for all 10 rendered resources. The deployment and
  migration Job both require the same immutable digest placeholder in environment overlays.
- The committed Gradle lock resolved 264 packages. Google OSV-Scanner v2.3.8 initially identified four
  medium findings; Jackson, Commons Lang, and Commons Compress were upgraded to their fixed releases,
  locks regenerated, and the repeated fail-closed scan returned **No issues found**. The image is pinned
  by digest in CI and needs no third-party API key. CycloneDX generation passed.
- Shell syntax, diff whitespace, credential/token/private-key pattern checks, and Kustomize rendering
  passed. GitHub CI retains the pinned Gitleaks full-history scan.

## External release inputs

- `gh secret list --repo kira-manga/Kira-backend` returned no configured production signing secrets.
  A production private key was therefore not fabricated or committed. The exact one-time generation,
  GitHub secret installation, public-pin propagation, and rotation procedure is in
  `SOURCE_DOCUMENT_SIGNING.md` and `scripts/signing/`.
- App integration is committed locally on `production-hardening-source-signing` at
  `6b7641a5cf1e23b9876b0d3d51651dc492a8b9d6` with 300 tests and Android/iOS compilation/lint green.
  The execution environment rejected `git push origin production-hardening-source-signing` as an
  external-repository export, so the configured app remote is still three commits behind locally.
- A deployed HTTPS backend origin and production Kubernetes/database/Redis infrastructure are not
  present in this workspace. Manifests, validation, rollout/rollback, backup/PITR/restore, alerts, and
  load-test automation are complete without claiming that those external services already exist.
