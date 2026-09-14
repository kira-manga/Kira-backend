# Backend-managed tutorials

Tutorials use immutable category/tutorial revisions with mutable publication pointers. Identities
have `DRAFT`, `PUBLISHED`, or `ARCHIVED` lifecycle state. Publishing moves the pointer atomically;
rollback copies historical content into a new revision and publishes that copy. Archive hides an
identity publicly without deleting history, and restore returns it to its published/draft state.

Category archive is a visibility gate, not a cascade: it preserves child lifecycle, publication
pointers, revisions, order and featured positions. Internally PUBLISHED tutorials may remain under
an ARCHIVED category with a retained published revision; that durable state is valid on restart.
Restoring the category reveals only children that are still PUBLISHED. Independently archived or
draft children stay hidden. Publishing or rolling back a tutorial requires a currently PUBLISHED
category with a publication pointer; the category row is locked until that transaction commits,
so an archive that wins first makes publication/rollback fail without changing the tutorial.

## Public API

- `GET /api/v1/tutorial-categories`
- `GET /api/v1/tutorials?category={slug}&featured={true|false}`
- `GET /api/v1/tutorials/{slug}`
- `GET /api/v1/tutorial-media/{uuid}`

JSON endpoints return bilingual `{en, ar}` values, ordered records, resolved immutable media URLs,
strong ETags, and `Cache-Control: public, max-age=60, stale-if-error=86400`. Published media uses a
one-year immutable cache. Archived/draft content is absent publicly.

Authorized ADMIN delivery of unpublished media uses `Cache-Control: private, no-store` on
both 200 and 304 responses. Authorization precedes conditional handling: anonymous and non-ADMIN
requests still receive 404 even with a matching ETag. The Admin media proxy forwards the backend's
chosen cache policy to the browser.

Category gating applies to origin JSON responses, not immediate revocation of already cached
responses. Previously published media remains permanently public, including while its category
or tutorial is archived.

The unpublished policy forbids retention by compliant private and shared caches for new responses.
Changing the origin header cannot automatically recall draft bytes previously served with public,
immutable caching or erase downloaded copies. Assessment of historical exposure and any purge of
installed browser/proxy/CDN cache entries are external operational actions, not performed by this
change.

## ADMIN workflow

Use Swagger or an API client with an ADMIN bearer token. Category endpoints are under
`/api/v1/admin/tutorial-categories`; tutorial endpoints are under `/api/v1/admin/tutorials`.

Identity slugs are immutable lowercase kebab-case: letters `a-z`, digits `0-9`, and single hyphen
separators. Category slugs allow **at most 64 characters**; tutorial slugs allow **at most 96**,
matching their storage and OpenAPI bounds. Slugs are not trimmed, normalized, or truncated.
With otherwise-valid request fields, an oversized slug is rejected before persistence/audit with
HTTP **400** and `errors[0]` containing `path: "slug"`, `code: "TOO_LONG"`, and
`message: "must be at most <bound> characters"`; `detail` is
`"slug must be at most <bound> characters."`. The length guard precedes the grammar rule.
Nonblank, within-limit grammar violations retain HTTP **422** / `INVALID_SLUG`; other tutorial
content-validation failures also retain 422. Existing request/bean-validation precedence is unchanged.
Genuine identity/order conflicts still return HTTP **409**, not the oversized-input 400.

Both create requests accept optional non-negative `position` and `featuredPosition`. Tutorials
use `featuredPosition`; categories retain and validate that field for compatibility but ignore it.

1. `POST` an identity with `{"slug":"my-guide"}`.
2. Upload JPEG/PNG assets with multipart field `file` to `/api/v1/admin/tutorial-media`.
3. `POST /{id}/revisions` with bilingual structured fields and media UUIDs.
4. Publish with `POST /{id}/revisions/{number}/publish`.
5. Use `/archive`, `/restore`, or `/revisions/{number}/rollback` for lifecycle operations.
6. Send every identity exactly once to `/reorder`; normal and featured positions are contiguous from
   zero. Category reorder does not accept featured positions.

Media is re-decoded and re-encoded to remove metadata. Only signature-valid JPEG/PNG is accepted,
up to 4 MiB, 4096×4096, and 16 megapixels. A slot requires a default asset and bilingual alt text;
`enLight`, `enDark`, `arLight`, and `arDark` are optional. An asset referenced by any retained
revision cannot be deleted. Draft-only media is visible only to ADMIN; publication makes its URL
permanently public.

## Seed and storage

When enabled, the bundled seed imports six screenshot assets **before checking whether both tutorial
identity tables are empty**. The identity guard still controls creation of the four pre-migration
guides, categories, order and featured positions; it does not prevent those earlier media imports.
Existing seed objects can be repaired only with authentic bundled bytes matching **all** recorded
content metadata. This remains an explicitly authorized, isolated seed/recovery action, not ordinary
startup repair or permission to rerun seed against unexpected installation data.

The ordinary two-replica Kubernetes Deployment explicitly sets `KIRA_TUTORIAL_SEED_ENABLED="false"`
and `KIRA_TUTORIAL_MEDIA_DIRECTORY=/var/lib/kira/tutorial-media`, backed by the same stable
namespace-local **`kira-tutorial-media` Filesystem/RWX PVC** for every pod. `/tmp` has separate bounded
ephemeral scratch and is not the media store. PostgreSQL stores metadata/references, not media bytes.
See [DEPLOYMENT.md](DEPLOYMENT.md#shared-tutorial-media-storage-and-fresh-bootstrap) for the unresolved
class/capacity template, ownership, retention and installed multi-node acceptance gates. No provider,
permissions or durability are established just by the RWX/fsGroup declarations.

### Media transactions and reconciliation

The database row authorizes an immutable filename: its lowercase canonical UUID plus `.jpg` for
JPEG or `.png` for PNG. New server UUIDs are never adopted from orphan files or deliberately reused.
The file is exclusively created, bounded, completed and forced before inserting its row; there is
**no atomic transaction across PostgreSQL and the filesystem**. A rollback, crash or UNKNOWN commit
outcome may leave a recognizable rowless file. An exception is not permission to delete an upload
whose commit outcome is unknown. A known committed delete attempts physical cleanup only after the
outer transaction completes; failed/missed cleanup leaves a rowless file, not a database rollback.

Delivery authorizes first, then verifies the size and lowercase SHA-256 of the **exact bounded bytes
returned**, before either 200 or matching-ETag 304. Missing/corrupt media is unavailable, with a
noncacheable error rather than corrupt content or an integrity-bypassing 304. Published immutable
and ADMIN-only draft private/no-store caching remain distinct.

The cooperative PostgreSQL media lock covers writable READ_COMMITTED transactions, decision reads
and effects through the actual outer transaction. Reference creation/publication takes media before
tutorial/category locks. Reconciliation captures a fixed bounded file inventory before its fresh
database read; it does not discover new deletion candidates later or continue after losing its DB
identity/lock. This protocol does not fence old binaries or independent filesystem writers.

| `kira.tutorial` property | Default | Meaning |
|---|---:|---|
| `media-reconciliation-enabled` | `false` | Mutating worker is opt-in; ordinary inspection is report-only. |
| `media-reconciliation-interval-millis` | `300000` | Worker interval, supported range 1000–3600000. |
| `media-inspection-limit` | `10000` | Bounded inventory/row inspection, supported range 1–100000. |
| `media-lock-timeout-millis` | `5000` | Bounded media-lock acquisition, supported range 1–30000. |

Before enabling mutation, the owners must positively exclude and drain **all old/noncooperating
application, seed, maintenance and restore writers**, including rolling/terminating processes and
post-commit cleanup tails. Establish the same intended database and shared filesystem authority for
every cooperating worker. A configuration flag, advisory lock or successful unit test does not prove
that installed exclusion, permissions, RWX capacity, crash durability or failover fencing exists.
Do not reintroduce old filenames through restore while any writer or cleanup tail can still act.

Only known rowless UUID finals and recognized `.upload-[A-Za-z0-9-]{1,80}\.(jpg|png)` staging artifacts
are quarantine candidates. Preservation uses a fresh no-clobber directory:

```text
quarantine/<canonical-random-uuid>--<original-basename>--<lowercase-sha256>/content
```

The sole regular nonsymlink `content` leaf contains the completely preserved original bytes,
0–4 MiB, matching the SHA-256 in the directory name. A partial copy/unknown entry is retained and
reported, not accepted as completed preservation. Existing complete matching evidence can be reused;
there is no automatic quarantine expiry or recursive cleanup of unknown trees. Repeated inspection
does not erase evidence. Unknown files, directories, links and unsafe/oversized originals require
operator review, not age-based deletion.

Missing/wrong-size/wrong-hash **row-present** draft and published files remain authoritative failures;
reconciliation never deletes their rows, rewrites checksums or treats them as orphans. Recover only
authentic exact bytes. Explicit seed repair stages and verifies those bytes, preserves a bounded
corrupt regular original completely before intentional atomic replacement, and refuses unsafe or
unpreservable originals. An interrupted/unsupported repair is not success; retain its evidence for
owner recovery instead of weakening bounds or fabricating metadata.

### Separately authorized first seed

Before admitting normal traffic to a genuinely fresh installation, the service/platform owners must
approve and perform one **serialized, quarantined bootstrap** using the exact release image,
migrated database and selected shared claim. Positively establish freshness and exclusive writer
admission/custody; exclude and drain other application, seed and maintenance writers, including
rolling/terminating processes. Unexpected existing rows or media are an operator **STOP**, not
permission to delete, replace or rerun seed. Do not reduce the committed steady-state replicas or
introduce a privileged initialization container to bypass storage permissions.

Enable tutorial seeding only for that isolated, authorized operation. Keep traffic quarantined until
there is positive evidence of successful completion, the expected guides/categories/order and six
asset variants, matching DB/media references and bytes on the intended claim. Stop the bootstrap
writer and retain seed=false for ordinary replicas. Verify both normal replicas read the resulting
media before releasing traffic. No bootstrap Job/controller or concurrency safety guarantee is
provided by this repository slice; the installed procedure and completion remain external gates.

Startup retains the published pointer/category/media relational checks and inspects media size and
SHA-256. Published missing/corrupt bytes fail readiness; an incomplete/overflow/error inspection
that leaves any published subset unverified cannot pass as clean. Draft failures and orphan/unknown
artifacts are explicit reports, not automatic repair. This is an observational bounded inspection,
not a continuous or atomic online health snapshot; delivery still checks exact bytes on each request.
**Empty tutorial tables can pass readiness without any initial guides.** Readiness does not prove
writeability, installed storage durability or bootstrap completion.

Back up and restore the database and media as a matched encrypted pair under the writer exclusion
**and positive drain**, including completion callbacks, protocol in
[DISASTER_RECOVERY.md](DISASTER_RECOVERY.md#read-only-restored-pair-media-verification). After both
selected restore stages, use its read-only semantic checker for **every draft and published row**
before migration, application access handoff or traffic. Manifest hashes alone cannot establish
row/file consistency. The restore helper refuses an existing target, even if empty; a mounted PVC
root requires a separately approved isolated restore/custody mapping, not a direct in-place invocation
or deletion of the stable claim. Reconciliation is eventual recovery of recognizable debris, not
cross-store atomicity or a substitute for authentic backup/restore.

An older binary with the pre-fix startup validator rejects an archived category that retains a
PUBLISHED child. This correction does not make rollback to that binary safe; retain a compatible
binary when planning upgrades/rollback. No startup repair or data rewrite is performed.
