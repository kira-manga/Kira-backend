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
identity tables are empty**. Those imports can rewrite missing/mismatched seed files even if the
identity-table guard subsequently prevents creation of the four pre-migration guides, categories,
order and featured positions. It is not a safe concurrent bootstrap protocol or a repair operation
for an existing installation. Do not rerun it to reconcile unexpected data.

The ordinary two-replica Kubernetes Deployment explicitly sets `KIRA_TUTORIAL_SEED_ENABLED="false"`
and `KIRA_TUTORIAL_MEDIA_DIRECTORY=/var/lib/kira/tutorial-media`, backed by the same stable
namespace-local **`kira-tutorial-media` Filesystem/RWX PVC** for every pod. `/tmp` has separate bounded
ephemeral scratch and is not the media store. PostgreSQL stores metadata/references, not media bytes.
See [DEPLOYMENT.md](DEPLOYMENT.md#shared-tutorial-media-storage-and-fresh-bootstrap) for the unresolved
class/capacity template, ownership, retention and installed multi-node acceptance gates. No provider,
permissions or durability are established just by the RWX/fsGroup declarations.

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

The existing startup validator still fails if a published media row has no regular file or if a
published pointer/category/media relation is inconsistent. **Empty tutorial tables can pass it and
readiness without any initial guides.** It is not a file-hash, writeability, continuous media-health
or bootstrap-completion test. Do not weaken it or use a green readiness result as first-seed evidence.

Back up and restore the database and media as a matched encrypted pair under the writer exclusion
**and positive drain** protocol in [DISASTER_RECOVERY.md](DISASTER_RECOVERY.md). The filesystem/DB
transaction gap (Backend #5) is not solved by persistence alone. That helper refuses an existing
restore target, even if empty; a mounted PVC root requires a separately approved isolated restore/
custody mapping, not a direct in-place invocation or deletion of the stable claim.

An older binary with the pre-fix startup validator rejects an archived category that retains a
PUBLISHED child. This correction does not make rollback to that binary safe; retain a compatible
binary when planning upgrades/rollback. No startup repair or data rewrite is performed.
