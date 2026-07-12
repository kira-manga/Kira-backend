package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.exception.ConflictException
import me.manga.kira.backend.common.exception.GoneException
import me.manga.kira.backend.common.exception.NotFoundException

/**
 * Source-config boundary exceptions (PLAN §4.3/§9). These extend the common `ApiException` types so the
 * single `GlobalExceptionHandler` maps them to the problem envelope. Kept in the application layer (the
 * domain state machine stays framework-free — its pure exceptions are translated here). Codes are part
 * of the API contract; the `{api}`/revision identifiers in messages are caller-supplied path values, not
 * secrets (PLAN §6 forbids echoing config/header/password values, never these identifiers).
 */

/** 404 — no source with the given api. */
class SourceNotFoundException(api: String) :
    NotFoundException("source '$api' not found.", code = "SOURCE_NOT_FOUND")

/** 404 — no such revision for the source. */
class RevisionNotFoundException(api: String, revisionNumber: Int) :
    NotFoundException("revision $revisionNumber of source '$api' not found.", code = "REVISION_NOT_FOUND")

/** 404 — no published document snapshot with the given revision. */
class DocumentNotFoundException(revision: Long) :
    NotFoundException("published document revision $revision not found.", code = "DOCUMENT_NOT_FOUND")

/** 404 — no source-config document has ever been published (public `GET /source-config/document`; PLAN §4.1). */
class NoPublishedDocumentException :
    NotFoundException("no source-config document has been published yet.", code = "NO_PUBLISHED_DOCUMENT")

/**
 * 410 — the source is terminally `removed`, so it is absent from the served document (PLAN §4.1
 * `GET /sources/{api}`). Distinct from a 404 (unknown/never-published) so the app can distinguish
 * "gone for good" from "no such source". The `{api}` is a caller-supplied path value, not a secret.
 */
class SourceRemovedException(api: String) :
    GoneException("source '$api' has been removed.", code = "SOURCE_REMOVED")

/** 409 — a source with this api already exists (`uq_source_configs_api`; PLAN §4.3). */
class SourceAlreadyExistsException(api: String) :
    ConflictException("source '$api' already exists.", code = "SOURCE_ALREADY_EXISTS")

/**
 * 409 — attempted to publish a `superseded` revision (PLAN §9). Old content is restored ONLY via
 * rollback (forward-roll), never by re-publishing a superseded row.
 */
class RevisionSupersededException(api: String, revisionNumber: Int) :
    ConflictException(
        "revision $revisionNumber of source '$api' is superseded; restore old content via rollback.",
        code = "REVISION_SUPERSEDED",
    )

/**
 * 409 — attempted to publish a draft whose number is not greater than the currently published revision
 * (PLAN §9 — a stale draft is restorable via rollback, which re-validates it as a fresh revision).
 */
class RevisionOlderThanPublishedException(api: String, revisionNumber: Int, publishedNumber: Int) :
    ConflictException(
        "revision $revisionNumber of source '$api' is not newer than the currently published revision " +
            "$publishedNumber; restore old content via rollback.",
        code = "REVISION_OLDER_THAN_PUBLISHED",
    )

/**
 * 409 — un-retire (`retired → active`) attempted for a non-generic engine (PLAN §9). A returning legacy
 * stanza is never re-seeded by the app, so it would stay permanently invisible; its only path out of
 * `retired` is `removed`. Translated from the domain `UnretireNotAllowedForEngineException`.
 */
class UnretireUnsupportedForEngineException(detail: String) :
    ConflictException(detail, code = "UNRETIRE_UNSUPPORTED_FOR_ENGINE")

/** The stable code for the `remove` foot-gun guard 400 (body `{confirm}` must equal the api; PLAN §4.3). */
const val CONFIRMATION_REQUIRED_CODE = "CONFIRMATION_REQUIRED"
