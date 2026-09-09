# Server3 production runbook

The versioned server3 topology is a Docker Compose project under `/opt/kira`. PostgreSQL is reachable
only on the internal `kira-database` network. The API, public site, and admin studio bind to loopback
ports `18080`, `18081`, and `18082`; host Nginx must be the only public entry point. The trusted-ingress
prerequisites below require verification on the installed host, not just a valid Compose file.

## Versioned files

- `compose.yaml` defines the database, one-shot migration, backend, public web, and admin services
  with resource limits and health checks. `/opt/kira/admin.env` supplies `KIRA_ADMIN_ORIGIN` and
  remains uncommitted; Compose owns the BFF's internal backend URL and trusted-ingress opt-in.
- `ingress.env.example` documents required, nonsecret topology inputs. Provision them in
  `/opt/kira/ingress.env`; the intentionally empty example is not a deployable configuration.
- `postgres-init.sh` creates separate migration and runtime roles on the first database initialization.
- `nginx/*.conf` keeps the site and API virtual hosts independent from existing hosts.
- `kira-deploy` accepts an exact-SHA image stream, verifies it, preflights the candidate Compose
  configuration before activation, backs up PostgreSQL before
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
KIRA_ADMIN_ORIGIN=https://admin.kiramanga.me
```

Compose explicitly sets `KIRA_BACKEND_URL=http://backend:8080` and
`KIRA_ADMIN_TRUSTED_INGRESS="true"`, overriding contradictory `admin.env` values. The BFF must not
hairpin through the public API: its ingress would overwrite the BFF-supplied client identity.
These settings do not change generic deployments, where the Admin opt-in remains off by default.

## Mandatory trusted-ingress configuration

The API HTTP bootstrap/TLS and Admin TLS proxy locations overwrite `X-Real-IP` and
`X-Forwarded-For` with Nginx's observed `$remote_addr`, and remove `Forwarded`. They never preserve
a caller-supplied forwarding prefix. The BFF uses only bounded, validated `X-Real-IP` for login and
password step-up, emitting one new XFF literal. Missing/invalid metadata falls back to the BFF peer:
that is safe against caller-selected identity, but is still shared/degraded throttling.

Copy the example to `/opt/kira/ingress.env` and provision **all** values; no address is defaulted:

| Variable | Required meaning |
|---|---|
| `KIRA_SERVER3_ADMIN_SUBNET` | Collision-free IPv4 CIDR for the dedicated `kira-admin-ingress` bridge. |
| `KIRA_SERVER3_ADMIN_GATEWAY` | Provisioned gateway within that subnet. |
| `KIRA_SERVER3_ADMIN_ADDRESS` | Available, pinned Admin IPv4 address within the subnet, distinct from the gateway. |
| `KIRA_SERVER3_HOST_PEER` | Exact numeric peer observed by Backend for host Nginx's loopback connection, distinct from the Admin address; never a guessed gateway or a CIDR. |

The deployment helper passes `images.env` then `ingress.env` to Compose without sourcing either
file as shell code. Compose also injects both peer variables explicitly into Backend's environment;
CLI `--env-file` interpolation alone would not do that. Do not supply contradictory ingress values
through the deployment shell: shell variables take precedence over these files.

Backend explicitly runs `prod,server3`. Its server3 profile enables forwarded trust for exactly the
declared host peer and pinned BFF peer, not the whole Docker/private subnet. Its eager policy must
reject missing/contradictory effective settings before listener acceptance: `prod` is required,
forwarded trust is on, the two exact peers agree, `server.forward-headers-strategy` is `none`, and
Tomcat's `remote-ip-header`/`protocol-header` overrides must be absent or blank. Strategy `none`
alone does not prevent a `RemoteIpValve` when those overrides are present. Generic direct deployments
retain their secure forwarding-off defaults outside server3.

Each `activate_backend`, `activate_web`, and `activate_admin` path runs a quiet Compose configuration
preflight with the candidate image and the migration profile, before service activation, migration,
or tutorial-media volume mutation. Missing/empty ingress inputs or an invalid Compose/env combination
fail without rendering configuration or parser diagnostics. This is a configuration gate, not proof
of installed network isolation or actual client identity. Never publish resolved Compose output,
`config --environment`, env-file contents, tokens, or passwords in deployment logs.

### Installed topology — EXTERNAL VERIFICATION REQUIRED

Before enabling trust or admitting public authentication traffic, an authorized operator must:

1. Verify a supported **Docker Engine >= 28.0.0** and a Compose client supporting this model.
   Older Engines allowed same-L2 access to localhost-published ports. Confirm effective NAT filtering
   and host firewall rules; no daemon direct-routing, trusted-interface, routed/unprotected bridge,
   or custom firewall bypass may expose Admin to untrusted peers. Source configuration is not proof.
2. Provision the dedicated **non-internal NAT bridge**, with IPv6 disabled as declared. Only Backend
   and Admin may join `kira-admin-ingress`; Admin must no longer join `kira-proxy`, while Web stays on
   `kira-proxy`. Backend retains its database/Web connections. Do not use `internal:true`: an
   internal-only bridge does not provide the host port publication used by the existing Nginx upstream.
3. Check subnet/address collisions and recreate affected network/container membership deliberately.
   Observe Backend's actual Nginx and BFF peers in that topology, then populate/confirm the exact
   values. Recheck after every relevant network/container recreation or daemon routing change;
   service order, Compose priority, a subnet's apparent gateway, and old observations are not proof.
4. Inspect effective Nginx configuration, including global includes and any real-IP/CDN settings.
   `$remote_addr` may only be rewritten under a separately reviewed trusted-upstream policy. Install
   the reviewed API/Admin header policy, validate Nginx configuration and reload it before trusting
   forwarding. Keep the redirect-only Admin HTTP and public Web vhosts otherwise unchanged.
5. Verify that Web and external clients cannot reach Admin's container IP or a host-published bypass;
   only the trusted host/Backend boundary may reach it directly. Requests through Nginx must have
   overwritten identity even when callers submit XFF, X-Real-IP or Forwarded. This design assumes
   trusted host processes and Backend; it does not defend a compromised host or Backend.
6. Coordinate the first configuration/image transition in a maintenance or staged topology with
   public auth ingress closed until the prerequisites, effective startup policy, and both BFF/Backend
   versions agree. Install the reviewed Nginx, Compose, helper and provisioned env files as a separate
   authorized configuration change; an image-stream deployment does not install or reload them.
   After that authorized activation, use bounded synthetic two-client checks for login/step-up
   isolation and spoof rejection, not real password guessing. Keep verification records free of secrets.

The source/config checks and application tests cannot establish those installed-host facts. Until
the operator checks succeed, production ingress/client-identity acceptance remains **EXTERNAL
VERIFICATION REQUIRED**, not a deployment or issue-closure claim.

## Operations and recovery

Run deployments only through the workflow or restricted stream command. For a manual backup, freeze
tutorial ADMIN mutations and run `sudo /usr/local/sbin/kira-deploy backup`. It must create a matched
PostgreSQL dump and `kira-tutorial-media` archive with bundle checksums. Files remain root-only in
`/opt/kira/backups`; the web cache volume is disposable. The independent `backup` command neither
loads `ingress.env` nor invokes Compose/preflight, so ingress repair cannot prevent a data backup.
Automatic image rollback retains the installed Compose/Nginx isolation contract; do not restore an
old shared Admin/Web network or remove header sanitization while trusted-ingress mode is enabled.

Deploy sequentially through the restricted SSH gateway on port 22: backend image/migration/seed
first, then verify public categories, all four seeded tutorials, media, ETags, and bilingual parity.
Only after that gate succeeds deploy web. A newly published guide must appear within 60 seconds
without a web rebuild. Normal image deployments leave installed Nginx/Compose files unchanged;
record and verify their separately reviewed hashes during the trusted-ingress configuration change.

To move Kira, provision Docker and Nginx on the destination, copy the versioned files, securely transfer the `/opt/kira` secrets and a verified matched database/media bundle, restore into new `kira-postgres-data` and `kira-tutorial-media` volumes, reissue public certificates, test with local DNS overrides, and only then change DNS. Do not copy Let's Encrypt private keys when certificates can be reissued. Keep mail services and mail DNS outside this deployment.
