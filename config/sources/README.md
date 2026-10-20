# Authored source configurations

These files are version-controlled authoring inputs, **not automatically loaded or published**.
Use the authenticated source-admin revision/publish/lifecycle API; do not edit database rows,
replace signed artifacts, reuse revision numbers, or import the initial bootstrap again.
Keep each `api` stable. A previously withheld source must receive a validated generic revision
and then an explicit enable transition before it is publicly visible.

## Manhwatop

The configuration preserves the `Manhwatop` identity and existing `/manga/{slug}/` URLs. It uses
ordinary public Madara HTML/form endpoints, a separate full chapter-list request, lazy image
attributes, and the existing genre blacklist. Header capture is disabled because the inspected
source and image requests do not require credentials; no cross-host credential trust is added.

Unknown publication dates remain null: the site's relative/localized labels and `Complete`
marker are not reliable ISO dates. Do not fabricate today's date. The upstream chapter list may
contain duplicate URLs; this file does not hide or rewrite saved reading/download identities.

Publication is pending backend validation, independent configuration review, current consumer
qualification, and the actual authenticated publish/enable transaction with signed-catalog
readback. A checked-in configuration is not evidence that a source is live or device-verified.

## Lavatoons

Keeps API `Lavatoons` and the existing manga/chapter path identities. The current site is
`lavascans.com`; the former `lavatoons.com` host is an explicit alias, not a path migration.
Uses public HTML/form endpoints and the current-origin Referer. Unknown chapter numbers stay
blank rather than being invented from URL digits. Carousel duplicates are removed by the newer
engine's stable featured-URL deduplication. The homepage carousel is a single-page featured feed;
later pages return empty without another request. Captured-header support does not authorize broader
image credential forwarding.

## Mangatuk

Uses the current public `api.mangatuk.com` JSON endpoints but emits the historical
`/manga/{slug}/` and `/manga/{seriesSlug}/{chapterSlug}/` storage identities. Official web
redirects do not by themselves reconcile library/read/download keys. The configuration needs
newer engine chapter-parent context, `{chapterId}` request identity and stable numeric chapter
ordering; **do not publish it to the existing `0.1.0` consumers**.

Search genre extraction uses explicit `genreNames[*]` fan-out for the retained Arabic blacklist.
In addition, all discovery feeds exclude explicit `hasAdultChapters:true`. This deliberately
conservative restriction is not claimed equivalent to the old badge blacklist: it can hide a
series with otherwise free chapters. Known coin-locked chapters are excluded; no unlock/payment
or credential flow is added. Equal chapter numbers retain distinct URLs and provider tie order.

All three inputs stay disabled in source control. Backend parsing/validation, updated consumer
qualification and authenticated publication/signature readback remain separate requirements;
`enabled:false` alone does not make an unsupported declaration safe for older app validators.

## Manhwaweb (candidate, not published)

Keeps `Manhwaweb` and the historical `/manhwa/see/{id}` and `/chapters/see/{id}`
storage paths. Requests use the current official `api.manhwaweb.com`; saved references
are not rewritten. Browse/search use the non-erotic alphabetical library with true
zero-based paging, numeric-genre mapping before the existing blacklist, and declarative
genre/sort controls. The home feed deliberately no longer changes from recent updates
on page one to an unrelated alphabetical collection on later pages.

Current chapter groups contain versioned releases: keep every version URL and stable
numeric newest-first order, including groups without the obsolete outer `link` field.
Chapter dates are UTC from actual epoch milliseconds. The observed `img2mw.xyz` reader
and `img1mw.xyz` cover hosts receive only the static public site Referer; captured headers
are disabled and undeclared hosts receive none. API requests remain header-free.

Current replay covers three public detail samples structurally (227 versioned groups),
with one complete 50-chapter/68-page extraction. This does not prove hypothetical historical
link-only response support. Detail genres use dynamic object keys not handled by the
existing scalar field declarations and are not emitted; the first named author is shown.
No site-ranked featured feed is invented: safe activation also needs the parser's optional
featured-endpoint handling to avoid a visible missing-endpoint error. Keep disabled until
that correction is delivered and normal backend validation/publication is completed.
