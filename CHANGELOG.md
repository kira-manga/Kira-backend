# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.0.0] - 2026-07-19

### Added

- Kotlin/Spring Boot backend for source-config authoring, validation, publication, lifecycle, and
  immutable canonical document snapshots.
- Stateless JWT authentication, admin user management, audit history, and completion-provider seam.
- PostgreSQL/Flyway persistence with publication locking, revision floors, ETags, and startup
  consistency checks.
- Request bounds, auth throttling, generic problem responses, credential timing equalization, and
  bounded completion execution from the preserved hardening baseline.
- Reproducible OCI/GitHub delivery, dependency locking, SBOM/provenance, fail-closed CI security
  scanning, production container smoke testing, and validated Kubernetes deployment manifests.
- Redis-backed multi-instance authentication/completion admission, quotas, retention, HTTPS provider
  support, least-privilege migration/runtime database roles, backup/restore verification, metrics,
  alerts, and structured redacted logging.
- Deterministic Ed25519-signed source documents with anti-rollback metadata, rotation tooling, and a
  fail-closed Android/iOS remote client with bundled fallback.
- Bounded iterative source validation, atomic ordered import, batched admin reads, strict ETags, and
  regression coverage for publication, provider, coordination, and database failure paths.

[Unreleased]: https://github.com/kira-manga/Kira-backend/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/kira-manga/Kira-backend/releases/tag/v1.0.0
