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
- `verify_hsts.py` is an explicitly invoked, read-only post-install header/redirect check for all
  four public hosts; it is not part of image deployment or a health check.
- `kira-deploy` validates a bounded image stream before load, captures the actual healthy owned
  predecessor **image ID before any tag changes**, and activates/persists full immutable IDs. It
  preflights Compose quietly, backs up PostgreSQL before migration, initializes Backend media for
  the image's numeric UID:GID, and checks both the actual runtime ID and health. A failed deployment
  remains failed even when a separately checked application rollback succeeds.
- `scripts/ci/image_release.py` is the finite producer/consumer packet verifier. Install that exact
  reviewed file as `/usr/local/libexec/kira-image-release.py` for the receiver's archive-only commands;
  it has no runtime dependency on a sibling checkout or third-party Python package.
- `kira-deploy-gateway` permits exactly the three wire forms documented below, never root-only
  `activate`, `adopt` or `backup`. `kira-deploy.sudoers` retains the existing three component deploy
  prefixes; the receiver itself enforces exact arguments. No sudoers grant was broadened.
  `kira-deploy.sshd.conf` forces the gateway and disables forwarding, TTYs, and password authentication.

Do not commit the production `*.env`, TLS keys, signing keys, initial administrator credentials, database dumps, or deployment private key.

## Exact tested-image promotion

Backend CI builds **once**, retaining the existing `VERSION=1.0.0` behavior. The container job
reconciles Buildx's `imageid` with Docker's `.Id`, smokes that immutable ID with the unchanged
production-profile smoke, confirms the tag still identifies it, saves that one tag once, and
compresses once. The strict archive check binds raw image configuration to its SHA-256 image ID
and the layer bytes to its DiffIDs. A registry manifest digest or `RepoDigests` is not this local
Docker image ID. The Kubernetes/semantic-tag release workflow is a separate path.

Only a push/main attempt can publish `backend-image-<run_id>-<run_attempt>`, containing exactly
`image.tar.gz` and `receipt.json`. The receipt binds repository/source tree, producing workflow/run/
attempt, trusted contract-file hashes, image ID/platform/revision, original and expanded archive
bytes/digests, and the exact-ID smoke result. Upload is immutable (`overwrite: false`) with three-day
retention. The artifact may exist before parallel supply-chain work finishes; it is **not eligible**
until authenticated CI **and** that attempt's `verify`, `supply-chain` and `container` jobs succeed.

`Deploy server3` retains the existing `workflow_run: CI/completed` entry and **never builds, runs,
resaves or recompresses an image**. Its `contents: read` / `actions: read` token authenticates canonical
repository `kira-manga/Kira-backend` (ID `1304735394`), active workflow ID/path, current and selected
producer attempt, complete bounded job/artifact listings and the unique individual artifact record.
It fails on missing API evidence, incomplete lists, skipped gates, forks, changed attempts, replaced/
expired artifacts or digest mismatches; it never follows a replacement candidate or rebuilds one.

Credential-free preflight (apart from the read-only workflow token) freezes the artifact ID, actual
outer ZIP digest/length, source/tree/run/attempt and complete receipt/image/archive identity **before**
the separate `production` environment job. Both jobs check out `${{ github.sha }}`: the trusted
default-branch **consumer**, not `workflow_run.head_sha`'s producer-selected scripts. The fixed
producer workflow/helper/smoke bytes and consumer workflow are compared against authenticated Git
bytes. The event's producer source must still be current `main`; artifact creation must be less than
72 hours old and its actual API expiry must be in the future. These checks run again after download,
after environment approval, and immediately before SSH key materialization. Consumer attempts other
than `1` are refused: use a new eligible producer completion, not a rerun that silently reselects.

The Backend repository is **public by deliberate policy**. API authentication establishes provenance,
not confidentiality. Candidate images/receipts must never contain secrets, signing material,
production responses or private cross-repository review archives. API storage redirects are followed
only as a new allowlisted HTTPS request without Authorization; signed URLs and raw responses are not
logged. Only the original validated gzip is streamed to the fixed receiver.

### Finite packet and installed receiver contract

- Limits: 512 MiB outer ZIP and gzip, 2 GiB expanded Docker tar, 16 KiB receipt, exactly two regular
  ZIP root files, 64 KiB ZIP directory, 4096 outer tar entries, 64 KiB image manifests and 1 MiB config.
  Actual streamed/inflated bytes and EOF are checked, not just headers or gzip ISIZE. These are
  initial ceilings, **not measured Backend image sizes**; there is no automatic limit increase.
- Supported archive profile: one Docker-save image/tag with ordinary regular/directory outer
  entries, raw config and uncompressed layer tars; modern `blobs/sha256/...` paths and a coherent
  single-image OCI index are checked, including Moby's bounded inert legacy V1 config blobs.
  Compressed/foreign layers, extra subjects/tags, inconsistent indexes, outer links/PAX extensions
  and unsupported layouts fail closed. Layer filesystems are never extracted; links *inside* a
  layer are normal opaque content. The authorized tiny Docker rehearsal must establish the actual
  installed export shape; a synthetic fixture is not that proof.
- Prerequisites: Python **3.10+**, Bash, GNU `timeout`/file utilities and `flock`, the supported Docker
  Engine/Compose described below, and a Compose supporting application `--pull never`/`--no-build`.
  The Backend backup path additionally requires **Linux, Python3.11+**, PostgreSQL17 clients in
  the installed database image, and the reviewed backup helper/pin described under Operations.
  Install the helper root-owned mode `0644`, receiver `/usr/local/sbin/kira-deploy` and gateway
  `/usr/local/bin/kira-deploy-gateway` root-owned mode `0755`, with non-writable parent directories.
  `/opt/kira`, release directories and the fixed helper/config paths must be root owned, nonsymlink,
  and not group/other writable. Keep `images.env` mode `0600`, with exactly one unquoted
  `KIRA_<COMPONENT>_IMAGE=<reference>` line per target; no export/whitespace/duplicate variants.
  `ingress.env` must not shadow image keys. Neither file is shell code.
- Coordinate installation of the reviewed helper, receiver and gateway **before** the new Backend
  workflow is enabled. The legacy Backend three-token command is intentionally retired without
  fallback. Current Web/Admin client protocols remain unchanged:

  ```text
  deploy backend <40-lowercase-hex-source> <64-lowercase-hex-gzip-sha256> sha256:<64-lowercase-hex-image-id>
  deploy web <40-lowercase-hex-source>
  deploy admin <40-lowercase-hex-source>
  ```

  Exact single spaces/argument counts are required. No quoting, flags, extra tokens, newlines,
  arbitrary paths, `eval` or general command dispatch. Web/Admin derive their immutable ID from
  their own bounded single-tag stream; they do **not** acquire Backend's receipt/UID/label rules.
  In particular Web's unlabeled `USER node` image remains supported. Compatibility is not authority
  to reinstall/re-enable a retired Admin key/grant/workflow or bypass its own approval controls.
- Lock acquisition is bounded to 30 seconds; receive, full archive validation and Docker load each
  have 90-second bounds. Docker/Compose commands and health polling are finite. The Backend SSH
  operation has a five-minute total bound with pinned host keys, no agents/forwarding/passwords,
  and owned key cleanup; Web's existing five-minute transfer bound is unchanged. Those local bounds
  do not prove a canceled/killed client stopped a daemon-side or remote-root operation. Inspect a
  pending transaction and actual host state before retrying; never infer cleanup from job cancellation.

### Immutable host transaction and archive adoption

Under the existing shared lock, normal deployment captures the actual container **`.Image`**,
Compose project/service ownership, running/healthy state, and the separately resolved configured
reference *before load*. An unhealthy/unowned runtime, unresolved predecessor, duplicate target or
runtime/configuration mismatch stops the attempt. A missing container has **no previous healthy
runtime**; a configured but nonrunning tag is not automatically restored or reported as one.

After byte validation and quiet configuration preflight, the predecessor's configuration is pinned
to its captured ID before a tag can be overwritten. Backend media initialization and migration use
the verified candidate ID. Application activation/rollback use IDs with pulling/building disabled;
separate digest-pinned PostgreSQL provisioning is not disabled. Success checks Compose's result,
the actual container image and health, and the persisted ID and activation record.

The host stores exact gzip bytes at `releases/<component>/<gzip-sha256>.tar.gz`, never overwriting an
object. `releases/<component>/activation` contains `active <source> <image-id> <archive-sha256>` and
`previous <source> <image-id> <archive-sha256>` (or `previous - - -`). `images.env` and each activation
record are individually atomically replaced; they are **not a multi-file atomic transaction**.
The persistent `pending` marker makes an interrupted/incomplete transition an explicit STOP.
On failure, rollback must itself pass Compose, actual image/health and persistence checks; otherwise
the report says failed/indeterminate, never “restored when available”. A failed first deployment removes
only its owned candidate and reports `rollback=none`. Original deployment failure remains nonzero.

Deployment/activation outcomes are finalized **after EXIT cleanup**, including the final receiver
message. Backend transfer interprets only these fixed exit codes, after its own process/key cleanup;
it never forwards or parses arbitrary remote stdout/stderr:

| Exit | Meaning |
|---|---|
| `0` | Verified activation/no-op and owned cleanup succeeded. |
| `70` | Refused before this attempt's activation; existing host health is **not asserted**. |
| `71` | Deployment failed; healthy immutable predecessor and persistence were restored. |
| `72` | First deployment failed; no predecessor existed and owned candidate removal was verified. |
| `73` | Recovery incomplete/indeterminate; inspect actual runtime and pending state. |
| `74` | Activation/no-op was verified, but owned post-activation cleanup failed. |

Cleanup failure downgrades a provisional refusal/restoration/removal to `73`, not a healthy claim.
Unknown exits, SSH `255`, signals, timeouts and local transfer/cleanup failures mean **unknown remote
outcome**: inspect the host before retrying, without inferring rollback or daemon stoppage. These
codes do not relax any STOP/reconciliation guard or grant emergency activation. Web/Admin retain
their exact wire forms and nonzero-on-failure behavior; root-only backup/adoption retain their
ordinary success/failure exit convention.

Keep active and the previous **distinct successful image** archives. Repeating an identical healthy
image is a no-op and does not rotate away the real predecessor. GitHub retention does not expire
these host recovery archives. Only after a committed transition are unreferenced owned archive
objects pruned; there is no global Docker prune. Failed attempts clean only their scratch/new
uncommitted object. Cleanup or persistence failure is nonzero even if a container is healthy.

Before the first rollout of this receiver, a healthy legacy runtime needs an explicit bounded
archive adoption by an authorized root operator, for example:

```text
sudo /usr/local/sbin/kira-deploy adopt web <full-source-sha>
```

This checks the existing root-owned `releases/web-<source-sha>.tar.gz` against the **actual running
image**, adopts its content-addressed bytes and records/pins that ID without loading or restarting it.
The same command form supports Backend/Admin. If the legacy archive is absent, already overwritten
by another same-SHA image, or otherwise mismatched, **STOP**. There is no automatic export/backup of
the daemon's current image and no tag-based fallback; obtaining an authorized verified predecessor
archive is a separate operator recovery action. Do not simply delete a pending marker or invent a
healthy predecessor. Legacy-file retirement after verified adoption is an explicit operator action.

Root-only `activate <component> sha256:<id>` selects an existing verified active/previous archive and
uses the same normal transaction guards; it is not an unhealthy/unowned/drift/pending-state bypass.
Abnormal-state recovery requires separately authorized reconciliation of actual runtime and records.
All migrations remain **forward-only**; application rollback neither reverses schema/data changes
nor guarantees compatibility with a migrated database.

Focused offline tests (synthetic API/archives and disposable command stubs only):

```bash
python3 -B -m unittest discover -s scripts/ci -p 'test_image_release.py' -v
```

Installed hashes/permissions, Docker/Compose/Python behavior, genuine GitHub source/environment
protection/token access, SSH authority retirement, and production state remain **EXTERNAL
VERIFICATION REQUIRED**. These source tests do not deploy, authorize installation, establish public
Web post-deployment behavior, or substitute for the separately authorized tiny real-Docker gate.

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

Each component deployment/recorded-activation path runs a quiet Compose configuration
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

## HTTPS-only HSTS policy — EXTERNAL VERIFICATION REQUIRED

The versioned policy for the four TLS servers (apex, www redirect, API, and Admin) is exactly one
`Strict-Transport-Security: max-age=86400`, defined at server scope with `always`. This covers
redirects and ordinary local/upstream HTTP errors, not failed TLS handshakes with no HTTP response.
The three TLS proxies suppress upstream HSTS so the edge owns the policy. The standalone Web
bootstrap proxy (both apex and www) and API bootstrap proxy also suppress upstream HSTS; they
**still proxy HTTP**, unlike the redirecting HTTP servers in the steady-state TLS templates.
Standalone Admin HTTP remains redirect-only. Do not enable bootstrap and TLS templates together.
ACME's `/.well-known/acme-challenge/` path deliberately remains available over HTTP without HSTS.

### Reconcile the installed Nginx policy before activation

An image-stream deployment does not install these templates or reload Nginx. An authorized operator
must inspect the effective configuration, including Certbot/global includes, reconcile it without
changing unrelated hosts, validate it with the installed Nginx, and separately install/reload it.
For the **Nginx 1.28.3** validation baseline, account for all of these inheritance rules:

- Adding server-level `add_header` replaces the **entire inherited parent `add_header` set**, not
  just HSTS. Preserve any required other security headers when reconciling the installed policy.
- An `add_header` in a same-scope Certbot/global include can instead create a duplicate HSTS field.
  Any child location with its own `add_header` drops the inherited server header set, including
  HSTS; none of the committed locations currently defines one. Audit future locations too.
- `proxy_hide_header` suppresses an upstream response field, **not edge-generated** headers.
  A local custom hide list replaces an inherited custom hide list; preserve other required hiding.
  An inherited `proxy_pass_header Strict-Transport-Security` can undo the intended suppression.
- A global edge HSTS rule can leak HSTS onto HTTP despite upstream suppression. Reconcile all
  four HTTP hosts as well as HTTPS; do not assume an unknown global policy is safe or overwrite it.

Do not use `add_header_inherit`: it was introduced in Nginx 1.29.3 and is unavailable in 1.28.3.
Source/config checks do not prove the effective installed headers or preserve unknown global rules.

### Post-install operator checks

After the authorized configuration change, run from the Backend repository with Python 3.10+:

```bash
python3 -B deploy/server3/verify_hsts.py --check
```

Without `--check`, the command only prints help and makes no requests. The explicit check makes
eight credential-free GETs: HTTPS `/` on each host, plus HTTP
`/__kira_hsts_probe__/part%2Fone?first=1&second=a%2Bb` on each. It uses the normal CA trust store and
hostname verification, never follows redirects (www must supply its own HTTPS policy), and never
reads response bodies. Each request has a **10-second total deadline**, including DNS, connection,
TLS, header receipt, and worker cleanup; a small part is reserved for kill/reap rather than allowing
socket-idle timeouts to reset. `--timeout SECONDS` accepts 1–60 seconds per request. There are no
retries. Exit 0 means these eight header checks passed; exit 1 means at least one failed.

The checker counts raw case-insensitive field occurrences and requires exactly one HTTPS HSTS value
`max-age=86400` after field OWS trimming. Missing, duplicate (even identical), comma-combined, and
extra/wrong policies fail. HTTPS 4xx/5xx are inspected normally, **not** treated as a health failure.
Every HTTP response must have no HSTS, status **301**, and exactly the expected HTTPS Location with
the original encoded path/query: www goes to apex; apex/API/Admin retain their respective hosts.
This checks steady-state TLS deployment redirects, **not** the standalone Web/API bootstrap proxy
behavior or the ACME exception. It is not proof that every route/error response has the policy.
Separately verify installed normal/local-error/upstream-error headers, bootstrap/ACME behavior,
public certificates on all four names (including www), and the effective global/include policy.

Keep the public Web repository's existing verifier as a complementary gate, run from that repository:

```bash
node scripts/verify-deployment.mjs https://kiramanga.me
```

That unchanged command checks TLS, pages, content types, and association identifiers; it does not
replace the Backend's four-host exact-HSTS checks. Neither command installs configuration, verifies
network isolation, or authorizes deployment. Focused Backend helper tests use only offline/owned
loopback fixtures and do not contact production:

```bash
python3 -B -m unittest discover -s deploy/server3 -p 'test_verify_hsts.py' -v
```

### Staged rollout and rollback limits

One day is a conservative **initial** policy, not a final security ceiling. Any later increase needs
separate observation and operator approval. This rollout has no `includeSubDomains` or preload:
the apex does not cover www or sibling hosts, and a fresh client's first HTTP hop is not protected
without an already learned/preloaded policy. HSTS expiry is relative to receipt and is refreshed by
valid policy responses; merely removing the directive does not clear a browser's cached policy.

Rollback requires delivering `max-age=0` over **valid HTTPS for each affected host** and separately
checking that changed policy (the fixed-policy smoke above deliberately rejects it). This does not
erase a parent or preloaded policy. Account for any previously issued longer policy and clients
that have not received the rollback. Maintain valid certificates, including www's redirect
certificate, throughout rollout, rollback, and the remaining cache lifetime. Installed acceptance
and rollout remain **EXTERNAL VERIFICATION REQUIRED**, not implied by a source or isolated test pass.

## Operations and recovery

Run deployments only through the workflow or restricted stream command. Backend backup publication
requires the **jointly reviewed receiver and shared Backend29 helper**, not this receiver alone.
No gateway/sudoers permission is broadened: `backup` remains a root-only command.

### Install the reviewed backup helper and pin together

Install the exact reviewed `scripts/db/backup_bundle.py` as
`/usr/local/libexec/kira-backup-bundle.py`, root-owned mode0644, together with
`/usr/local/libexec/kira-backup-bundle.sha256`, root-owned mode0644 (0600 also works).
The pin contains **exactly the review-approved lowercase64-hex SHA256 plus newline**.
Obtain that expected digest from the independently accepted source/artifact record; generating a
new hash from arbitrary installed bytes is not provenance. Neither file may be a symlink or writable
by group/others. Every ancestor must also be root-owned, nonsymlink and not writable by non-root.
The receiver checks this custody and installed bytes before each helper invocation with
`python3 -I -B`. Installation/replacement must exclude active receivers; hostile concurrent root is
outside this custody boundary. Do not silently omit the pin, downgrade Python or substitute another
verifier. Web/Admin's image-only helper/protocol remains unchanged.

`/opt/kira/backups` must be an owned private0700 nonsymlink directory beneath trusted ancestors.
Create it if absent; stop on an unexpected existing path instead of repairing owner files. The
receiver creates unique `.stage.<random>` directories, never timestamp-only final filenames, and
publishes whole generations using the shared Linux `renameat2(RENAME_NOREPLACE)` helper plus fsync.
Missing safe-publication support, cross-device placement and failed fsync are errors, not reasons
to fall back to overwrite. Installed filesystem/ACL/durability behavior remains externally verified.

### Fresh external writer freeze **and drain**, before either backup path

Exclude **every** relevant writer, including tutorial ADMIN mutations, and positively drain their
in-flight database/filesystem work before requesting a backup or mutating Backend deployment.
Docker pause is not a drain: for example, pausing after media deletion but before database commit
can produce a correctly hashed but inconsistent pair. No media-service draining mechanism or
production quiescence is established by this receiver. The owner must supply and retain that
external exclusion through the backup, or through explicit reconciliation after a failure.

For each invocation, an authorized root operator/system then creates
`/opt/kira/backups/.writers-frozen-and-drained`, a regular nonsymlink root-owned0600 file containing
exactly this one line, including its newline:

```text
KIRA_BACKUP_WRITERS_FROZEN_AND_DRAINED=yes
```

Create the marker exclusively under the deployment lock; do not overwrite an existing marker or
pending obligation. It is an attestation, **not evidence of quiescence**. The restricted SSH
environment cannot authorize it. After validating the marker and actual Backend identity/state,
the receiver consumes it before the first mutating Backend Docker command, including image load,
media initialization and PostgreSQL startup. An identical-image no-op precedes this backup path;
it does not consume a marker. Do not leave/reuse a stale marker after a refusal/no-op or release
writer exclusion while an unused marker remains: re-establish the external precondition for the
next invocation. There is no automatic approval, freshness inference or freeze/drain bypass.

With that prerequisite, a manual backup is:

```text
sudo /usr/local/sbin/kira-deploy backup
```

The independent `backup` command neither loads `ingress.env`/`images.env` nor invokes Compose,
preflight or release recovery; ingress/configuration repair cannot prevent an otherwise authorized
data backup. It does require the installed pinned helper and positively identified running
PostgreSQL/media volume. Positively absent or owned stopped Backend needs no pause; an initially
paused, foreign, replaced or uninspectable Backend is a STOP, never an instruction to unpause it.

### Completed generations and failure custody

A successful generation is `/opt/kira/backups/kira-<random>/`, containing the same-stem custom
`.dump`, `.media.tar.gz` and versioned `.bundle.json`, plus private diagnostic TOC, operation/CID and
digest records. The v1 manifest binds the two relative basenames, byte lengths and SHA256 values.
The receiver requires an actual custom-dump TOC listing, a readable safe-profile media tar, and
the pinned shared final selected-pair verifier before publication. A valid archive of empty media
is supported for a new installation; zero-byte/corrupt archives are not. Files remain0600 under
0700 directories; the web cache volume is disposable. Existing flat backups/generations are not
overwritten, pruned, resealed or silently upgraded. Explicit NEW-backend legacy verification and
relocated restore are described in [DISASTER_RECOVERY.md](../../docs/DISASTER_RECOVERY.md).

Record the exact manifest digest and successful generation in the trusted encrypted off-host
recovery catalog. A self-generated `bundle-pin` file is a checksum, not an external trust anchor,
successful restore or retention service. Never select a backup by a newest-filename glob.

Before the first Docker side effect, the receiver exclusively publishes and fsyncs
`/opt/kira/backups/.pending`. This obligation binds one invocation, exact initial Backend ID/state,
stage and target. Its short append-only phase records identify started versus positively completed
operations; named helper containers also have invocation labels/CID files, and database execs use
the exact PostgreSQL ID with invocation-specific `PGAPPNAME`. The recorded receiver PID is
diagnostic, **not authority to signal a subsequently reused PID**. All conflicting Backup and
Backend deploy/activate/adopt calls refuse this obligation across later lock acquisitions.
Web/Admin do not mutate or discard it.

Normal completion and EXIT/INT/TERM/HUP share the existing cleanup protocol. Only a positively
completed owned pause permits one exact-ID unpause; no replacement, initial pause or unconfirmed
pause is compensated. The resumed state is checked; cached `healthy` while `Paused=true` cannot
qualify activation/restoration. A failed/unconfirmed unpause is never retried automatically. Other
unfinished operations remain unresolved even if that owned Backend was successfully resumed.
Repeated catchable signals cannot interrupt cleanup and turn uncertainty into a healthy claim.

**Any retained `.pending` is operator STOP.** The receiver keeps its potentially writable stage
and does not migrate or report restored71. A helper can rename a directory and then time out/fail
before its fsync/return: the named **target may already exist** while the stage is absent. Both
the named stage and target remain unselectable/unadvertised until positive reconciliation; do not
claim either is absent or delete it while a daemon/local helper may still act. Previously completed
generations remain untouched. If clearing `.pending` fails or its directory fsync fails, the receiver
preserves/restores the same owned obligation inode best-effort and reports indeterminate failure;
a failing storage system may prevent even that persistence. Stop conflicting work externally too.

There is **no automatic reconcile/retry subcommand**. Under separately authorized root custody and
the same deployment lock, inspect the exact obligation, containers/execs/helpers, stage **and**
target; positively establish completed operations and exclude late effects, confirm the intended
Backend state and reconcile any partially published bytes. Only then archive/remove the exact
owned obligation and any proved-safe scratch, with a fresh freeze/drain attestation for a new
attempt. Merely deleting `.pending`, waiting an arbitrary interval, observing one unpaused state,
or killing a Docker CLI proves none of this. Retain the separate image-transaction pending record
as required by the exact-image runbook. Backup uncertainty cannot earn an application rollback71;
root-only backup remains ordinary success/nonzero failure, and signal outcomes remain unknown.

Catchable-signal handling is not SIGKILL, power-loss or daemon-loss recovery. Installed writer
exclusion/draining, exact helper/pin custody, daemon late-effect reconciliation, available space,
backup duration and storage/fsync/crash behavior, encrypted off-host/PITR/retention, actual matched
restore/migration/application drills and achieved RPO/RTO remain **EXTERNAL VERIFICATION REQUIRED**.
This concerns NEW backend-data and installation recovery only; W06/old Firestore recovery is excluded.

Automatic image rollback retains the installed Compose/Nginx isolation contract; do not restore an
old shared Admin/Web network or remove header sanitization while trusted-ingress mode is enabled.

Deploy sequentially through the restricted SSH gateway on port 22: backend image/migration/seed
first, then verify public categories, all four seeded tutorials, media, ETags, and bilingual parity.
Only after that gate succeeds deploy web. A newly published guide must appear within 60 seconds
without a web rebuild. Normal image deployments leave installed Nginx/Compose files unchanged;
record and verify their separately reviewed hashes during the trusted-ingress configuration change.

To move Kira, provision Docker and Nginx on the destination, copy the versioned files, securely transfer the `/opt/kira` secrets and a verified matched database/media bundle, restore into new `kira-postgres-data` and `kira-tutorial-media` volumes, reissue public certificates, test with local DNS overrides, and only then change DNS. Do not copy Let's Encrypt private keys when certificates can be reissued. Keep mail services and mail DNS outside this deployment.
