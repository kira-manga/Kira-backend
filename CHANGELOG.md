# Changelog

All notable changes follow [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and releases use
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Production delivery, security, resilience, observability, signed-document, and app-integration
  hardening is tracked on the `production-hardening` branch.

## [1.0.0] - 2026-07-18

### Added

- Kotlin/Spring Boot backend for source-config authoring, validation, publication, lifecycle, and
  immutable canonical document snapshots.
- Stateless JWT authentication, admin user management, audit history, and completion-provider seam.
- PostgreSQL/Flyway persistence with publication locking, revision floors, ETags, and startup
  consistency checks.
- Request bounds, auth throttling, generic problem responses, credential timing equalization, and
  bounded completion execution from the preserved hardening baseline.

[Unreleased]: https://github.com/kira-manga/Kira-backend/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/kira-manga/Kira-backend/releases/tag/v1.0.0
