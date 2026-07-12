-- V5__completions.sql — the completion foundation (PLAN §5 / §10, Phase 9).
--
-- Two tables: completion_requests (one row per submitted prompt — user, provider, model, prompt,
-- status) and completion_results (the sanitized outcome — result | error + stable error_code +
-- latency). Synchronous execution in v1, but the RUNNING status also enables async later with no
-- schema change (PLAN §10).
--
-- Version ordering (PLAN §5): audit is built in Phase 6 (V4) and completions in Phase 9 (V5), so
-- completions MUST carry the higher version number — a fresh environment migrated mid-campaign must
-- never see V5 applied before V4 exists. `outOfOrder` stays false; versions append in build order.
--
-- Every FK is ON DELETE RESTRICT (PLAN §5 global policy): prompts/results are "RESTRICT-protected
-- evidence" (PLAN §10 retention) — nothing may cascade them away.

-- completion_requests — one row per submitted completion (identity + lifecycle status).
CREATE TABLE completion_requests (
    id         uuid         NOT NULL DEFAULT gen_random_uuid(),
    user_id    uuid         NOT NULL,                 -- the authenticated caller (PLAN §4.6)
    provider   varchar(64)  NOT NULL,                 -- the CompletionProvider.name that served it
    model      varchar(128) NOT NULL,                 -- effective model (submitted, or the provider default)
    prompt     text         NOT NULL,                 -- kept only here (PLAN §10 data hygiene); never logged
    status     varchar(16)  NOT NULL,
    created_at timestamptz  NOT NULL,                 -- no DB default (PLAN §5/§10): set from the injected Clock
    updated_at timestamptz  NOT NULL,
    CONSTRAINT pk_completion_requests PRIMARY KEY (id),
    CONSTRAINT fk_completion_requests_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_completion_status
        CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED'))
);
-- Newest-first per user (PLAN §4.6 list ordering) — the one normative read order for GET /completions.
CREATE INDEX idx_completions_user ON completion_requests (user_id, created_at DESC);

-- completion_results — the sanitized outcome, exactly one row per finished request.
-- result:     the (possibly truncated) completion text on success; NULL on failure.
-- error:      the SANITIZED, client-visible generic message on failure; NULL on success. NEVER a
--             stack trace or a raw provider exception (PLAN §10) — provider internals live only in
--             the secured, request-id-correlated server log.
-- error_code: the stable §10 catalog code on failure; NULL on success.
CREATE TABLE completion_results (
    id         uuid        NOT NULL DEFAULT gen_random_uuid(),
    request_id uuid        NOT NULL,
    result     text        NULL,
    error      text        NULL,
    error_code varchar(64) NULL,
    latency_ms int         NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_completion_results PRIMARY KEY (id),
    -- One outcome per request (PLAN §5) — also lets result/error be looked up by request_id.
    CONSTRAINT uq_completion_results_request UNIQUE (request_id),
    CONSTRAINT fk_completion_results_request
        FOREIGN KEY (request_id) REFERENCES completion_requests (id) ON DELETE RESTRICT,
    -- Never BOTH a result and an error (PLAN §5/§10). A success carries result; a failure carries error.
    CONSTRAINT chk_result_xor_error
        CHECK (NOT (result IS NOT NULL AND error IS NOT NULL)),
    -- error and error_code travel together: both NULL (success) or both non-NULL (failure) (PLAN §5/§10).
    CONSTRAINT chk_completion_error_code_pairing
        CHECK ((error_code IS NULL) = (error IS NULL))
);
