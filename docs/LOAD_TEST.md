# Production load test

`load/k6-production-smoke.js` exercises the important read paths: signed document metadata/body,
strong-ETag conditional reads, filtered source listing, and DB-backed JWT identity. It uses 20 public
and 5 authenticated concurrent users for two minutes and fails on error rate ≥1% or documented p95/p99
latency thresholds. It never submits prompts, publishes data, or uses an admin token.

Run only against an authorized staging topology that matches production replica/Redis/PostgreSQL/ingress
settings:

```bash
export KIRA_API_URL=https://staging-api.example.com/api/v1
export KIRA_LOAD_TOKEN='<short-lived non-admin access token>'
k6 run load/k6-production-smoke.js
```

Record k6 version, backend Git SHA/image digest, topology, dataset size, threshold result, and exported
Prometheus dashboard snapshots in the release evidence. Stop and investigate if Hikari utilization,
Redis latency, queue saturation, error rate, or p99 latency alerts fire. The script intentionally
requires HTTPS and refuses an absent token so it cannot silently test a different security posture.
