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

Category gating applies to origin JSON responses, not immediate revocation of already cached
responses. Previously published media remains permanently public, including while its category
or tutorial is archived.

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

The bundled seed runs only when both tutorial identity tables are empty. It publishes the four
pre-migration guides, categories/order/featured positions, and six screenshot variants. It never
merges into or overwrites administrator-authored data. Set `KIRA_TUTORIAL_SEED_ENABLED=false` to
disable it and `KIRA_TUTORIAL_MEDIA_DIRECTORY` to the dedicated mounted volume.

Startup fails if a published media row has no file or if a published pointer/category/media relation
is inconsistent. PostgreSQL stores metadata and references only; media bytes belong in the
`kira-tutorial-media` volume.

An older binary with the pre-fix startup validator rejects an archived category that retains a
PUBLISHED child. This correction does not make rollback to that binary safe; retain a compatible
binary when planning upgrades/rollback. No startup repair or data rewrite is performed.
