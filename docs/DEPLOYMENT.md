# Production deployment

The repository ships a provider-neutral Kubernetes base under `deploy/kubernetes/base`. It describes
the supported production topology: two stateless application replicas behind a TLS ingress, shared
PostgreSQL and Redis services, health probes, rolling updates, a disruption budget, restricted pod
security, and bounded ingress traffic. It is a template, not a claim that infrastructure exists.

## Required overlay

Before applying it, create an environment overlay that replaces `api.kira.example`, the TLS secret,
the image with an immutable digest, trusted proxy CIDRs, resource sizing, and the broad example egress
rules with environment-specific destinations. Create `kira-backend-secrets` through the platform
secret manager; never commit it. The runtime JDBC URL must use `sslmode=verify-full`.

Render and validate before rollout:

```bash
kubectl kustomize deploy/kubernetes/base > /tmp/kira-rendered.yaml
kubectl apply --server-side --dry-run=server -f /tmp/kira-rendered.yaml
```

CI also runs `scripts/smoke/container-smoke.sh IMAGE`. It creates disposable TLS PostgreSQL
infrastructure, runs the packaged migration CLI, boots the exact image with the `prod` profile and
ephemeral signing/JWT material, then verifies readiness, liveness, and Prometheus before removing all
disposable resources. The rendered base is schema-checked in strict mode against Kubernetes 1.34.1;
the environment overlay still receives the server-side dry run shown above before deployment.

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

1. Verify database backup freshness and complete the pre-deploy restore test.
2. Run migrations with the dedicated migration identity and exact release image/job.
3. Pin the deployment image by registry digest, not a mutable tag.
4. Apply the overlay and wait for `kubectl rollout status deployment/kira-backend -n kira`.
5. Verify readiness, public ETag/signature metadata, authentication, publication, metrics, and alerts.
6. Record release SHA, image digest, migration version, and verification evidence in the change record.

The rolling strategy keeps existing pods ready while one new pod starts. A 60-second termination grace
period plus the pre-stop delay allows the ingress to drain before Spring's graceful shutdown begins.

## Rollback and forward recovery

Application-only rollback uses `kubectl rollout undo deployment/kira-backend -n kira` to the previous
known-good image digest. Database migrations are forward-only: never reverse or edit an applied Flyway
migration. If a release has migrated the schema, deploy a tested forward-recovery migration compatible
with both the prior and next application versions. Follow `docs/DISASTER_RECOVERY.md` for data loss or
database restoration; follow `docs/SOURCE_CONFIG_LIFECYCLE.md` only for publication-pointer repair.
