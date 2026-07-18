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
