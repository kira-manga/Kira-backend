# Release process

**Production authorization is permanent native `production` environment approval**, not a main
merge, tag push, successful CI run, workflow input, receipt or policy fingerprint. The workflow
source is a fail-closed implementation, not evidence that GitHub protections/keys are installed.
Complete the external prerequisites below before separately authorizing any publication.

## Trusted entry and source/version contract

`Publish release` (`.github/workflows/publish-release.yml`) accepts **only a new manual dispatch on
`main`**. A tag push does not run this distinct workflow or receive its write permissions; that says
nothing about reachable historical writers. Select all four inputs explicitly: `tag`, full
`source_sha`, `ci_run_id` and `ci_run_attempt`. None is an approval switch. Consumer reruns are
refused; a new dispatch must repeat validation and native approval.

- Tags are exactly `vMAJOR.MINOR.PATCH`, with no leading-zero integers, suffix, whitespace or ref/path
  fragments. The single supported literal `version = "MAJOR.MINOR.PATCH"` in `build.gradle.kts` and
  the top released `CHANGELOG.md` heading must agree. Duplicate/dynamic declarations or ambiguous
  headings fail closed. `v1.0.0` is a tag spelling; the numeric project/image/JAR version is `1.0.0`.
  Implementing this workflow does not require bumping the version or creating/replacing that tag.
- Require a **directly annotated, SSH-ed25519-signed commit tag**, verified against explicit public
  signer trust freshly read from GitHub. Lightweight, nested, unsigned, invalid and unknown-signer
  tags fail. PGP/certificate/other key formats are not supported by this narrow policy. There is no
  unsigned or GitHub “verified” fallback, and an artifact attestation cannot replace tag verification.
- The immutable tag object and exact target commit are pinned separately. The target must be an
  actual ancestor of authenticated current protected `main`, with its full tree checked. A rebase
  is eligible only after its **actual new SHA** lands on protected main and receives its own CI.
  Same-tree siblings and unrelated/off-main commits are not an ancestry exception.
- Bind the active CI workflow, exact same-repository push/main run, its current explicit attempt,
  and successful `verify`, `supply-chain`, `container`, **`observability-rules`** jobs. Completion
  evidence must be under 72 hours old. No old attempt, fork/PR run, skipped gate or count-only/partial
  listing is accepted. A newer producer attempt after selection invalidates the candidate.
- The trusted main workflow SHA/tree and all authorization/CI controls are frozen; both consumers
  and the tagged source must use those control bytes. Main advancement, tag/source/control/policy
  drift or signer rotation during approval requires a fresh selection, not a relaxed recheck.

## Build, freeze, approve, publish

1. **Read-only preflight** checks source, signature, version, CI, current installed policy, the exact
   legacy-disabled snapshot below and absent release/version targets. It executes trusted-main
   controls, never tag-selected verification code.
2. **Read-only builder** checks out the validated full SHA without persisted checkout credentials.
   It has no production environment, SSH key, package/content write or OIDC/attestation authority.
   Only the scoped dependency-read token is used for Gradle/Buildx. CI has already run verification;
   this job constructs the JAR/SBOM and one candidate image, then smokes the actual immutable image ID.
   An owned generated Gradle init script checks the evaluated project version and records the numeric
   version and source SHA in the JAR manifest. The Docker labels are checked independently. These
   are separately built outputs from the same source, not a claim that the attached JAR is byte-equal
   to the JAR built inside Docker. Wrapper/base/action pins and dependency locks remain in force.
3. **Separate read-only freeze job** checks the successful build and one immutable Actions artifact
   `backend-release-<run_id>-1`, without executing it. It freezes artifact ID, actual ZIP digest/length,
   source/tree/tag object, CI attempt, trusted workflow run/attempt, policy/trust fingerprint, image
   identity and every file's bytes/hash. The artifact run's `head_sha` is the **main workflow SHA**;
   the manifest separately identifies the tagged application source. It never selects “latest”.
4. **Native protected publisher** alone requests job-scoped contents/packages/id-token/attestations
   writes. Required human reviewers authorize this job in `production`. It downloads the frozen ID,
   checks bytes, and loads the image without rebuilding, running the application or executing
   candidate scripts. It freshly reads native policy, legacy-disabled state and signer trust after
   approval and before mutation. Missing/unreadable/changed policy or active legacy state fails;
   no broader-token retry repairs it.
5. Publish only `ghcr.io/kira-manga/kira-backend:MAJOR.MINOR.PATCH` and `:sha-<full-source-sha>`.
   Refuse either existing target and any existing release (including visible drafts); no `latest`,
   major/minor alias, overwrite, asset replacement or automatic repair. After push, verify both
   actual registry manifests name the sealed image config ID. Provenance attests that **registry
   manifest digest**, not the different local Docker image ID. It identifies the trusted workflow;
   the retained manifest binds the separately selected source/CI/image bytes. Only owned ephemeral
   Docker credentials are retained for the pinned attestation action and removed with publisher
   scratch. Recheck policy/source again before create-only GitHub release publication and verify
   uploaded asset sizes/SHA-256. A bounded freshness stamp gates each image push; it is not approval.

The release artifact has exactly five regular root files: `image.tar.gz`, the versioned JAR,
`bom.json`, `bom.xml`, `manifest.json`. It does **not** change server3's two-file `image.tar.gz` /
`receipt.json` protocol or promote a differently rebuilt image through that path. Bounds are
512 MiB total ZIP/gzip, 2 GiB expanded Docker tar, 128 MiB JAR, 16 MiB per SBOM and 32 KiB manifest;
three-day retention and current API expiry are enforced. Unsupported archive shapes/limits stop,
never auto-expand. The publisher's write token is **job-scoped and already minted after admission**;
step-scoped variables do not sandbox processes or prove no token existed before a failed recheck.

Publication across GHCR, provenance and GitHub releases is not atomic. A failure may leave a partial
publication. Stop and inspect the actual registries/release under a separate owner recovery decision;
do not rerun, delete, move tags or overwrite assets as an implicit cleanup. Non-cancelling workflow
concurrency serializes this entry only. GHCR absence checks are not atomic compare-and-create against
other token/app writers; those authorities must be retired/restricted and audited externally.

## Legacy workflow retirement and rollout block

**Publication/tag-creation admission, deployment rollout and Backend25 closure remain blocked until
the owner verifies durable retirement and capability controls.** The old tag-push writer is workflow
ID **`315951352`**, path **`.github/workflows/release.yml`**, in `kira-manga/Kira-backend` (repository
ID `1304735394`). The primary's read-only observation at **2026-09-15 03:15 UTC** reported **`active`**;
this change performs no settings operation and does not establish that it has been retired.

Deleting the current `release.yml` and moving the manual publisher to
`.github/workflows/publish-release.yml` does not alter historical workflow bytes or retire their
server-side identity. A **new ancestor-tag invocation** can select an old write-authorized definition;
it is neither a tag update nor a rerun.
Historical reruns and pending/running legacy work are separate paths. Read-only default workflow
permissions do not cap explicit historical writes, and a creation allowlist constrains who, not
the target commit. A rename/deletion or green tests are **not retirement**.

Every trusted-context snapshot, including deployment checks and release selection/freeze/rechecks,
makes a fresh **`GET /repos/kira-manga/Kira-backend/actions/workflows/315951352`** with the ordinary
**Actions-read** credential. It requires exact numeric ID `315951352`, exact path
`.github/workflows/release.yml` and exact state **`disabled_manually`**, then freezes those fields in
the policy snapshot. Missing/unreadable/ambiguous metadata, ID/path mismatch, `active` or any other
disabled state denies continuation. No filename-only lookup, list discovery, state inference,
cached retirement receipt, retry or broader policy-token capability replaces that GET. The active
manual publisher must have a different workflow ID and the exact new path. This read does not disable
anything, cancel old work, prove historical denial, or make disablement durable.

Before allowing tag creation/publication or rollout, the owner must separately verify and record:

- Actual old/new workflow IDs and installed controls that prevent **new ancestor-tag invocations**
  and **historical reruns**, not only today's manual entry. Cancel pending/running legacy work and
  approvals and reconcile any already issued writer authority or partial mutations.
- Durable custody over legacy disablement: constrained creator Team/Integration IDs and their real
  credentials/capabilities cannot **re-enable** that writer, bypass retirement or introduce
  **alternate writers**. Review administrative/Actions rights, bypass grants and other GHCR/release/
  tag-writing identities; the source's creation allowlist alone does not prove this.
- Owner-controlled enforcement/independence and real platform/API capability for those boundaries.
  Unknown identities, unsupported controls or unverified capabilities keep rollout blocked. A fresh
  `disabled_manually` response is necessary for this entry, **not sufficient** for these owner gates.

Never exercise a historical tag or unprotected writer to test retirement, grant it publication
authority, or treat a successful source/unit-test run as installed enforcement. Re-enablement or
revocation between reads remains an external control problem, not an atomic guarantee from snapshots.

## External native policy and public signer provisioning

The owner must choose/provision and verify all of the following. Empty/missing/unreadable values deny
publication; fixture success does not provision any of them:

- **Main:** supported classic protection enforcing admins, no force push/deletion, reviewed PRs,
  stale-approval dismissal, independent latest-push approval, no review bypass, and the exact four
  strict CI contexts above bound to GitHub Actions app ID `15368`. Ruleset-only protection is not
  silently treated as equivalent; review/extend the checker rather than weakening an existing model.
  Independently review the bootstrap main tip/history; installing rules today is not historical proof.
- **Production:** permanent required reviewers, explicit owner-controlled User IDs, prevent self
  review, no admin bypass, custom branch policy containing **only** `{type: branch, name: main}`.
  GitHub requires one listed reviewer, not unanimity. The optional `branch_policy` marker is not
  approval; timers, missing reviewers, extra/tag refs and unknown native rules are refused.
- **Tag rules:** exactly two active repository tag rulesets covering `refs/tags/v*.*.*` with no
  exclusions: creation-only with explicit Team/Integration creator IDs; separate update+deletion
  restrictions with **no bypass actors**. A creator exception must not bypass immutability. Other
  tag/inherited policy models need explicit review, not disabling stronger existing controls.
- **Public trust:** repository variable `BACKEND_RELEASE_SIGNERS`, bounded JSON array of one to six
  objects with exactly `principal` and `key`. Each principal is an explicit, non-wildcard owner-approved
  identity; each key is `ssh-ed25519 <public-key-base64>`. No actual principal/key is supplied by this
  change. Do not install fixture keys. Git tag verification uses the raw annotated object, fixed
  `/usr/bin/ssh-keygen`, namespace `git`, an owned allowlist and sanitized child environment, not
  candidate Git config/programs. The variable is read anew from the fixed Variables API on each
  check and its normalized trust fingerprint is frozen. Cached `${{ vars.* }}` values are not used.
  Keep tag-signing private keys off runners and separate from runtime source-document signing keys.
- **Read capability:** repository secret `BACKEND_POLICY_READ_TOKEN`, single-repository, expiring,
  fine-grained read-only Administration/Variables/metadata access. Administration:read is not a
  `GITHUB_TOKEN` workflow permission. The special token goes only to protection/ruleset/public-trust
  GETs in policy steps, never builds, child verification programs, publication commands or storage
  redirects. Ordinary read tokens cover source/actions/environment/registry reads, including only the
  exact legacy-workflow numeric GET above. It adds no Administration or policy-token permission.
  Verify the real endpoint capability under the workflow identity; no broad classic PAT, write
  permission or fallback.
- **Legacy authority/credentials:** follow [server3 prerequisites](../deploy/server3/README.md#github-production-environment).
  Satisfy the durable workflow-retirement block above, including new ancestor-tag invocations,
  historical reruns and creator re-enable/alternate-writer capabilities. Audit other GHCR/release/tag
  writers and repository/organization secret fallbacks. Default workflow permissions must be
  read-only as defense in depth, not a cap on old explicit writes; dependency credentials remain
  read-only and deployment keys environment-only.

Policy reads are bounded snapshots, not continuous history or an atomic policy/approval/revocation/
mutation transaction. Native enforcement, reviewer custody, administration rights and credential
retirement remain external authorization requirements. No settings mutation is performed by the helper.

## Operator sequence and validation limits

Only after the owner has verified durable legacy retirement/capabilities and separately authorized
the operation, prepare reviewed matching version/changelog source, obtain the
exact successful CI run/attempt, sign the immutable annotated tag with an **already approved** SSH
identity, and push that tag through the installed creation rules. Never move/recreate an existing tag.
Then manually dispatch `Publish release` at **`.github/workflows/publish-release.yml` on main** with
those four exact selectors, inspect the read-only
freeze job's candidate summary, and request the independent native production approval. A main merge
or tag push alone does not grant publication permission. No tag or live dispatch is a test of this fix.

The ordinary affected offline fixture batch is:

```bash
python3 -B -m unittest discover -s scripts/ci -p 'test_*release*.py' -v
```

It uses synthetic API/archive/command fixtures, not real SSH crypto, GitHub approval or publication.
Separately admit a no-publication rehearsal with synthetic public signer/API/artifact data, sentinel
dependency credentials and recording registry/release/SSH stubs; assert no real production credential
and zero external mutation on failed preflight/build/frozen recheck. Installed Git/OpenSSH/Gradle/JAR/
Docker export compatibility and genuine approved/rejected native paths need separate evidence.
This document and source fixtures do not claim those checks have run.

Authorization does not prove database/state compatibility, backup freshness, storage readiness or
safe rollback. Backend [#26](https://github.com/kira-manga/Kira-backend/issues/26) owns the separate
state-contract/rollback-floor gate; preserve it and the [deployment/recovery gates](DEPLOYMENT.md).
