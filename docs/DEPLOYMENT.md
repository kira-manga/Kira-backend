# Production deployment

The repository ships a provider-neutral Kubernetes base under `deploy/kubernetes/base`. It describes
two replaceable application processes behind a TLS ingress, with shared PostgreSQL, Redis and
**persistent tutorial media** dependencies, health probes, rolling updates, a disruption budget,
restricted pod security, and bounded ingress traffic. It is a template, not evidence that the
infrastructure exists or is production-ready. **Never apply the unresolved base.**

## Server3 exact-image path (separate from Kubernetes/tag releases)

Server3's [runbook](../deploy/server3/README.md#exact-tested-image-promotion) documents its shared
Compose receiver and immutable image/archive recovery contract. Backend CI builds once and smokes
the actual Docker image ID; the `workflow_run` consumer authenticates that successful main attempt
and its finite, three-day artifact, verifies original bytes and image identity, and never rebuilds.
It freezes the artifact before the separate production approval job and rechecks current-main /
72-hour freshness immediately before keys/transfer. Consumer reruns are refused.

The host captures the healthy owned running `.Image` before load and uses full local image IDs for
activation, migration and checked application rollback. Active/previous **distinct successful**
gzip archives are content-addressed and survive GitHub expiry. Runtime/config drift or an absent
verified predecessor archive is an operator STOP, not a silent backup/tag fallback. The Backend wire
command now binds source, gzip SHA-256 and image ID; Web/Admin keep their existing three-token forms.
Root-only archive adoption is required for a healthy legacy predecessor without a matching record.

Backend is intentionally public: artifact authentication is not confidentiality. No credentials,
signing material, production responses or private review archives belong in image artifacts/logs.
Source/stub checks are not installed-host or production proof. Coordinate reviewed receiver/helper/
gateway installation and separately verify GitHub protections and SSH authority before rollout.
Neither application rollback path below nor Server3's ID rollback reverses database migrations.

## Required overlay

Before applying it, create an environment overlay that replaces `api.kira.example`, the TLS secret,
the image with an immutable digest, trusted proxy CIDRs, and the broad example egress rules with
environment-specific destinations. Resolve the media class/capacity selections below. Review resource
and scratch sizing; the media policy pins the current topology/security/probe/resource profile, so
changes to that profile require a reviewed policy update. Create `kira-backend-secrets` through the
platform secret manager; never commit it. The runtime JDBC URL must use `sslmode=verify-full`.

### Template checks versus resolved configuration checks

`scripts/ci/test_kubernetes_media_storage.py` consumes **rendered multi-document YAML**, not source
text. `check-template` is fixed to **TEMPLATE_ONLY** and admits only the designated unresolved
sentinels. `check-resolved` is fixed to **RESOLVED_CONFIG** and refuses those sentinels, missing
selection markers and incomplete/invalid selections. Neither command proves installed storage,
bootstrap, release readiness or recovery; `check-template` is never rollout admission.

The bounded policy supports ordered same-namespace ConfigMap `envFrom` (including prefixes), then
explicit literal or ConfigMap-key `env` overrides. ConfigMap references must be nonoptional and resolvable;
expansion-dependent values and uninspectable `envFrom` are refused. Existing explicit DB/Redis/JWT/
signing `secretKeyRef`s are allowed without reading their contents. It rejects other Spring inputs
outside the existing datasource/Redis/prod-profile envelope, relaxed tutorial-property aliases,
JSON/JVM/loader/process-environment overrides, command/args/working-directory changes, extra mounts
and init/sidecar workarounds. This is deliberately **not** a general Spring/Kubernetes interpreter;
arbitrary overlays, different image defaults and admission-time mutation require separate review.

The parser uses pinned PyYAML 6.0.3 SafeLoader with duplicate-key, alias/merge and unknown-tag
rejection. Its finite Linux x86_64 wheel pins support CPython 3.12–3.14; unsupported environments
fail rather than build a source distribution. Stdlib discovery runs the dictionary-only unit cases;
CI explicitly runs the parser tests and mutations of the actual rendered base in its owned venv.
The resolved positive control uses a **synthetic**, not installed, storage class/capacity.

For an authorized overlay review, use the pinned Kustomize/kubeconform tools from CI, strict
Kubernetes **1.34.1** schema validation on the **resolved render**, and the parser environment below.
Set `KIRA_KUSTOMIZE_OVERLAY` explicitly to the reviewed environment overlay, never the base. Run
the server-side dry run only against the authorized cluster/context; do not apply anything here.

```bash
(
  set -euo pipefail
  work="$(mktemp -d "${TMPDIR:-/tmp}/kira-media-policy.XXXXXXXX")"
  trap 'rm -rf -- "$work"' EXIT
  trap 'exit 130' INT
  trap 'exit 143' TERM
  mkdir "$work/tmp"
  export TMPDIR="$work/tmp"
  python3 -B -m venv "$work/venv"
  "$work/venv/bin/python" -I -B -m pip --isolated install --disable-pip-version-check \
    --require-hashes --only-binary=:all: --no-deps --no-cache-dir \
    --requirement scripts/ci/kubernetes-media-requirements.txt
  kubectl kustomize deploy/kubernetes/base > "$work/base.yaml"
  "$work/venv/bin/python" -I -B scripts/ci/test_kubernetes_media_storage.py check-template "$work/base.yaml"
  kubectl kustomize "${KIRA_KUSTOMIZE_OVERLAY:?select the reviewed environment overlay}" > "$work/resolved.yaml"
  kubeconform -strict -summary -kubernetes-version 1.34.1 "$work/resolved.yaml"
  "$work/venv/bin/python" -I -B scripts/ci/test_kubernetes_media_storage.py check-resolved "$work/resolved.yaml"
  kubectl apply --server-side --dry-run=server -f "$work/resolved.yaml"
  # Before cleanup, retain the exact resolved render/hash and review evidence in the change record.
  # Never retain credentials or Secret documents in that record.
)
```

CI also runs `scripts/smoke/container-smoke.sh IMAGE`. It creates disposable TLS PostgreSQL
infrastructure, runs the packaged migration CLI, boots the exact image with the `prod` profile and
ephemeral signing/JWT material, then verifies readiness, liveness, and Prometheus before removing all
disposable resources. The rendered base is schema-checked in strict mode against Kubernetes 1.34.1
and checked as TEMPLATE_ONLY; these checks are not installed PVC or bootstrap tests. The environment
overlay still needs resolved policy/schema checks and the server-side dry run before deployment.

## Shared tutorial-media storage and fresh bootstrap

All backend pods mount the same namespace-local **`kira-tutorial-media`** PVC read-write at
**`/var/lib/kira/tutorial-media`**. The ConfigMap explicitly sets `KIRA_TUTORIAL_MEDIA_DIRECTORY`
to that path, overriding the image's `/tmp/kira/tutorial-media` default. The claim declares
`volumeMode: Filesystem` and only `ReadWriteMany`; there is no per-pod claim, subPath, media emptyDir,
hostPath or automatic ephemeral fallback. Keep the claim namespace/name stable across releases and
rollbacks. Do not delete/recreate it during upgrades or attach a Deployment owner reference; plan
uninstall, namespace deletion and claim/PV retention separately with the platform owner.

The base deliberately requests the schema-valid **one-byte sentinel `"1"`** from
`replace-with-installed-rwx-class`. Both `kira.manga/storage-class-state` and
`kira.manga/storage-capacity-state` are `UNRESOLVED`. The real overlay must select an installed RWX
StorageClass explicitly, select a positive whole `Mi`, `Gi` or `Ti` capacity (within signed 64-bit
bytes), and set **both** annotations to `SELECTED`. Missing/empty/default-inferred classes and names
with placeholder tokens (including `example` and `default`) are outside this policy. Updating only
the class or deleting a comment cannot approve the capacity. These annotations record configuration
selection only: they do **not** configure a PV/StorageClass reclaim policy or prove provisioning.

The platform/storage owner must approve the actual class/CSI driver, capacity/growth, failure-domain
durability, filesystem semantics, encryption, snapshots, retention and reclaim behavior. Dynamically
provisioned storage may default to deletion on claim removal; verify the installed StorageClass/PV
policy and custody rather than relying on these PVC annotations. RWX advertising does not establish
multi-node consistency, atomic same-directory rename, read-after-write visibility or durability.

The pod retains UID/GID/fsGroup **10001**, nonroot execution, RuntimeDefault seccomp, dropped ALL
capabilities, no privilege escalation and a read-only root. Image-layer directory ownership does
not establish mounted-PVC ownership. Verify the provider's CSI/fsGroup behavior, mount permissions/
ACLs and actual read/write/rename access as UID10001; do not add a privileged/root chown workaround.

`/tmp` is a **separate disk-backed default-medium emptyDir with `sizeLimit: 128Mi`** for JVM/multipart
scratch. It is not durable media, a hard filesystem-quota guarantee, or proven production sizing.
Node ephemeral-storage pressure may exhaust it earlier. Verify node accounting, ownership and actual
writeability; the container smoke's 128m tmpfs is not identical to this disk-backed mount.

Steady-state `KIRA_TUTORIAL_SEED_ENABLED="false"` prevents concurrent ordinary replicas from seeding.
A fresh installation still requires the separately authorized, **serialized and quarantined first
seed** in [TUTORIALS.md](TUTORIALS.md#seed-and-storage), using the exact release image, database and
shared claim. Positive completion evidence must precede normal traffic. Empty tutorial tables can
pass startup validation and readiness; neither is bootstrap proof. The dedicated migration CLI Job
does not run the application seeder and does not need this application media mount.

**EXTERNAL VERIFICATION REQUIRED:** with the exact resolved overlay/image and real provider, prove
both replicas on distinct nodes can upload non-seed media on A/read the same bytes on B and reverse;
prove those bytes survive restart and reschedule. Exercise rolling/terminating overlap as well as
the nominal two replicas, atomic publication/read-after-write behavior, and writable media/scratch
under the restricted security context. `ScheduleAnyway`, a Bound claim, green schema/policy checks
and readiness are not these observations. Retain nonsecret evidence and bootstrap/ownership decisions.

### Matched recovery remains an operational gate

Follow [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md) for matched encrypted PostgreSQL/media capture,
custody/integrity and isolated restoration. Exclude **all** relevant writers **and positively drain**
in-flight work before capture, including ADMIN mutations, seed/maintenance processes and surge or
terminating pods. Pausing containers or counting Ready replicas is not proof of drain. Keep exclusion
through capture. A PVC does not fix Backend #5's separate filesystem/DB transaction gap.

The accepted restore helper requires an **absent** `NEW_MEDIA_TARGET` and refuses even an existing
empty directory. A mounted PVC root already exists: do not aim that helper directly at it, delete the
claim, or promise an in-place restore. Recovery owners must approve a separate isolated restore/
custody mapping and controlled ownership handoff for the selected DB/media pair before connecting
an application to it. Do not weaken the helper or change the steady-state mount to a per-pod subPath.
Installed recovery must demonstrate the documented **15-minute RPO / 2-hour RTO**, matched bytes,
application integrity and access controls; no backup/restore success is implied by this base.

## Required recovery monitoring overlay and external drill

`deploy/observability/prometheus-scrape.yaml` deliberately leaves `kira-backup` and
`kira-restore-verification` targets empty. The database/platform and service owners must provision
private, TLS-verified persistent exporters and replace those lists in the real monitoring overlay.
Follow `OBSERVABILITY.md` for the new last-verified-success gauge contract, the exact target labels
`service="kira-backend", environment="production"`, and the single authoritative scope per role.
Do not merely add global external labels, let exporter labels override scope identity, or use a
fresh scrape/producer-start time as recovery evidence. Extend the declared inventory and tests
before monitoring independent additional databases/scopes. Configure authentication via the secret
manager and restrict access to monitoring; no public exporter or credentials belong in this base.

The monitoring owner must register `alerts.yaml` with the **installed** Prometheus evaluator, verify
the rendered target inventory and gauge cardinality/type, and route both stale and telemetry-
unavailable alerts to the responsible recovery receiver (backup critical, restore warning).
Record the installed version, scrape/evaluation interval, lookback, clock health and Alertmanager
delivery/grouping settings. CI checks syntax and synthetic rules only, not this deployed overlay.

**EXTERNAL VERIFICATION REQUIRED before issue closure/release acceptance:** perform an authorized
producer-loss/removed-target drill for each role. Preserve truthful durable last-success state;
record physical failure, recognized absence, pending/firing and actual receiver delivery times,
including lookback/staleness and the continuous 5m unavailable hold. Restore registration/production
and verify alert resolution at that receiver. A returning old timestamp must still mean stale
success, not recovered freshness; separately verify a real successful backup/isolated restore and
its durable timestamp. Record approvals, rule/source SHA, rendered configuration, receiver evidence
and recovery outcome without secrets. Do not forge a production success or run a destructive restore
to satisfy this drill. No exporter, overlay or delivery PASS is implied by the committed template.

## Rollout

1. Complete the resolved overlay/storage/ownership gates and verify matched DB/media backup freshness
   and the pre-deploy isolated restore test.
2. Run migrations with the dedicated migration identity and exact release image/job.
3. Pin the deployment image by registry digest, not a mutable tag. For a fresh installation, complete
   the separately authorized quarantined first seed and its positive checks before normal traffic.
4. Apply the reviewed overlay without replacing the stable claim, retaining seed=false, and wait for
   `kubectl rollout status deployment/kira-backend -n kira` (use the approved namespace if different).
5. Verify readiness, cross-replica persistent media, public ETag/signature metadata, authentication,
   publication, metrics, and alerts.
6. Record release SHA, image digest, migration version, claim/provider and verification evidence in
   the change record without secrets.

The rolling strategy keeps existing pods ready while one new pod starts. A 60-second termination grace
period plus the pre-stop delay allows the ingress to drain before Spring's graceful shutdown begins.
Those timings are not positive proof that all database/media writers have drained for a backup.

## Rollback and forward recovery

Application-only rollback uses `kubectl rollout undo deployment/kira-backend -n kira` to the previous
known-good image digest. Database migrations are forward-only: never reverse or edit an applied Flyway
migration. If a release has migrated the schema, deploy a tested forward-recovery migration compatible
with both the prior and next application versions. Follow `docs/DISASTER_RECOVERY.md` for data loss or
database restoration; follow `docs/SOURCE_CONFIG_LIFECYCLE.md` only for publication-pointer repair.
