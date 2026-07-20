# Server3 production runbook

Kira runs as an isolated Docker Compose project under `/opt/kira`. PostgreSQL is reachable only on the internal `kira-database` network. The API and static site bind to loopback ports `18080` and `18081`; host Nginx is the only public entry point.

## Versioned files

- `compose.yaml` defines the database, one-shot migration, backend, and web services with resource limits and health checks.
- `postgres-init.sh` creates separate migration and runtime roles on the first database initialization.
- `nginx/*.conf` keeps the site and API virtual hosts independent from existing hosts.
- `kira-deploy` accepts an exact-SHA image stream, verifies it, backs up PostgreSQL before migrations, health-gates activation, and restores the prior application image after a failed health check.
- `kira-deploy-gateway` restricts the CI SSH account to `deploy backend|web <40-character-sha>`.
- `kira-deploy.sudoers` grants only the two root deploy commands. `kira-deploy.sshd.conf` forces every login for that account through the gateway and disables forwarding, TTYs, and password authentication.

Do not commit the production `*.env`, TLS keys, signing keys, initial administrator credentials, database dumps, or deployment private key.

## GitHub production environment

Create a protected `production` environment in both the backend and web repositories.

| Kind | Name | Value |
|---|---|---|
| Variable | `SERVER3_HOST` | `213.130.144.21` |
| Variable | `SERVER3_PORT` | `22` |
| Variable | `SERVER3_USER` | `kira-deploy` |
| Secret | `SERVER3_SSH_PRIVATE_KEY` | Dedicated restricted key; never a personal SSH key |
| Secret | `SERVER3_KNOWN_HOSTS` | Pinned server3 host-key line |

The web environment also needs `ANDROID_APP_SHA256_CERT_FINGERPRINT`, `ANDROID_PACKAGE_NAME`, `APPLE_TEAM_ID`, and `IOS_BUNDLE_ID`. Require manual environment approval until the first automated release is verified.

## Operations and recovery

Run deployments only through the workflow or the restricted stream command. For a manual database backup, run `sudo /usr/local/sbin/kira-deploy backup`. Dumps and SHA-256 files are stored root-only in `/opt/kira/backups`.

To move Kira, provision Docker and Nginx on the destination, copy the versioned files, securely transfer the `/opt/kira` secrets and a verified database dump, restore into a new `kira-postgres-data` volume, reissue public certificates, test with local DNS overrides, and only then change DNS. Do not copy Let's Encrypt private keys when certificates can be reissued. Keep mail services and mail DNS outside this deployment.
