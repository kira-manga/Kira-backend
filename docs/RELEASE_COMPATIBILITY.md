# Backend release state compatibility

This is a **fail-closed containment floor**, not an assertion that an arbitrary old image can read
or write the current database. Migrations remain forward-only. A healthy process, retained digest,
matching schema number, image label, or current count of withheld sources is not a compatibility test.

## Fixed profile

The reviewed profile is `kira-backend-state-v1`, declared by the immutable image configuration label
`io.kira.backend.state-contract`. It covers the current migration inventory through **V13.2**:

- `WITHHELD` is a server-only lifecycle understood by readers **and writers**. It stays admin-visible
  and absent from public artifacts. Publishing a reviewed generic revision does not implicitly
  enable it; admission requires the separate, engine-gated enable operation.
- v1 and signed v2 publication share a monotonic generation. v2 commits to immutable source tuples,
  detached signatures and ordering; a tuple must have public membership before it can be fetched.
  Removal retains historical artifacts and produces identity-only v2 tombstones.
- V13.2 admission is durable `PENDING` / `COMPLETE` / `RECONCILIATION_REQUIRED`, not a health check or
  inferred roster. Ordinary publication/import require COMPLETE. The payload-bound origin receipt
  survives later legitimate evolution; identical original bytes replay without rewinding it.
- V13.1 password reset advances credential generation atomically. Old tokens must remain rejected
  after restart. An older bearer verifier or reset writer is not compatible merely because added
  database columns have defaults.

`scripts/ci/image_release.py` fixes **all 15 migration paths and their SHA-256 bytes**. Its local
producer check and remote Git source readback reject additions, removals, nested entries or byte
changes. This is an explicit future-drift gate, not “schema version ≥ 13.2”. A future migration needs
a separately reviewed compatibility decision/profile, producer evidence, receiver policy and rollout;
editing the inventory or copying the label is not that evidence. No V14 migration is introduced here.

## Normal server3 receiver boundary

The receiver admits a **different-image Backend transaction only when the incoming archive and,
when present, the actual healthy running predecessor's retained archive declare the exact fixed profile**. It
checks the bounded archives and their expected source SHA, archive digest and image ID, not a tag
alone. Missing/unknown profiles refuse before Docker mutation or consumption of the writer-drain
attestation. The loaded candidate's immutable ID and profile are checked again before migration.

This applies to both streamed deployment and root `activate` of a retained release. A previous
activation record is identity/custody evidence, not an exemption. Legacy `adopt` remains identity-only,
and a true same-actual-image no-op does not run migration or manufacture upgrade/rollback eligibility.
An unmarked existing runtime can therefore be adopted or left running, but cannot be the predecessor
of a normal different-image transition. That first forward baseline needs separate owner authority.

On a failed admitted transaction, the receiver rechecks the actual predecessor's retained archive
before starting it. If that compatibility evidence is missing or invalid, it **does not start the old
image, delete pending state or claim restoration**. Outcome 73 retains pending custody and requires
separately authorized forward recovery. An unresolved backup obligation already takes precedence over
rollback. Existing outcomes 71 (verified healthy predecessor restored) and 72 (no predecessor; owned
candidate removed) keep their narrower meanings; neither undoes a migration. Web/Admin behavior is
unchanged.

The archive helper keeps its existing identity-only **two-token `archive` stdout ABI**. The separate
`backend-state SHA ARCHIVE_PATH ARCHIVE_SHA IMAGE_ID` check has no success stdout and accepts no
caller-supplied profile override. Backup helpers, markers, attestation custody and installation
permissions are not changed by this profile.

## Producer evidence, not just a declaration

Promotion requires the `backend-production-state-v1` smoke receipt policy. The old
`backend-production-profile-v1` receipt proves only the former health/metrics smoke and is ineligible.
The finite source contract includes the smoke driver, semantic probe, existing signing-key helper, both bootstrap
fixtures, Dockerfile, image helper, CI workflow and all migration bytes. Source identity and the
contract are frozen before the exact-ID smoke and checked again before exporting that same ID.
Existing approval/freshness/source-policy checks still apply; a label does not replace them.

The smoke uses one newly created disposable TLS PostgreSQL database and the existing production
validators. It generates private fixture credentials and uses the existing AdminSeeder **only for
initial fixture creation**. It never enables registration. Application HTTP is published on one
ephemeral `127.0.0.1` port; the probe accepts no external URL and forwards no credential redirects.

The semantic probe exercises:

1. PENDING ordinary-import/republish/retired-cutover denial, then raw atomic bootstrap using the
   original 45 historical models and revision-6 reference metadata, without model substitution.
2. The exact nine-field receipt, admin visibility of all 45 heads, 33 withheld legacy exclusions,
   ordinary no-op import, and signed v1/v2 byte/checksum/ETag/304 coherence.
3. A withheld source's generic draft, publish-without-enable, explicit enable, and membership-gated
   immutable artifact reads. It also disables, retires and removes an original generic source,
   checks tombstones and retained prior artifacts, and rejects guessed/nonmember tuples.
4. Identical original-byte receipt replay after evolution, different-byte refusal, unchanged visible
   heads/document history/latest artifacts, and retained immutable origin document/source bytes.
5. Password reset/token-generation revocation; then graceful stop and restart of **the same exact
   immutable image ID with seeding disabled**, retaining only the disposable database/media.
6. Persisted state/receipt/signature/history readback and revoked-token rejection after restart,
   followed by a normal forward publication and another origin replay. Both reader and writer
   behavior matter; a green readiness probe alone cannot satisfy this policy.

Python standard-library HTTP/JSON and the existing OpenSSL tooling are used; there is no test API,
JVM fixture launcher, direct SQL mutation, new production credential path or new crypto dependency.
The request/checkpoint/token files are owner-private under the disposable smoke directory and are
not release artifacts. Once authenticated probing begins, the failure path does not collect container
logs or private checkpoints. Only bounded fixed diagnostics are emitted. The probe's visible-state
comparisons are not a full database/audit diff or a live-data recovery test.

## Still external / not certified by this change

- **An initial supported forward baseline from an existing unmarked runtime.** No emergency bypass,
  profile override, automatic restore, marker deletion or implicit reconciliation is provided.
- **A genuine predecessor-image recovery test on a representative migrated-data clone.** Same-image
  seed-disabled restart is useful producer evidence, not historical-binary compatibility evidence.
  Root admission recognizes the declaration; it does not execute a compatibility suite on server3
  or convert legacy adoption into a semantic attestation. Baseline and retained predecessor custody
  must be established independently; labels alone are insufficient for an operational rollback claim.
- **Sustained old-writer exclusion.** Backup temporarily pauses/resumes the predecessor before
  migration; that is not proof that incompatible writers remain fenced throughout rollout/recovery.
  The owner must account for every writer, account/token cutover and deployment surface.
- **Real backup restore, remote custody and forward reconciliation.** Normal receiver entry refuses
  unresolved pending/unhealthy/drift states; “retry”, “activate old”, or deleting markers is not a
  recovery procedure. Preserve exact evidence and obtain separate authorization.
- **Other deployment surfaces.** Kubernetes/manual/root operations outside this receiver do not gain
  admission enforcement from a Docker label. They require the same compatibility review and their
  own stopped/drained rollout controls.

The offline receiver fixture uses synthetic, nonbootable images and fake Docker/PostgreSQL. It can
check admission ordering, refusal, custody and fallback control flow, **not binary compatibility**.
Record actual test commands, immutable image identities and results separately. Source authoring,
labels and synthetic test passes must not be reported as verified deployment/recovery success.
