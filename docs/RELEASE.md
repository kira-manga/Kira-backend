# Release process

Kira backend uses semantic versions. The application version in `build.gradle.kts`, the heading in
`CHANGELOG.md`, and the Git tag must agree. Release tags have the form `vMAJOR.MINOR.PATCH`; the first
production release is `v1.0.0`.

## Reproducible release gate

From a clean checkout of the intended release commit:

```bash
./gradlew --no-daemon clean check bootJar cyclonedxBom
docker buildx build --load \
  --build-arg VERSION=1.0.0 \
  --build-arg VCS_REF="$(git rev-parse HEAD)" \
  --build-arg BUILD_DATE=1970-01-01T00:00:00Z \
  --tag "kira-backend:1.0.0-$(git rev-parse --short=12 HEAD)" .
```

The Gradle archives disable timestamps and use a stable entry order. The wrapper distribution and
container bases are SHA-256 pinned. Dependency locks are committed and must be updated deliberately
with `./gradlew dependencies --write-locks` whenever dependencies change.

## Publishing

The tag workflow checks out and verifies the tag commit, creates the JAR and CycloneDX SBOM, builds
the container from that same commit, and publishes both a semantic-version tag and a full Git-SHA tag
to `ghcr.io/kira-manga/kira-backend`. It also attaches provenance and creates the GitHub release. All
third-party workflow actions are pinned to immutable commits. Repository-scoped `GITHUB_TOKEN`
permissions are used; no registry credential is committed.

Create a release only after the final gate is green and `CHANGELOG.md` is current:

```bash
git tag -s v1.0.0 -m "Kira backend 1.0.0"
git push origin main v1.0.0
```

If signed Git tags are unavailable on the release workstation, use an annotated tag and rely on the
GitHub Actions artifact provenance. Never move or recreate an existing release tag.
