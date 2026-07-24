# Server3 production runbook

Kira runs as an isolated Docker Compose project under `/opt/kira`. PostgreSQL is reachable only on
the internal `kira-database` network. The API, public site, and admin studio bind to loopback ports
`18080`, `18081`, and `18082`; host Nginx is the only public entry point.

## Versioned files

- `compose.yaml` defines the database, one-shot migration, backend, public web, and admin services
  with resource limits and health checks. `/opt/kira/admin.env` contains only the admin container's
  runtime endpoints (`KIRA_BACKEND_URL` and `KIRA_ADMIN_ORIGIN`) and remains uncommitted.
- `postgres-init.sh` creates separate migration and runtime roles on the first database initialization.
- `nginx/*.conf` keeps the site and API virtual hosts independent from existing hosts.
- `kira-deploy` accepts an exact-SHA image stream, verifies it, backs up PostgreSQL before
  migrations, initializes the tutorial-media volume for the backend image's declared numeric
  UID/GID, health-gates activation, and restores the prior component image after a failed health
  check. The deployment fails closed if the volume is not writable by that runtime identity.
- `kira-deploy-gateway` restricts the CI SSH account to
  `deploy backend|web|admin <40-character-sha>`.
- `kira-deploy.sudoers` grants only the two root deploy commands. `kira-deploy.sshd.conf` forces every login for that account through the gateway and disables forwarding, TTYs, and password authentication.

Do not commit the production `*.env`, TLS keys, signing keys, initial administrator credentials, database dumps, or deployment private key.

## GitHub production environment

Create a protected `production` environment in the backend, public web, and admin repositories.

| Kind | Name | Value |
|---|---|---|
| Variable | `SERVER3_HOST` | `213.130.144.21` |
| Variable | `SERVER3_PORT` | `22` |
| Variable | `SERVER3_USER` | `kira-deploy` |
| Secret | `SERVER3_SSH_PRIVATE_KEY` | Dedicated restricted key; never a personal SSH key |
| Secret | `SERVER3_KNOWN_HOSTS` | Pinned server3 host-key line |

The web environment also needs `ANDROID_APP_SHA256_CERT_FINGERPRINT`, `ANDROID_PACKAGE_NAME`, `APPLE_TEAM_ID`, and `IOS_BUNDLE_ID`. Require manual environment approval until the first automated release is verified.

The admin repository needs no backend credential: its server-side BFF uses the operator's
short-lived ADMIN token. Configure `/opt/kira/admin.env` with:

```text
KIRA_BACKEND_URL=http://backend:8080
KIRA_ADMIN_ORIGIN=https://admin.kiramanga.me
```

## Operations and recovery

Run deployments only through the workflow or restricted stream command. For a manual backup, freeze
tutorial ADMIN mutations and run `sudo /usr/local/sbin/kira-deploy backup`. It must create a matched
PostgreSQL dump and `kira-tutorial-media` archive with bundle checksums. Files remain root-only in
`/opt/kira/backups`; the web cache volume is disposable.

Deploy sequentially through the restricted SSH gateway on port 22: backend image/migration/seed
first, then verify public categories, all four seeded tutorials, media, ETags, and bilingual parity.
Only after that gate succeeds deploy web. A newly published guide must appear within 60 seconds
without a web rebuild. Host Nginx virtual-host files and their hashes remain unchanged.

To move Kira, provision Docker and Nginx on the destination, copy the versioned files, securely transfer the `/opt/kira` secrets and a verified matched database/media bundle, restore into new `kira-postgres-data` and `kira-tutorial-media` volumes, reissue public certificates, test with local DNS overrides, and only then change DNS. Do not copy Let's Encrypt private keys when certificates can be reissued. Keep mail services and mail DNS outside this deployment.
