-- Backend-owned complaints: additive, transactional storage only. No writer, capacity, accepted
-- catalog, credential or HTTP capability is enabled by this migration. V1--V13 remain immutable.
-- See docs/COMPLAINT_SCHEMA.md for the representation and the separate locked-writer obligations.
-- Helpers are immutable/current-row-only. They do not authenticate evidence or query other tables.
-- Parsed SQL bodies bind helper dependencies at creation, including under pg_dump/restore
-- search_path=empty. Never replace them with late-bound string bodies or hardcode the public schema.

CREATE FUNCTION complaint_is_v4(value uuid) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce((get_byte(uuid_send(value), 6) >> 4) = 4
        AND (get_byte(uuid_send(value), 8) & 192) = 128, false);

CREATE FUNCTION complaint_scope_valid(scope uuid, test_only boolean) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce((scope = '00000000-0000-0000-0000-000000000000'::uuid AND NOT test_only)
        OR (complaint_is_v4(scope) AND test_only), false);

CREATE FUNCTION complaint_text_valid(value text, minimum integer, maximum integer, byte_limit integer)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(char_length(value) BETWEEN minimum AND maximum
        AND octet_length(value) <= byte_limit
        AND value COLLATE "C" !~ U&'[\0001-\0008\000B-\001F\007F-\009F]', false);

CREATE FUNCTION complaint_ascii_valid(value text, maximum integer) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(octet_length(value) BETWEEN 1 AND maximum
        AND value COLLATE "C" ~ '^[ -~]+$', false);

CREATE FUNCTION complaint_opaque_valid(value text, maximum integer) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(octet_length(value) BETWEEN 1 AND maximum, false);

CREATE FUNCTION complaint_event_id_valid(value text) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(value COLLATE "C" ~ '^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$', false);

CREATE FUNCTION complaint_digest_valid(value bytea) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(octet_length(value) = 32, false);

CREATE FUNCTION complaint_bytes_match(value bytea, digest bytea, byte_limit integer) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN CASE WHEN coalesce(octet_length(value) BETWEEN 1 AND byte_limit, false)
        AND complaint_digest_valid(digest) THEN sha256(value) = digest ELSE false END;

CREATE FUNCTION complaint_finite_times(VARIADIC values_ timestamptz[]) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN values_ IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM unnest(values_) AS entry WHERE entry IS NOT NULL AND NOT isfinite(entry)
    );

CREATE FUNCTION complaint_uuid_array_valid(values_ uuid[], minimum integer, maximum integer)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN CASE
        WHEN values_ IS NULL OR minimum IS NULL OR maximum IS NULL OR minimum < 0 OR maximum < minimum THEN false
        WHEN cardinality(values_) = 0 THEN minimum = 0
        WHEN coalesce(array_ndims(values_) = 1 AND array_lower(values_, 1) = 1
            AND cardinality(values_) BETWEEN minimum AND maximum, false) THEN
            NOT EXISTS (SELECT 1 FROM unnest(values_) AS entry WHERE entry IS NULL)
            AND cardinality(values_) = (SELECT count(DISTINCT entry) FROM unnest(values_) AS entry)
        ELSE false END;

CREATE FUNCTION complaint_amounts_valid(values_ bigint[], size_ integer, minimum bigint)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN CASE WHEN coalesce(array_ndims(values_) = 1 AND array_lower(values_, 1) = 1
        AND cardinality(values_) = size_ AND size_ BETWEEN 1 AND 50 AND minimum IS NOT NULL, false)
        THEN NOT EXISTS (SELECT 1 FROM unnest(values_) AS entry WHERE entry IS NULL OR entry < minimum)
        ELSE false END;

CREATE FUNCTION complaint_vector_valid(values_ bigint[]) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN complaint_amounts_valid(values_, 22, 0);

CREATE FUNCTION complaint_vector_lte(values_ bigint[], ceiling_ bigint[]) RETURNS boolean
LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN CASE WHEN complaint_vector_valid(values_) AND complaint_vector_valid(ceiling_)
        THEN NOT EXISTS (SELECT 1 FROM generate_series(1, 22) AS n WHERE values_[n] > ceiling_[n])
        ELSE false END;

CREATE FUNCTION complaint_event_count_valid(kind text, target_count integer, test_only boolean)
RETURNS boolean LANGUAGE sql IMMUTABLE PARALLEL SAFE
RETURN coalesce(CASE
        WHEN kind IN ('OWNER_DELETE', 'ADMIN_DELETE') THEN target_count = 1
        WHEN kind IN ('ADMIN_BATCH_DELETE', 'RETENTION', 'INSTALLATION_RETIREMENT')
            THEN target_count BETWEEN 1 AND 50
        WHEN kind = 'OWNER_DELETE_ALL' THEN target_count BETWEEN 0 AND 100
        WHEN kind = 'INSTALLATION_MANIFEST' THEN test_only AND target_count BETWEEN 1 AND 500
        WHEN kind = 'TEST_RUN_PURGE' THEN test_only AND target_count = 0
        ELSE false END, false);

CREATE TABLE complaint_installation_ids (
    id uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    state varchar(24) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    terminal_at timestamptz,
    CONSTRAINT pk_complaint_installation_ids PRIMARY KEY (id),
    CONSTRAINT uq_complaint_installation_scope UNIQUE (id, data_scope_id),
    CONSTRAINT chk_complaint_installation_id CHECK (complaint_is_v4(id)),
    CONSTRAINT chk_complaint_installation_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_installation_state CHECK (
        (state IN ('ACTIVE', 'DELETION_PENDING', 'RECOVERY_RESERVED') AND terminal_at IS NULL)
        OR (state IN ('RETIRED', 'DELETED') AND terminal_at IS NOT NULL)
    ),
    CONSTRAINT chk_complaint_installation_times CHECK (complaint_finite_times(created_at, terminal_at))
);
CREATE INDEX idx_complaint_installation_scope ON complaint_installation_ids (data_scope_id, id);

CREATE TABLE app_installations (
    id uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    secret_verifier bytea,
    platform varchar(8) COLLATE "C",
    state varchar(24) COLLATE "C" NOT NULL,
    credential_version bigint NOT NULL,
    owner_reference uuid,
    created_at timestamptz NOT NULL,
    last_authenticated_at timestamptz,
    deleted_at timestamptz,
    verifier_expires_at timestamptz,
    version bigint NOT NULL,
    CONSTRAINT pk_app_installations PRIMARY KEY (id),
    CONSTRAINT uq_app_installations_scope UNIQUE (id, data_scope_id),
    CONSTRAINT uq_app_installations_owner_reference UNIQUE (owner_reference),
    CONSTRAINT fk_app_installations_reservation FOREIGN KEY (id, data_scope_id)
        REFERENCES complaint_installation_ids (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT chk_app_installations_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_app_installations_versions CHECK (credential_version > 0 AND version > 0),
    CONSTRAINT chk_app_installations_verifier CHECK (
        secret_verifier IS NULL OR complaint_digest_valid(secret_verifier)
    ),
    CONSTRAINT chk_app_installations_shape CHECK (
        (state IN ('ACTIVE', 'DELETION_PENDING') AND secret_verifier IS NOT NULL
            AND platform IS NOT NULL AND platform IN ('ANDROID', 'IOS')
            AND complaint_is_v4(owner_reference) AND owner_reference <> id
            AND last_authenticated_at IS NOT NULL AND deleted_at IS NULL AND verifier_expires_at IS NULL)
        OR (state = 'DELETED' AND secret_verifier IS NOT NULL AND platform IS NULL
            AND owner_reference IS NULL AND last_authenticated_at IS NULL
            AND deleted_at IS NOT NULL AND verifier_expires_at IS NOT NULL
            AND verifier_expires_at = deleted_at + interval '192 hours')
    ),
    CONSTRAINT chk_app_installations_times CHECK (
        complaint_finite_times(created_at, last_authenticated_at, deleted_at, verifier_expires_at)
    )
);
CREATE INDEX idx_app_installations_activity ON app_installations (state, last_authenticated_at, id);
CREATE INDEX idx_app_installations_expiry ON app_installations (verifier_expires_at, id) WHERE state = 'DELETED';
CREATE INDEX idx_app_installations_scope ON app_installations (data_scope_id, id);

CREATE TABLE complaint_resource_ids (
    id uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    state varchar(24) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    deleted_at timestamptz,
    CONSTRAINT pk_complaint_resource_ids PRIMARY KEY (id),
    CONSTRAINT uq_complaint_resource_scope UNIQUE (id, data_scope_id),
    CONSTRAINT chk_complaint_resource_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_resource_state CHECK (
        (state IN ('LIVE', 'DELETION_PENDING') AND deleted_at IS NULL)
        OR (state = 'DELETED' AND deleted_at IS NOT NULL)
    ),
    CONSTRAINT chk_complaint_resource_times CHECK (complaint_finite_times(created_at, deleted_at))
);
CREATE INDEX idx_complaint_resource_scope ON complaint_resource_ids (data_scope_id, id);

CREATE TABLE complaints (
    id uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    owner_id uuid,
    ownership varchar(24) COLLATE "C" NOT NULL,
    kind varchar(8) COLLATE "C" NOT NULL,
    type varchar(16) COLLATE "C",
    status varchar(16) COLLATE "C" NOT NULL,
    notice_key varchar(96) COLLATE "C",
    subject text,
    body text,
    parent_resource_id uuid,
    app_version text,
    platform varchar(8) COLLATE "C",
    os_version text,
    manufacturer text,
    device_model text,
    closure_reason text,
    closed_at timestamptz,
    closure_provenance varchar(8) COLLATE "C",
    closure_actor_id uuid,
    legacy_collection varchar(32) COLLATE "C",
    legacy_key_id varchar(128) COLLATE "C",
    legacy_document_hmac bytea,
    legacy_payload_hash bytea,
    legacy_owner_fingerprint bytea,
    legacy_reconciliation_code varchar(64) COLLATE "C",
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    version bigint NOT NULL,
    CONSTRAINT pk_complaints PRIMARY KEY (id),
    CONSTRAINT uq_complaints_scope UNIQUE (id, data_scope_id),
    CONSTRAINT uq_complaints_legacy_identity UNIQUE (legacy_collection, legacy_key_id, legacy_document_hmac),
    CONSTRAINT fk_complaints_resource FOREIGN KEY (id, data_scope_id)
        REFERENCES complaint_resource_ids (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT fk_complaints_owner FOREIGN KEY (owner_id, data_scope_id)
        REFERENCES app_installations (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT fk_complaints_parent FOREIGN KEY (parent_resource_id, data_scope_id)
        REFERENCES complaint_resource_ids (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT fk_complaints_closure_actor FOREIGN KEY (closure_actor_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_complaints_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaints_ownership CHECK (
        (ownership = 'INSTALLATION' AND owner_id IS NOT NULL)
        OR (ownership = 'LEGACY_UNCLAIMED' AND owner_id IS NULL AND NOT test_only)
        OR (ownership = 'SYSTEM' AND owner_id IS NULL)
    ),
    CONSTRAINT chk_complaints_status CHECK (
        status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED', 'PLANNED', 'PINNED', 'NOT_PLANNED')
        OR (status = 'UNKNOWN' AND ownership = 'LEGACY_UNCLAIMED')
    ),
    CONSTRAINT chk_complaints_notice_pair CHECK ((kind = 'NOTICE') = (ownership = 'SYSTEM')),
    CONSTRAINT chk_complaints_content CHECK (
        (kind = 'NOTICE' AND status = 'PINNED' AND notice_key IS NOT NULL AND type IS NULL
            AND subject IS NULL AND body IS NULL AND parent_resource_id IS NULL
            AND app_version IS NULL AND platform IS NULL AND os_version IS NULL
            AND manufacturer IS NULL AND device_model IS NULL)
        OR (kind IN ('REPORT', 'REPLY') AND body IS NOT NULL AND type IS NOT NULL
            AND type IN ('TECHNICAL', 'LANGUAGES', 'SITES_ADD', 'SITE_ERROR', 'FEATURES', 'CUSTOM')
            AND ((notice_key IS NULL AND subject IS NOT NULL)
                OR (kind = 'REPLY' AND notice_key IS NOT NULL AND type = 'CUSTOM' AND subject IS NULL)))
    ),
    CONSTRAINT chk_complaints_parent CHECK (
        (kind <> 'REPLY' AND parent_resource_id IS NULL)
        OR (kind = 'REPLY' AND parent_resource_id IS NOT NULL AND parent_resource_id <> id)
        OR (kind = 'REPLY' AND parent_resource_id IS NULL AND ownership = 'LEGACY_UNCLAIMED'
            AND legacy_reconciliation_code IS NOT NULL AND legacy_reconciliation_code = 'AMBIGUOUS_NOTICE_PARENT')
    ),
    CONSTRAINT chk_complaints_text CHECK (
        (subject IS NULL OR complaint_text_valid(subject, 1, 200, 800))
        AND (body IS NULL OR complaint_text_valid(body, 1, 1000, 4000))
        AND (notice_key IS NULL OR notice_key COLLATE "C" ~ '^[a-z0-9._-]{1,96}$')
        AND (closure_reason IS NULL OR complaint_text_valid(closure_reason, 1, 500, 2000))
        AND (app_version IS NULL OR complaint_text_valid(app_version, 0, 64, 256))
        AND (os_version IS NULL OR complaint_text_valid(os_version, 0, 128, 512))
        AND (manufacturer IS NULL OR complaint_text_valid(manufacturer, 0, 128, 512))
        AND (device_model IS NULL OR complaint_text_valid(device_model, 0, 128, 512))
    ),
    CONSTRAINT chk_complaints_platform CHECK (
        (platform IS NULL OR platform IN ('ANDROID', 'IOS'))
        AND (ownership <> 'INSTALLATION' OR (platform IS NOT NULL AND os_version IS NOT NULL
            AND manufacturer IS NOT NULL AND device_model IS NOT NULL))
    ),
    CONSTRAINT chk_complaints_closure CHECK (
        (status <> 'CLOSED' AND closure_reason IS NULL AND closed_at IS NULL
            AND closure_provenance IS NULL AND closure_actor_id IS NULL)
        OR (status = 'CLOSED' AND closure_provenance IS NOT NULL AND (
            (closure_provenance = 'ADMIN' AND closure_reason IS NOT NULL
                AND closed_at IS NOT NULL AND closure_actor_id IS NOT NULL)
            OR (closure_provenance = 'LEGACY' AND ownership = 'LEGACY_UNCLAIMED' AND closure_actor_id IS NULL)))
    ),
    CONSTRAINT chk_complaints_legacy CHECK (
        (ownership <> 'LEGACY_UNCLAIMED' AND legacy_collection IS NULL AND legacy_key_id IS NULL
            AND legacy_document_hmac IS NULL AND legacy_payload_hash IS NULL
            AND legacy_owner_fingerprint IS NULL AND legacy_reconciliation_code IS NULL)
        OR (ownership = 'LEGACY_UNCLAIMED' AND complaint_ascii_valid(legacy_collection, 32)
            AND complaint_ascii_valid(legacy_key_id, 128) AND complaint_digest_valid(legacy_document_hmac)
            AND complaint_digest_valid(legacy_payload_hash)
            AND (legacy_owner_fingerprint IS NULL OR complaint_digest_valid(legacy_owner_fingerprint))
            AND legacy_reconciliation_code IS NOT NULL
            AND legacy_reconciliation_code IN ('MAPPED', 'AMBIGUOUS_NOTICE_PARENT')
            AND (legacy_reconciliation_code <> 'AMBIGUOUS_NOTICE_PARENT' OR (kind = 'REPLY' AND parent_resource_id IS NULL)))
    ),
    CONSTRAINT chk_complaints_version CHECK (version > 0),
    CONSTRAINT chk_complaints_times CHECK (complaint_finite_times(created_at, updated_at, closed_at))
);
CREATE UNIQUE INDEX uq_complaints_notice_key ON complaints (data_scope_id, notice_key) WHERE kind = 'NOTICE';
CREATE INDEX idx_complaints_owner_page ON complaints (owner_id, data_scope_id, created_at DESC, id DESC);
CREATE INDEX idx_complaints_parent ON complaints (parent_resource_id, data_scope_id);
CREATE INDEX idx_complaints_closure_actor ON complaints (closure_actor_id);
CREATE INDEX idx_complaints_scope_page ON complaints (data_scope_id, updated_at DESC, id DESC);
CREATE INDEX idx_complaints_status_page ON complaints (data_scope_id, status, updated_at DESC, id DESC);
CREATE INDEX idx_complaints_type_page ON complaints (data_scope_id, type, updated_at DESC, id DESC);
CREATE INDEX idx_complaints_ownership_page ON complaints (data_scope_id, ownership, updated_at DESC, id DESC);
CREATE INDEX idx_complaints_legacy_owner ON complaints (legacy_key_id, legacy_owner_fingerprint);
CREATE INDEX idx_complaints_search ON complaints USING gin (
    to_tsvector('simple'::regconfig, coalesce(subject, '') || ' ' || coalesce(body, ''))
);

-- Durable-before-network outbox. Exact bytes/hash are not a substitute for the closed authenticated
-- codec. No FK from permanent applied evidence back to this eventually compactable table.
CREATE TABLE complaint_journal_publications (
    event_id varchar(43) COLLATE "C" NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    writer_generation uuid NOT NULL,
    journal_epoch bigint NOT NULL,
    event_kind varchar(32) COLLATE "C" NOT NULL,
    target_count integer NOT NULL,
    routing_key_id varchar(128) COLLATE "C" NOT NULL,
    object_key text COLLATE "C" NOT NULL,
    canonicalizer varchar(16) COLLATE "C" NOT NULL,
    event_bytes bytea NOT NULL,
    semantic_hash bytea NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    object_version text COLLATE "C",
    ciphertext_hash bytea,
    object_created_at timestamptz,
    retain_until timestamptz,
    verified_at timestamptz,
    verification_bytes bytea,
    verification_hash bytea,
    applied_at timestamptz,
    CONSTRAINT pk_complaint_publications PRIMARY KEY (event_id),
    CONSTRAINT uq_complaint_publication_scope UNIQUE (event_id, data_scope_id),
    CONSTRAINT uq_complaint_publication_key UNIQUE (object_key),
    CONSTRAINT chk_complaint_publication_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_publication_identity CHECK (
        complaint_event_id_valid(event_id) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
        AND complaint_ascii_valid(routing_key_id, 128) AND complaint_ascii_valid(object_key, 1024)
        AND complaint_event_count_valid(event_kind, target_count, test_only)
    ),
    CONSTRAINT chk_complaint_publication_bytes CHECK (
        canonicalizer = 'kcj-1' AND complaint_bytes_match(event_bytes, semantic_hash, 65536)
    ),
    CONSTRAINT chk_complaint_publication_state CHECK ((
        (state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL
            AND object_created_at IS NULL AND retain_until IS NULL AND verified_at IS NULL
            AND verification_bytes IS NULL AND verification_hash IS NULL AND applied_at IS NULL)
        OR (state IN ('VERIFIED', 'APPLIED') AND complaint_opaque_valid(object_version, 1024)
            AND object_version <> 'null' AND complaint_digest_valid(ciphertext_hash)
            AND object_created_at IS NOT NULL AND retain_until IS NOT NULL AND verified_at IS NOT NULL
            AND complaint_bytes_match(verification_bytes, verification_hash, 65536)
            AND ((state = 'VERIFIED' AND applied_at IS NULL)
                OR (state = 'APPLIED' AND applied_at IS NOT NULL
                    AND event_kind NOT IN ('INSTALLATION_MANIFEST', 'TEST_RUN_PURGE'))))
    ) IS TRUE),
    CONSTRAINT chk_complaint_publication_times CHECK (
        complaint_finite_times(created_at, object_created_at, retain_until, verified_at, applied_at)
    )
);
CREATE INDEX idx_complaint_publication_pending ON complaint_journal_publications (state, created_at, event_id);
CREATE INDEX idx_complaint_publication_epoch
    ON complaint_journal_publications (data_scope_id, writer_generation, journal_epoch, event_id);

CREATE TABLE complaint_idempotency_receipts (
    actor_kind varchar(16) COLLATE "C" NOT NULL,
    actor_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    operation varchar(32) COLLATE "C" NOT NULL,
    fingerprint bytea NOT NULL,
    target_ids uuid[] NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    state varchar(24) COLLATE "C" NOT NULL,
    outcome varchar(8) COLLATE "C",
    response_status integer,
    ack_ids uuid[],
    ack_versions bigint[],
    response_location text COLLATE "C",
    response_etag text COLLATE "C",
    problem_code varchar(64) COLLATE "C",
    publication_ref varchar(43) COLLATE "C",
    consumed_grant_id uuid,
    external_event_id varchar(43) COLLATE "C",
    external_epoch bigint,
    external_object_version text COLLATE "C",
    external_ciphertext_hash bytea,
    created_at timestamptz NOT NULL,
    authorized_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz,
    CONSTRAINT pk_complaint_receipts PRIMARY KEY (actor_kind, actor_id, idempotency_key),
    CONSTRAINT fk_complaint_receipt_publication FOREIGN KEY (publication_ref, data_scope_id)
        REFERENCES complaint_journal_publications (event_id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_receipt_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_receipt_identity CHECK (
        complaint_is_v4(idempotency_key) AND complaint_digest_valid(fingerprint)
        AND ((actor_kind = 'INSTALLATION' AND complaint_is_v4(actor_id)
                AND operation IN ('OWNER_CREATE', 'OWNER_REPLY', 'OWNER_EDIT', 'OWNER_DELETE'))
            OR (actor_kind = 'ADMIN' AND operation IN ('ADMIN_EDIT', 'ADMIN_STATUS', 'ADMIN_CLOSURE',
                'ADMIN_DELETE', 'ADMIN_BATCH_STATUS', 'ADMIN_BATCH_DELETE')))
    ),
    CONSTRAINT chk_complaint_receipt_targets CHECK (
        CASE WHEN operation = 'OWNER_REPLY' THEN complaint_uuid_array_valid(target_ids, 2, 2)
            WHEN operation IN ('ADMIN_BATCH_STATUS', 'ADMIN_BATCH_DELETE') THEN complaint_uuid_array_valid(target_ids, 1, 50)
            ELSE complaint_uuid_array_valid(target_ids, 1, 1) END
    ),
    CONSTRAINT chk_complaint_receipt_phase CHECK ((
        (state = 'IN_PROGRESS' AND outcome IS NULL AND response_status IS NULL
            AND ack_ids IS NULL AND ack_versions IS NULL AND response_location IS NULL
            AND response_etag IS NULL AND problem_code IS NULL AND publication_ref IS NULL
            AND consumed_grant_id IS NULL AND external_event_id IS NULL AND external_epoch IS NULL
            AND external_object_version IS NULL AND external_ciphertext_hash IS NULL
            AND authorized_at IS NULL AND completed_at IS NULL AND expires_at IS NULL)
        OR (state = 'AUTHORIZED_DELETE' AND operation IN ('OWNER_DELETE', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE')
            AND outcome IS NULL AND response_status IS NULL AND ack_ids IS NULL AND ack_versions IS NULL
            AND response_location IS NULL AND response_etag IS NULL AND problem_code IS NULL
            AND publication_ref IS NOT NULL AND authorized_at IS NOT NULL
            AND ((actor_kind = 'ADMIN' AND consumed_grant_id IS NOT NULL)
                OR (actor_kind = 'INSTALLATION' AND consumed_grant_id IS NULL))
            AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL
            AND external_ciphertext_hash IS NULL AND completed_at IS NULL AND expires_at IS NULL)
        OR (state = 'COMPLETED' AND outcome IN ('APPLIED', 'REJECTED')
            AND response_status IS NOT NULL AND completed_at IS NOT NULL AND expires_at IS NOT NULL
            AND expires_at = completed_at + interval '192 hours')
    ) IS TRUE),
    CONSTRAINT chk_complaint_receipt_result CHECK ((
        state <> 'COMPLETED'
        OR (outcome = 'REJECTED' AND ack_ids IS NULL AND ack_versions IS NULL
            AND response_location IS NULL AND response_etag IS NULL AND publication_ref IS NULL
            AND consumed_grant_id IS NULL AND authorized_at IS NULL AND external_event_id IS NULL
            AND external_epoch IS NULL AND external_object_version IS NULL AND external_ciphertext_hash IS NULL
            AND ((problem_code IN ('COMPLAINT_NOT_FOUND', 'COMPLAINT_PARENT_NOT_FOUND') AND response_status = 404)
                OR (problem_code IN ('COMPLAINT_CAPACITY_REACHED', 'COMPLAINT_INVALID_TRANSITION',
                    'COMPLAINT_NO_CHANGE', 'COMPLAINT_DELETION_PENDING') AND response_status = 409)
                OR (problem_code = 'PRECONDITION_FAILED' AND response_status = 412)))
        OR (outcome = 'APPLIED' AND problem_code IS NULL AND (
            (operation IN ('OWNER_DELETE', 'ADMIN_DELETE') AND response_status = 204
                AND ack_ids IS NULL AND ack_versions IS NULL AND response_location IS NULL AND response_etag IS NULL)
            OR (operation = 'ADMIN_BATCH_DELETE' AND response_status = 200 AND ack_ids = target_ids
                AND ack_versions IS NULL AND response_location IS NULL AND response_etag IS NULL)
            OR (operation = 'ADMIN_BATCH_STATUS' AND response_status = 200 AND ack_ids = target_ids
                AND complaint_amounts_valid(ack_versions, cardinality(target_ids), 1)
                AND response_location IS NULL AND response_etag IS NULL)
            OR (operation IN ('OWNER_CREATE', 'OWNER_REPLY', 'OWNER_EDIT', 'ADMIN_EDIT', 'ADMIN_STATUS', 'ADMIN_CLOSURE')
                AND complaint_uuid_array_valid(ack_ids, 1, 1) AND ack_ids[1] = ANY(target_ids)
                AND complaint_amounts_valid(ack_versions, 1, 1)
                AND response_etag = '"complaint-' || ack_ids[1]::text || '-v' || ack_versions[1]::text || '"'
                AND ((operation IN ('OWNER_CREATE', 'OWNER_REPLY') AND response_status = 201
                        AND response_location = '/api/v1/complaints/' || ack_ids[1]::text)
                    OR (operation NOT IN ('OWNER_CREATE', 'OWNER_REPLY') AND response_status = 200
                        AND response_location IS NULL)))))
    ) IS TRUE),
    CONSTRAINT chk_complaint_receipt_external CHECK ((
        state <> 'COMPLETED' OR outcome <> 'APPLIED'
        OR (operation IN ('OWNER_DELETE', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE')
            AND publication_ref IS NOT NULL AND external_event_id = publication_ref
            AND complaint_event_id_valid(external_event_id) AND external_epoch > 0
            AND complaint_opaque_valid(external_object_version, 1024) AND external_object_version <> 'null'
            AND complaint_digest_valid(external_ciphertext_hash) AND authorized_at IS NOT NULL
            AND ((actor_kind = 'ADMIN' AND consumed_grant_id IS NOT NULL)
                OR (actor_kind = 'INSTALLATION' AND consumed_grant_id IS NULL)))
        OR (operation NOT IN ('OWNER_DELETE', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE')
            AND publication_ref IS NULL AND consumed_grant_id IS NULL AND authorized_at IS NULL
            AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL
            AND external_ciphertext_hash IS NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_receipt_times CHECK (complaint_finite_times(created_at, authorized_at, completed_at, expires_at))
);
CREATE INDEX idx_complaint_receipt_publication ON complaint_idempotency_receipts (publication_ref, data_scope_id);
CREATE INDEX idx_complaint_receipt_expiry ON complaint_idempotency_receipts (expires_at, actor_kind, actor_id, idempotency_key)
    WHERE state = 'COMPLETED';
CREATE INDEX idx_complaint_receipt_scope ON complaint_idempotency_receipts (data_scope_id, actor_kind, actor_id, idempotency_key);

CREATE TABLE installation_deletion_receipts (
    installation_id uuid NOT NULL,
    deletion_key uuid NOT NULL,
    submitted_credential_version bigint NOT NULL,
    fingerprint bytea NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    state varchar(24) COLLATE "C" NOT NULL,
    outcome varchar(8) COLLATE "C",
    response_status integer,
    publication_ref varchar(43) COLLATE "C",
    external_event_id varchar(43) COLLATE "C",
    external_epoch bigint,
    external_object_version text COLLATE "C",
    external_ciphertext_hash bytea,
    created_at timestamptz NOT NULL,
    authorized_at timestamptz,
    completed_at timestamptz,
    expires_at timestamptz,
    CONSTRAINT pk_installation_deletion_receipts PRIMARY KEY (installation_id, deletion_key),
    CONSTRAINT fk_installation_receipt_reservation FOREIGN KEY (installation_id, data_scope_id)
        REFERENCES complaint_installation_ids (id, data_scope_id) ON DELETE RESTRICT DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT fk_installation_receipt_publication FOREIGN KEY (publication_ref, data_scope_id)
        REFERENCES complaint_journal_publications (event_id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT chk_installation_receipt_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_installation_receipt_identity CHECK (
        complaint_is_v4(installation_id) AND complaint_is_v4(deletion_key)
        AND submitted_credential_version > 0 AND complaint_digest_valid(fingerprint)
    ),
    CONSTRAINT chk_installation_receipt_phase CHECK ((
        (state = 'IN_PROGRESS' AND outcome IS NULL AND response_status IS NULL AND publication_ref IS NULL
            AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL
            AND external_ciphertext_hash IS NULL AND authorized_at IS NULL AND completed_at IS NULL AND expires_at IS NULL)
        OR (state = 'AUTHORIZED_DELETE' AND outcome IS NULL AND response_status IS NULL AND publication_ref IS NOT NULL
            AND external_event_id IS NULL AND external_epoch IS NULL AND external_object_version IS NULL
            AND external_ciphertext_hash IS NULL AND authorized_at IS NOT NULL AND completed_at IS NULL AND expires_at IS NULL)
        OR (state = 'COMPLETED' AND outcome = 'APPLIED' AND response_status = 204
            AND publication_ref IS NOT NULL AND external_event_id = publication_ref
            AND complaint_event_id_valid(external_event_id) AND external_epoch > 0
            AND complaint_opaque_valid(external_object_version, 1024) AND external_object_version <> 'null'
            AND complaint_digest_valid(external_ciphertext_hash) AND authorized_at IS NOT NULL
            AND completed_at IS NOT NULL AND expires_at = completed_at + interval '192 hours')
    ) IS TRUE),
    CONSTRAINT chk_installation_receipt_times CHECK (complaint_finite_times(created_at, authorized_at, completed_at, expires_at))
);
CREATE INDEX idx_installation_receipt_reservation ON installation_deletion_receipts (installation_id, data_scope_id);
CREATE INDEX idx_installation_receipt_publication ON installation_deletion_receipts (publication_ref, data_scope_id);
CREATE INDEX idx_installation_receipt_expiry ON installation_deletion_receipts (expires_at, installation_id, deletion_key)
    WHERE state = 'COMPLETED';
CREATE INDEX idx_installation_receipt_scope ON installation_deletion_receipts (data_scope_id, installation_id, deletion_key);

CREATE TABLE complaint_deletion_journal_applied (
    object_key text COLLATE "C" NOT NULL,
    object_version text COLLATE "C" NOT NULL,
    event_id varchar(43) COLLATE "C" NOT NULL,
    ciphertext_hash bytea NOT NULL,
    writer_generation uuid NOT NULL,
    journal_epoch bigint NOT NULL,
    event_kind varchar(32) COLLATE "C" NOT NULL,
    target_count integer NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    applied_at timestamptz NOT NULL,
    CONSTRAINT pk_complaint_journal_applied PRIMARY KEY (object_key, object_version),
    CONSTRAINT uq_complaint_applied_scope_kind UNIQUE (object_key, object_version, data_scope_id, event_kind),
    CONSTRAINT chk_complaint_applied_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_applied_identity CHECK (
        complaint_opaque_valid(object_key, 1024) AND complaint_opaque_valid(object_version, 1024)
        AND object_version <> 'null' AND complaint_event_id_valid(event_id)
        AND complaint_digest_valid(ciphertext_hash) AND complaint_is_v4(writer_generation) AND journal_epoch > 0
    ),
    CONSTRAINT chk_complaint_applied_kind CHECK (
        event_kind NOT IN ('INSTALLATION_MANIFEST', 'TEST_RUN_PURGE')
        AND complaint_event_count_valid(event_kind, target_count, test_only)
    ),
    CONSTRAINT chk_complaint_applied_times CHECK (complaint_finite_times(applied_at))
);
CREATE INDEX idx_complaint_applied_event ON complaint_deletion_journal_applied (event_id);
CREATE INDEX idx_complaint_applied_scope ON complaint_deletion_journal_applied (data_scope_id, writer_generation, journal_epoch);

CREATE TABLE complaint_deletion_journal_retirements (
    object_key text COLLATE "C" NOT NULL,
    object_version text COLLATE "C" NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    event_kind varchar(32) COLLATE "C" NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    authorization_catalog_generation bigint NOT NULL,
    authorization_catalog_hash bytea NOT NULL,
    restore_floor timestamptz NOT NULL,
    authorization_bytes bytea NOT NULL,
    authorization_hash bytea NOT NULL,
    authorized_at timestamptz NOT NULL,
    completion_catalog_generation bigint,
    completion_catalog_hash bytea,
    completion_bytes bytea,
    completion_hash bytea,
    completed_at timestamptz,
    CONSTRAINT pk_complaint_retirements PRIMARY KEY (object_key, object_version),
    CONSTRAINT fk_complaint_retirement_applied FOREIGN KEY (object_key, object_version, data_scope_id, event_kind)
        REFERENCES complaint_deletion_journal_applied (object_key, object_version, data_scope_id, event_kind) ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_retirement_scope CHECK (
        complaint_scope_valid(data_scope_id, test_only) AND NOT test_only
        AND event_kind IN ('OWNER_DELETE', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE', 'OWNER_DELETE_ALL', 'RETENTION', 'INSTALLATION_RETIREMENT')
    ),
    CONSTRAINT chk_complaint_retirement_authorization CHECK (
        complaint_opaque_valid(object_key, 1024) AND complaint_opaque_valid(object_version, 1024)
        AND authorization_catalog_generation > 0 AND complaint_digest_valid(authorization_catalog_hash)
        AND complaint_bytes_match(authorization_bytes, authorization_hash, 65536)
    ),
    CONSTRAINT chk_complaint_retirement_state CHECK ((
        (state = 'AUTHORIZED' AND completion_catalog_generation IS NULL AND completion_catalog_hash IS NULL
            AND completion_bytes IS NULL AND completion_hash IS NULL AND completed_at IS NULL)
        OR (state = 'COMPLETED' AND completion_catalog_generation > authorization_catalog_generation
            AND complaint_digest_valid(completion_catalog_hash)
            AND complaint_bytes_match(completion_bytes, completion_hash, 65536) AND completed_at IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_retirement_times CHECK (complaint_finite_times(restore_floor, authorized_at, completed_at))
);
CREATE INDEX idx_complaint_retirement_applied
    ON complaint_deletion_journal_retirements (object_key, object_version, data_scope_id, event_kind);
CREATE INDEX idx_complaint_retirement_state ON complaint_deletion_journal_retirements (state, authorized_at);

CREATE TABLE complaint_recovery_capacity_reservations (
    event_id varchar(43) COLLATE "C" NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    publication_ref varchar(43) COLLATE "C",
    state varchar(16) COLLATE "C" NOT NULL,
    accounting_version smallint NOT NULL,
    reserved_amounts bigint[] NOT NULL,
    converted_amounts bigint[],
    created_at timestamptz NOT NULL,
    converted_at timestamptz,
    CONSTRAINT pk_complaint_recovery_reservations PRIMARY KEY (event_id, data_scope_id),
    CONSTRAINT uq_complaint_recovery_event UNIQUE (event_id),
    CONSTRAINT fk_complaint_recovery_publication FOREIGN KEY (publication_ref, data_scope_id)
        REFERENCES complaint_journal_publications (event_id, data_scope_id)
        ON DELETE SET NULL (publication_ref) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT chk_complaint_recovery_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_recovery_identity CHECK (
        complaint_event_id_valid(event_id) AND (publication_ref IS NULL OR publication_ref = event_id)
    ),
    CONSTRAINT chk_complaint_recovery_amounts CHECK (accounting_version = 1 AND complaint_vector_valid(reserved_amounts)),
    CONSTRAINT chk_complaint_recovery_state CHECK ((
        (state = 'RESERVED' AND publication_ref IS NOT NULL AND converted_amounts IS NULL AND converted_at IS NULL)
        OR (state = 'CONVERTED' AND complaint_vector_lte(converted_amounts, reserved_amounts) AND converted_at IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_recovery_times CHECK (complaint_finite_times(created_at, converted_at))
);
CREATE INDEX idx_complaint_recovery_publication ON complaint_recovery_capacity_reservations (publication_ref, data_scope_id);
CREATE INDEX idx_complaint_recovery_scope ON complaint_recovery_capacity_reservations (data_scope_id, event_id);

-- Ordinals are a versioned encoding, never alphabetical inference at read time. These fixed rows
-- and the live control seed are the separately measured baseline, not recursively charged rows.
CREATE TABLE complaint_capacity_counters (
    name varchar(32) COLLATE "C" NOT NULL,
    ordinal smallint NOT NULL,
    accounting_version smallint NOT NULL,
    configuration_hash bytea,
    configuration_closed boolean NOT NULL,
    hard_limit bigint NOT NULL,
    creation_limit bigint NOT NULL,
    free_units bigint NOT NULL,
    actual_units bigint NOT NULL,
    recovery_reserved_units bigint NOT NULL,
    test_reserved_units bigint NOT NULL,
    admission_utc_date date,
    admission_count bigint,
    admission_daily_limit bigint,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_complaint_capacity PRIMARY KEY (name),
    CONSTRAINT uq_complaint_capacity_ordinal UNIQUE (ordinal),
    CONSTRAINT chk_complaint_capacity_encoding CHECK ((
        accounting_version = 1 AND ordinal BETWEEN 1 AND 22
        AND name = (ARRAY['app_installations', 'audit_rows', 'catalog_mutations', 'complaint_rows',
            'import_artifacts', 'import_runs', 'import_staging', 'installation_ids', 'installation_receipts',
            'journal_applied', 'journal_control', 'journal_publications', 'journal_retirements', 'legacy_records',
            'moderation_grants', 'normal_receipts', 'recovery_reservations', 'resource_ids', 'scan_entries',
            'scan_runs', 'storage_bytes', 'test_runs'])[ordinal]
    ) IS TRUE),
    CONSTRAINT chk_complaint_capacity_configuration CHECK (
        (configuration_hash IS NULL OR complaint_digest_valid(configuration_hash))
        AND (configuration_closed OR configuration_hash IS NOT NULL)
    ),
    CONSTRAINT chk_complaint_capacity_equation CHECK (
        hard_limit >= 0 AND creation_limit BETWEEN 0 AND hard_limit AND free_units >= 0
        AND actual_units >= 0 AND recovery_reserved_units >= 0 AND test_reserved_units >= 0
        AND hard_limit::numeric = free_units::numeric + actual_units::numeric
            + recovery_reserved_units::numeric + test_reserved_units::numeric
    ),
    CONSTRAINT chk_complaint_capacity_admission CHECK ((
        (name = 'installation_ids' AND admission_count >= 0 AND admission_daily_limit >= admission_count
            AND (admission_utc_date IS NOT NULL OR admission_count = 0)
            AND (admission_utc_date IS NULL OR isfinite(admission_utc_date)))
        OR (name <> 'installation_ids' AND admission_utc_date IS NULL
            AND admission_count IS NULL AND admission_daily_limit IS NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_capacity_times CHECK (complaint_finite_times(updated_at))
);
INSERT INTO complaint_capacity_counters (
    name, ordinal, accounting_version, configuration_closed, hard_limit, creation_limit,
    free_units, actual_units, recovery_reserved_units, test_reserved_units,
    admission_count, admission_daily_limit, updated_at
)
SELECT name, ordinal, 1, true, 0, 0, 0, 0, 0, 0,
    CASE WHEN name = 'installation_ids' THEN 0 END,
    CASE WHEN name = 'installation_ids' THEN 0 END, now()
FROM unnest(ARRAY['app_installations', 'audit_rows', 'catalog_mutations', 'complaint_rows',
    'import_artifacts', 'import_runs', 'import_staging', 'installation_ids', 'installation_receipts',
    'journal_applied', 'journal_control', 'journal_publications', 'journal_retirements', 'legacy_records',
    'moderation_grants', 'normal_receipts', 'recovery_reservations', 'resource_ids', 'scan_entries',
    'scan_runs', 'storage_bytes', 'test_runs']) WITH ORDINALITY AS counters(name, ordinal);

CREATE TABLE complaint_test_runs (
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    configuration_hash bytea NOT NULL,
    accounting_version smallint NOT NULL,
    installation_limit bigint NOT NULL,
    enrolled_count bigint NOT NULL,
    original_reserve bigint[] NOT NULL,
    unused_reserve bigint[] NOT NULL,
    activation_catalog_generation bigint NOT NULL,
    activation_catalog_hash bytea NOT NULL,
    created_at timestamptz NOT NULL,
    sealed_at timestamptz,
    purging_at timestamptz,
    purged_at timestamptz,
    final_ordinary_epoch bigint,
    terminal_seal_epoch bigint,
    generation_seal_count integer,
    generation_seal_root bytea,
    seal_set_bytes bytea,
    seal_set_hash bytea,
    event_manifest_count bigint,
    event_manifest_root bytea,
    installation_manifest_count bigint,
    installation_manifest_root bytea,
    installation_chunk_count integer,
    retired_count bigint,
    deleted_count bigint,
    permanent_denial_bytes bytea,
    permanent_denial_hash bytea,
    terminal_event_id varchar(43) COLLATE "C",
    terminal_object_key text COLLATE "C",
    terminal_object_version text COLLATE "C",
    terminal_ciphertext_hash bytea,
    terminal_catalog_generation bigint,
    terminal_catalog_hash bytea,
    CONSTRAINT pk_complaint_test_runs PRIMARY KEY (data_scope_id),
    CONSTRAINT chk_complaint_run_scope CHECK (test_only AND complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_run_configuration CHECK (
        complaint_digest_valid(configuration_hash) AND accounting_version = 1 AND installation_limit > 0
        AND enrolled_count BETWEEN 0 AND installation_limit AND complaint_vector_valid(original_reserve)
        AND complaint_vector_lte(unused_reserve, original_reserve)
        AND activation_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(activation_catalog_hash)
    ),
    CONSTRAINT chk_complaint_run_seals CHECK ((
        (final_ordinary_epoch IS NULL AND terminal_seal_epoch IS NULL AND generation_seal_count IS NULL
            AND generation_seal_root IS NULL AND seal_set_bytes IS NULL AND seal_set_hash IS NULL)
        OR (final_ordinary_epoch > 0 AND terminal_seal_epoch > final_ordinary_epoch AND generation_seal_count > 0
            AND complaint_digest_valid(generation_seal_root) AND complaint_bytes_match(seal_set_bytes, seal_set_hash, 65536))
    ) IS TRUE),
    CONSTRAINT chk_complaint_run_manifests CHECK ((
        (event_manifest_count IS NULL AND event_manifest_root IS NULL AND installation_manifest_count IS NULL
            AND installation_manifest_root IS NULL AND installation_chunk_count IS NULL
            AND retired_count IS NULL AND deleted_count IS NULL)
        OR (event_manifest_count >= 0 AND complaint_digest_valid(event_manifest_root)
            AND installation_manifest_count = enrolled_count AND complaint_digest_valid(installation_manifest_root)
            AND installation_chunk_count >= 0 AND retired_count >= 0 AND deleted_count >= 0
            AND retired_count::numeric + deleted_count::numeric = installation_manifest_count::numeric
            AND ((installation_manifest_count = 0 AND installation_chunk_count = 0)
                OR (installation_manifest_count > 0 AND installation_chunk_count BETWEEN 1 AND installation_manifest_count)))
    ) IS TRUE),
    CONSTRAINT chk_complaint_run_denial CHECK (
        (permanent_denial_bytes IS NULL AND permanent_denial_hash IS NULL)
        OR complaint_bytes_match(permanent_denial_bytes, permanent_denial_hash, 65536)
    ),
    CONSTRAINT chk_complaint_run_terminal CHECK ((
        (terminal_event_id IS NULL AND terminal_object_key IS NULL AND terminal_object_version IS NULL
            AND terminal_ciphertext_hash IS NULL AND terminal_catalog_generation IS NULL AND terminal_catalog_hash IS NULL)
        OR (complaint_event_id_valid(terminal_event_id) AND complaint_opaque_valid(terminal_object_key, 1024)
            AND complaint_opaque_valid(terminal_object_version, 1024) AND terminal_object_version <> 'null'
            AND complaint_digest_valid(terminal_ciphertext_hash)
            AND terminal_catalog_generation BETWEEN activation_catalog_generation + 1 AND 65536
            AND complaint_digest_valid(terminal_catalog_hash))
    ) IS TRUE),
    CONSTRAINT chk_complaint_run_state CHECK ((
        (state = 'ACTIVE' AND sealed_at IS NULL AND purging_at IS NULL AND purged_at IS NULL
            AND final_ordinary_epoch IS NULL AND event_manifest_count IS NULL
            AND permanent_denial_bytes IS NULL AND terminal_event_id IS NULL)
        OR (state = 'SEALED' AND sealed_at IS NOT NULL AND purging_at IS NULL AND purged_at IS NULL)
        OR (state IN ('PURGING', 'PURGED') AND sealed_at IS NOT NULL AND purging_at IS NOT NULL
            AND final_ordinary_epoch IS NOT NULL AND event_manifest_count IS NOT NULL
            AND permanent_denial_bytes IS NOT NULL AND terminal_event_id IS NOT NULL
            AND ((state = 'PURGING' AND purged_at IS NULL)
                OR (state = 'PURGED' AND purged_at IS NOT NULL AND unused_reserve = array_fill(0::bigint, ARRAY[22]))))
    ) IS TRUE),
    CONSTRAINT chk_complaint_run_times CHECK (complaint_finite_times(created_at, sealed_at, purging_at, purged_at))
);
CREATE UNIQUE INDEX uq_complaint_run_nonterminal ON complaint_test_runs ((1)) WHERE state IN ('ACTIVE', 'SEALED', 'PURGING');

CREATE TABLE complaint_catalog_mutations (
    operation_token uuid NOT NULL,
    operation_type varchar(64) COLLATE "C" NOT NULL,
    data_scope_id uuid,
    test_only boolean,
    predecessor_generation bigint NOT NULL,
    predecessor_hash bytea NOT NULL,
    successor_generation bigint NOT NULL,
    catalog_writer_generation uuid NOT NULL,
    approval_bytes bytea NOT NULL,
    approval_hash bytea NOT NULL,
    canonicalizer varchar(16) COLLATE "C" NOT NULL,
    unsigned_bytes bytea NOT NULL,
    unsigned_hash bytea NOT NULL,
    signer_policy varchar(24) COLLATE "C" NOT NULL,
    signer_one_id varchar(128) COLLATE "C" NOT NULL,
    signer_one_algorithm varchar(128) COLLATE "C" NOT NULL,
    signer_one_signature bytea,
    signer_two_id varchar(128) COLLATE "C",
    signer_two_algorithm varchar(128) COLLATE "C",
    signer_two_signature bytea,
    envelope_bytes bytea,
    envelope_hash bytea,
    object_key text COLLATE "C" NOT NULL,
    object_version text COLLATE "C",
    retain_until timestamptz,
    primary_evidence_bytes bytea,
    primary_evidence_hash bytea,
    replica_evidence_bytes bytea,
    replica_evidence_hash bytea,
    state varchar(16) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    completed_at timestamptz,
    projected_at timestamptz,
    CONSTRAINT pk_complaint_catalog_mutations PRIMARY KEY (operation_token),
    CONSTRAINT uq_complaint_catalog_successor UNIQUE (successor_generation),
    CONSTRAINT uq_complaint_catalog_object_key UNIQUE (object_key),
    CONSTRAINT chk_complaint_catalog_scope CHECK (
        (data_scope_id IS NULL AND test_only IS NULL) OR complaint_scope_valid(data_scope_id, test_only)
    ),
    CONSTRAINT chk_complaint_catalog_operation CHECK ((
        complaint_is_v4(operation_token) AND complaint_is_v4(catalog_writer_generation)
        AND operation_type IN ('GENESIS', 'RESTORE_SOURCE_ACCEPTANCE', 'RESTORE_SOURCE_EXPIRY',
            'RESTORE_SOURCE_DESTRUCTION', 'TEST_RUN_ACTIVATION', 'TEST_RUN_TERMINAL',
            'EVENT_WRITER_SUCCESSION', 'CATALOG_WRITER_HANDOFF', 'SIGNER_ROTATION_OVERLAP',
            'SIGNER_ROTATION_ACTIVATION', 'OBJECT_RETIREMENT_AUTHORIZATION', 'OBJECT_RETIREMENT_COMPLETION',
            'LEGACY_IMPORT_ACCEPTANCE', 'EPOCH_SEAL')
        AND (operation_type NOT IN ('TEST_RUN_ACTIVATION', 'TEST_RUN_TERMINAL')
            OR (data_scope_id IS NOT NULL AND test_only))
    ) IS TRUE),
    CONSTRAINT chk_complaint_catalog_chain CHECK (
        successor_generation BETWEEN 1 AND 65536 AND predecessor_generation = successor_generation - 1
        AND complaint_digest_valid(predecessor_hash)
        AND ((operation_type = 'GENESIS' AND successor_generation = 1 AND predecessor_hash = decode(repeat('00', 32), 'hex'))
            OR (operation_type <> 'GENESIS' AND successor_generation > 1))
    ),
    CONSTRAINT chk_complaint_catalog_bytes CHECK (
        canonicalizer = 'kcj-1' AND complaint_bytes_match(approval_bytes, approval_hash, 4096)
        AND complaint_bytes_match(unsigned_bytes, unsigned_hash, 8388608) AND complaint_ascii_valid(object_key, 1024)
    ),
    CONSTRAINT chk_complaint_catalog_signers CHECK ((
        complaint_ascii_valid(signer_one_id, 128) AND complaint_ascii_valid(signer_one_algorithm, 128)
        AND (signer_one_signature IS NULL OR octet_length(signer_one_signature) BETWEEN 1 AND 1024)
        AND ((signer_policy = 'SINGLE' AND operation_type <> 'SIGNER_ROTATION_OVERLAP'
                AND signer_two_id IS NULL AND signer_two_algorithm IS NULL AND signer_two_signature IS NULL)
            OR (signer_policy = 'ROTATION_OVERLAP' AND operation_type = 'SIGNER_ROTATION_OVERLAP'
                AND complaint_ascii_valid(signer_two_id, 128) AND complaint_ascii_valid(signer_two_algorithm, 128)
                AND signer_two_id <> signer_one_id
                AND (signer_two_signature IS NULL OR octet_length(signer_two_signature) BETWEEN 1 AND 1024)))
    ) IS TRUE),
    CONSTRAINT chk_complaint_catalog_envelope CHECK (
        (envelope_bytes IS NULL AND envelope_hash IS NULL)
        OR (complaint_bytes_match(envelope_bytes, envelope_hash, 8388608) AND signer_one_signature IS NOT NULL
            AND (signer_policy = 'SINGLE' OR signer_two_signature IS NOT NULL))
    ),
    CONSTRAINT chk_complaint_catalog_copies CHECK ((
        ((primary_evidence_bytes IS NULL AND primary_evidence_hash IS NULL)
            OR complaint_bytes_match(primary_evidence_bytes, primary_evidence_hash, 65536))
        AND ((replica_evidence_bytes IS NULL AND replica_evidence_hash IS NULL)
            OR complaint_bytes_match(replica_evidence_bytes, replica_evidence_hash, 65536))
        AND ((object_version IS NULL AND retain_until IS NULL
                AND primary_evidence_bytes IS NULL AND replica_evidence_bytes IS NULL)
            OR (complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
                AND retain_until IS NOT NULL AND envelope_bytes IS NOT NULL))
    ) IS TRUE),
    CONSTRAINT chk_complaint_catalog_state CHECK ((
        (state = 'PREPARED' AND completed_at IS NULL AND projected_at IS NULL)
        OR (state = 'COMPLETED' AND completed_at IS NOT NULL AND envelope_bytes IS NOT NULL
            AND primary_evidence_bytes IS NOT NULL AND replica_evidence_bytes IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_catalog_times CHECK (complaint_finite_times(created_at, retain_until, completed_at, projected_at))
);
CREATE UNIQUE INDEX uq_complaint_catalog_pending ON complaint_catalog_mutations ((1))
    WHERE state = 'PREPARED' OR (state = 'COMPLETED' AND projected_at IS NULL);
CREATE INDEX idx_complaint_catalog_scope ON complaint_catalog_mutations (data_scope_id, successor_generation);

CREATE TABLE complaint_journal_control (
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    publication_epoch bigint NOT NULL,
    desired_generation bigint NOT NULL,
    implementation_schema integer NOT NULL,
    desired_configuration_hash bytea,
    database_identity uuid,
    restore_identity uuid,
    event_writer_generation uuid,
    accepted_catalog_generation bigint,
    accepted_catalog_hash bytea,
    trust_bundle_hash bytea,
    catalog_writer_generation uuid,
    pending_projection_token uuid,
    maintenance_closed boolean NOT NULL,
    creation_closed boolean NOT NULL,
    scan_requested boolean NOT NULL,
    lease_owner uuid,
    lease_token bigint NOT NULL,
    lease_expires_at timestamptz,
    retention_lease_owner uuid,
    retention_lease_token bigint NOT NULL,
    retention_lease_expires_at timestamptz,
    seal_state varchar(16) COLLATE "C",
    seal_epoch bigint,
    seal_writer_generation uuid,
    seal_operation_token uuid,
    seal_object_key text COLLATE "C",
    seal_bytes bytea,
    seal_hash bytea,
    seal_object_version text COLLATE "C",
    seal_ciphertext_hash bytea,
    seal_retain_until timestamptz,
    seal_verified_at timestamptz,
    seal_verification_bytes bytea,
    seal_verification_hash bytea,
    checkpoint_generation bigint,
    checkpoint_fencing_token bigint,
    checkpoint_catalog_generation bigint,
    checkpoint_catalog_hash bytea,
    checkpoint_writer_generation uuid,
    checkpoint_cutoff_epoch bigint,
    checkpoint_configuration_hash bytea,
    checkpoint_database_identity uuid,
    checkpoint_restore_identity uuid,
    checkpoint_schema integer,
    checkpoint_started_at timestamptz,
    checkpoint_completed_at timestamptz,
    checkpoint_object_count bigint,
    checkpoint_byte_count bigint,
    checkpoint_result varchar(16) COLLATE "C",
    checkpoint_bytes bytea,
    checkpoint_hash bytea,
    updated_at timestamptz NOT NULL,
    CONSTRAINT pk_complaint_journal_control PRIMARY KEY (data_scope_id),
    CONSTRAINT fk_complaint_control_projection FOREIGN KEY (pending_projection_token)
        REFERENCES complaint_catalog_mutations (operation_token) ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_control_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_control_version CHECK (publication_epoch > 0 AND desired_generation > 0 AND implementation_schema = 1),
    CONSTRAINT chk_complaint_control_configuration CHECK (
        (desired_configuration_hash IS NULL OR complaint_digest_valid(desired_configuration_hash))
        AND ((database_identity IS NULL AND restore_identity IS NULL)
            OR (complaint_is_v4(database_identity) AND complaint_is_v4(restore_identity)))
        AND (event_writer_generation IS NULL OR complaint_is_v4(event_writer_generation))
    ),
    CONSTRAINT chk_complaint_control_catalog CHECK ((
        (accepted_catalog_generation IS NULL AND accepted_catalog_hash IS NULL
            AND trust_bundle_hash IS NULL AND catalog_writer_generation IS NULL AND pending_projection_token IS NULL)
        OR (accepted_catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(accepted_catalog_hash)
            AND complaint_digest_valid(trust_bundle_hash) AND complaint_is_v4(catalog_writer_generation))
    ) IS TRUE),
    CONSTRAINT chk_complaint_control_closed CHECK (
        (maintenance_closed AND creation_closed)
        OR (desired_configuration_hash IS NOT NULL AND database_identity IS NOT NULL AND event_writer_generation IS NOT NULL
            AND accepted_catalog_generation IS NOT NULL AND pending_projection_token IS NULL)
    ),
    CONSTRAINT chk_complaint_control_lease CHECK ((
        lease_token >= 0 AND retention_lease_token >= 0
        AND ((lease_owner IS NULL AND lease_expires_at IS NULL)
            OR (complaint_is_v4(lease_owner) AND lease_token > 0 AND lease_expires_at IS NOT NULL))
        AND ((retention_lease_owner IS NULL AND retention_lease_expires_at IS NULL)
            OR (complaint_is_v4(retention_lease_owner) AND retention_lease_token > 0 AND retention_lease_expires_at IS NOT NULL))
    ) IS TRUE),
    CONSTRAINT chk_complaint_control_seal CHECK ((
        (seal_state IS NULL AND seal_epoch IS NULL AND seal_writer_generation IS NULL AND seal_operation_token IS NULL
            AND seal_object_key IS NULL AND seal_bytes IS NULL AND seal_hash IS NULL AND seal_object_version IS NULL
            AND seal_ciphertext_hash IS NULL AND seal_retain_until IS NULL AND seal_verified_at IS NULL
            AND seal_verification_bytes IS NULL AND seal_verification_hash IS NULL)
        OR (seal_epoch > 0 AND complaint_is_v4(seal_writer_generation) AND complaint_is_v4(seal_operation_token)
            AND complaint_ascii_valid(seal_object_key, 1024) AND complaint_bytes_match(seal_bytes, seal_hash, 65536)
            AND ((seal_state = 'SEAL_PREPARED' AND seal_object_version IS NULL AND seal_ciphertext_hash IS NULL
                    AND seal_retain_until IS NULL AND seal_verified_at IS NULL
                    AND seal_verification_bytes IS NULL AND seal_verification_hash IS NULL)
                OR (seal_state = 'SEAL_VERIFIED' AND complaint_opaque_valid(seal_object_version, 1024)
                    AND seal_object_version <> 'null' AND complaint_digest_valid(seal_ciphertext_hash)
                    AND seal_retain_until IS NOT NULL AND seal_verified_at IS NOT NULL
                    AND complaint_bytes_match(seal_verification_bytes, seal_verification_hash, 65536))))
    ) IS TRUE),
    CONSTRAINT chk_complaint_control_checkpoint CHECK ((
        (checkpoint_generation IS NULL AND checkpoint_fencing_token IS NULL AND checkpoint_catalog_generation IS NULL
            AND checkpoint_catalog_hash IS NULL AND checkpoint_writer_generation IS NULL AND checkpoint_cutoff_epoch IS NULL
            AND checkpoint_configuration_hash IS NULL AND checkpoint_database_identity IS NULL AND checkpoint_restore_identity IS NULL
            AND checkpoint_schema IS NULL AND checkpoint_started_at IS NULL AND checkpoint_completed_at IS NULL
            AND checkpoint_object_count IS NULL AND checkpoint_byte_count IS NULL AND checkpoint_result IS NULL
            AND checkpoint_bytes IS NULL AND checkpoint_hash IS NULL)
        OR (checkpoint_generation > 0 AND checkpoint_fencing_token > 0 AND checkpoint_catalog_generation BETWEEN 1 AND 65536
            AND complaint_digest_valid(checkpoint_catalog_hash) AND complaint_is_v4(checkpoint_writer_generation)
            AND checkpoint_cutoff_epoch > 0 AND complaint_digest_valid(checkpoint_configuration_hash)
            AND complaint_is_v4(checkpoint_database_identity) AND complaint_is_v4(checkpoint_restore_identity)
            AND checkpoint_schema = 1 AND checkpoint_started_at IS NOT NULL AND checkpoint_completed_at IS NOT NULL
            AND checkpoint_object_count >= 0 AND checkpoint_byte_count >= 0 AND checkpoint_result = 'SUCCESS'
            AND complaint_bytes_match(checkpoint_bytes, checkpoint_hash, 65536))
    ) IS TRUE),
    CONSTRAINT chk_complaint_control_times CHECK (complaint_finite_times(updated_at, lease_expires_at,
        retention_lease_expires_at, seal_retain_until, seal_verified_at, checkpoint_started_at, checkpoint_completed_at))
);
CREATE INDEX idx_complaint_control_projection ON complaint_journal_control (pending_projection_token);
INSERT INTO complaint_journal_control (
    data_scope_id, test_only, publication_epoch, desired_generation, implementation_schema,
    maintenance_closed, creation_closed, scan_requested, lease_token, retention_lease_token, updated_at
) VALUES ('00000000-0000-0000-0000-000000000000', false, 1, 1, 1, true, true, true, 0, 0, now());

-- Each pass has its own bounded durable row; the common scan UUID identifies the two-pass pair.
-- The fenced worker verifies that both passes have identical bindings before checkpoint publication.
CREATE TABLE complaint_journal_scan_runs (
    scan_id uuid NOT NULL,
    pass smallint NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    restore_identity uuid NOT NULL,
    desired_generation bigint NOT NULL,
    fencing_token bigint NOT NULL,
    writer_generation uuid NOT NULL,
    cutoff_epoch bigint NOT NULL,
    maximum_entries bigint NOT NULL,
    maximum_bytes bigint NOT NULL,
    entry_count bigint NOT NULL,
    entry_bytes bigint NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    manifest_hash bytea,
    started_at timestamptz NOT NULL,
    finished_at timestamptz,
    CONSTRAINT pk_complaint_scan_runs PRIMARY KEY (scan_id, pass),
    CONSTRAINT uq_complaint_scan_scope UNIQUE (scan_id, pass, data_scope_id),
    CONSTRAINT chk_complaint_scan_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_scan_identity CHECK (
        complaint_is_v4(scan_id) AND pass IN (1, 2) AND complaint_is_v4(restore_identity)
        AND desired_generation > 0 AND fencing_token > 0 AND complaint_is_v4(writer_generation) AND cutoff_epoch > 0
    ),
    CONSTRAINT chk_complaint_scan_capacity CHECK (
        maximum_entries > 0 AND maximum_bytes > 0 AND entry_count BETWEEN 0 AND maximum_entries
        AND entry_bytes BETWEEN 0 AND maximum_bytes
    ),
    CONSTRAINT chk_complaint_scan_state CHECK (
        (state = 'SCANNING' AND manifest_hash IS NULL AND finished_at IS NULL)
        OR (state = 'COMPLETE' AND complaint_digest_valid(manifest_hash) AND finished_at IS NOT NULL)
        OR (state = 'ABANDONED' AND manifest_hash IS NULL AND finished_at IS NOT NULL)
    ),
    CONSTRAINT chk_complaint_scan_times CHECK (complaint_finite_times(started_at, finished_at))
);
CREATE INDEX idx_complaint_scan_scope ON complaint_journal_scan_runs (data_scope_id, state, scan_id, pass);

CREATE TABLE complaint_journal_scan_entries (
    scan_id uuid NOT NULL,
    pass smallint NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    object_key text COLLATE "C" NOT NULL,
    object_version text COLLATE "C" NOT NULL,
    ciphertext_hash bytea NOT NULL,
    semantic_hash bytea NOT NULL,
    event_id varchar(43) COLLATE "C",
    event_kind varchar(32) COLLATE "C" NOT NULL,
    writer_generation uuid NOT NULL,
    journal_epoch bigint NOT NULL,
    entry_bytes bigint NOT NULL,
    replay_state varchar(16) COLLATE "C" NOT NULL,
    CONSTRAINT pk_complaint_scan_entries PRIMARY KEY (scan_id, pass, object_key, object_version),
    CONSTRAINT fk_complaint_scan_entry_run FOREIGN KEY (scan_id, pass, data_scope_id)
        REFERENCES complaint_journal_scan_runs (scan_id, pass, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_scan_entry_scope CHECK (complaint_scope_valid(data_scope_id, test_only)),
    CONSTRAINT chk_complaint_scan_entry_identity CHECK (
        complaint_opaque_valid(object_key, 1024) AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
        AND complaint_digest_valid(ciphertext_hash) AND complaint_digest_valid(semantic_hash)
        AND complaint_is_v4(writer_generation) AND journal_epoch > 0 AND entry_bytes > 0
    ),
    CONSTRAINT chk_complaint_scan_entry_kind CHECK (
        (event_kind = 'EPOCH_SEAL' AND event_id IS NULL)
        OR (event_kind IN ('OWNER_DELETE', 'ADMIN_DELETE', 'ADMIN_BATCH_DELETE', 'OWNER_DELETE_ALL', 'RETENTION', 'INSTALLATION_RETIREMENT')
            AND complaint_event_id_valid(event_id))
        OR (event_kind IN ('INSTALLATION_MANIFEST', 'TEST_RUN_PURGE') AND test_only AND complaint_event_id_valid(event_id))
    ),
    CONSTRAINT chk_complaint_scan_entry_replay CHECK (replay_state IN ('PENDING', 'APPLIED', 'VERIFIED_ONLY', 'RETIRED'))
);
CREATE INDEX idx_complaint_scan_entry_run ON complaint_journal_scan_entries (scan_id, pass, data_scope_id);
CREATE INDEX idx_complaint_scan_entry_replay ON complaint_journal_scan_entries (scan_id, pass, replay_state);
CREATE INDEX idx_complaint_scan_entry_scope ON complaint_journal_scan_entries (data_scope_id, scan_id, pass);

CREATE TABLE complaint_import_runs (
    id uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    snapshot_hash bytea NOT NULL,
    collection_code varchar(32) COLLATE "C" NOT NULL,
    key_id varchar(128) COLLATE "C" NOT NULL,
    tool_version varchar(128) COLLATE "C" NOT NULL,
    configuration_hash bytea NOT NULL,
    cutoff_at timestamptz NOT NULL,
    document_count integer NOT NULL,
    accepted_count integer NOT NULL,
    rejected_count integer NOT NULL,
    accounted_count integer NOT NULL,
    staged_bytes bigint NOT NULL,
    mapping_root bytea,
    mapping_count integer,
    catalog_generation bigint,
    catalog_hash bytea,
    state varchar(16) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    sealed_at timestamptz,
    promoted_at timestamptz,
    aborted_at timestamptz,
    CONSTRAINT pk_complaint_import_runs PRIMARY KEY (id),
    CONSTRAINT uq_complaint_import_scope UNIQUE (id, data_scope_id),
    CONSTRAINT uq_complaint_import_snapshot UNIQUE (snapshot_hash),
    CONSTRAINT chk_complaint_import_scope CHECK (complaint_scope_valid(data_scope_id, test_only) AND NOT test_only),
    CONSTRAINT chk_complaint_import_identity CHECK (
        complaint_is_v4(id) AND complaint_digest_valid(snapshot_hash) AND complaint_ascii_valid(collection_code, 32)
        AND complaint_ascii_valid(key_id, 128) AND complaint_ascii_valid(tool_version, 128)
        AND complaint_digest_valid(configuration_hash)
    ),
    CONSTRAINT chk_complaint_import_counts CHECK (
        document_count BETWEEN 0 AND 200000 AND accepted_count BETWEEN 0 AND document_count
        AND rejected_count BETWEEN 0 AND document_count AND accounted_count BETWEEN 0 AND document_count
        AND accounted_count::bigint = accepted_count::bigint + rejected_count::bigint
        AND staged_bytes BETWEEN 0 AND 268435456
    ),
    CONSTRAINT chk_complaint_import_mapping CHECK ((
        (mapping_root IS NULL AND mapping_count IS NULL)
        OR (complaint_digest_valid(mapping_root) AND mapping_count = accepted_count)
    ) IS TRUE),
    CONSTRAINT chk_complaint_import_catalog CHECK ((
        (catalog_generation IS NULL AND catalog_hash IS NULL)
        OR (catalog_generation BETWEEN 1 AND 65536 AND complaint_digest_valid(catalog_hash))
    ) IS TRUE),
    CONSTRAINT chk_complaint_import_state CHECK ((
        (state = 'STAGING' AND sealed_at IS NULL AND promoted_at IS NULL AND aborted_at IS NULL)
        OR (state = 'SEALED' AND sealed_at IS NOT NULL AND promoted_at IS NULL AND aborted_at IS NULL
            AND rejected_count = 0 AND accounted_count = document_count AND mapping_root IS NOT NULL)
        OR (state = 'PROMOTED' AND sealed_at IS NOT NULL AND promoted_at IS NOT NULL AND aborted_at IS NULL
            AND rejected_count = 0 AND accounted_count = document_count AND mapping_root IS NOT NULL AND catalog_generation IS NOT NULL)
        OR (state = 'ABORTED' AND aborted_at IS NOT NULL AND promoted_at IS NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_import_times CHECK (complaint_finite_times(cutoff_at, created_at, sealed_at, promoted_at, aborted_at))
);
CREATE UNIQUE INDEX uq_complaint_import_nonterminal ON complaint_import_runs ((1)) WHERE state IN ('STAGING', 'SEALED');
CREATE UNIQUE INDEX uq_complaint_import_promoted ON complaint_import_runs ((1)) WHERE state = 'PROMOTED';

CREATE TABLE complaint_import_staging (
    run_id uuid NOT NULL,
    record_index integer NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    collection_code varchar(32) COLLATE "C" NOT NULL,
    key_id varchar(128) COLLATE "C" NOT NULL,
    document_hmac bytea NOT NULL,
    payload_hash bytea NOT NULL,
    normalized_bytes bytea NOT NULL,
    normalized_hash bytea NOT NULL,
    assigned_id uuid,
    assigned_parent_id uuid,
    owner_fingerprint bytea,
    reconciliation_code varchar(64) COLLATE "C",
    state varchar(16) COLLATE "C" NOT NULL,
    rejection_code varchar(64) COLLATE "C",
    CONSTRAINT pk_complaint_import_staging PRIMARY KEY (run_id, record_index),
    CONSTRAINT uq_complaint_staging_identity UNIQUE (run_id, collection_code, key_id, document_hmac),
    CONSTRAINT uq_complaint_staging_assignment UNIQUE (run_id, assigned_id),
    CONSTRAINT fk_complaint_staging_run FOREIGN KEY (run_id, data_scope_id)
        REFERENCES complaint_import_runs (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_staging_scope CHECK (complaint_scope_valid(data_scope_id, test_only) AND NOT test_only),
    CONSTRAINT chk_complaint_staging_identity CHECK (
        record_index BETWEEN 0 AND 199999 AND complaint_ascii_valid(collection_code, 32)
        AND complaint_ascii_valid(key_id, 128) AND complaint_digest_valid(document_hmac)
        AND complaint_digest_valid(payload_hash) AND complaint_bytes_match(normalized_bytes, normalized_hash, 65536)
        AND (owner_fingerprint IS NULL OR complaint_digest_valid(owner_fingerprint))
    ),
    CONSTRAINT chk_complaint_staging_state CHECK ((
        (state = 'ACCEPTED' AND complaint_is_v4(assigned_id) AND rejection_code IS NULL
            AND reconciliation_code IN ('MAPPED', 'AMBIGUOUS_NOTICE_PARENT')
            AND (assigned_parent_id IS NULL OR (complaint_is_v4(assigned_parent_id) AND assigned_parent_id <> assigned_id))
            AND (reconciliation_code <> 'AMBIGUOUS_NOTICE_PARENT' OR assigned_parent_id IS NULL))
        OR (state = 'REJECTED' AND assigned_id IS NULL AND assigned_parent_id IS NULL
            AND reconciliation_code IS NULL AND complaint_ascii_valid(rejection_code, 64))
    ) IS TRUE)
);
CREATE INDEX idx_complaint_staging_run ON complaint_import_staging (run_id, data_scope_id);

CREATE TABLE complaint_import_artifacts (
    run_id uuid NOT NULL,
    artifact_kind varchar(16) COLLATE "C" NOT NULL,
    chunk_index integer NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    object_key text COLLATE "C" NOT NULL,
    semantic_hash bytea NOT NULL,
    plaintext_bytes bigint NOT NULL,
    context_bytes bytea NOT NULL,
    context_hash bytea NOT NULL,
    expected_copies_bytes bytea NOT NULL,
    expected_copies_hash bytea NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    created_at timestamptz NOT NULL,
    object_version text COLLATE "C",
    ciphertext_hash bytea,
    retain_until timestamptz,
    primary_evidence_bytes bytea,
    primary_evidence_hash bytea,
    replica_evidence_bytes bytea,
    replica_evidence_hash bytea,
    verified_at timestamptz,
    CONSTRAINT pk_complaint_import_artifacts PRIMARY KEY (run_id, artifact_kind, chunk_index),
    CONSTRAINT uq_complaint_import_artifact_key UNIQUE (object_key),
    CONSTRAINT fk_complaint_artifact_run FOREIGN KEY (run_id, data_scope_id)
        REFERENCES complaint_import_runs (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT chk_complaint_artifact_scope CHECK (complaint_scope_valid(data_scope_id, test_only) AND NOT test_only),
    CONSTRAINT chk_complaint_artifact_shape CHECK (
        ((artifact_kind = 'EXPORT' AND chunk_index = 0 AND plaintext_bytes BETWEEN 0 AND 268435456)
            OR (artifact_kind = 'RESTORE_MAP' AND chunk_index BETWEEN 0 AND 262143 AND plaintext_bytes BETWEEN 1 AND 262144))
        AND complaint_ascii_valid(object_key, 1024) AND complaint_digest_valid(semantic_hash)
        AND complaint_bytes_match(context_bytes, context_hash, 4096)
        AND complaint_bytes_match(expected_copies_bytes, expected_copies_hash, 4096)
    ),
    CONSTRAINT chk_complaint_artifact_state CHECK ((
        (state = 'PREPARED' AND object_version IS NULL AND ciphertext_hash IS NULL AND retain_until IS NULL
            AND primary_evidence_bytes IS NULL AND primary_evidence_hash IS NULL
            AND replica_evidence_bytes IS NULL AND replica_evidence_hash IS NULL AND verified_at IS NULL)
        OR (state = 'VERIFIED' AND complaint_opaque_valid(object_version, 1024) AND object_version <> 'null'
            AND complaint_digest_valid(ciphertext_hash) AND retain_until IS NOT NULL
            AND complaint_bytes_match(primary_evidence_bytes, primary_evidence_hash, 65536)
            AND complaint_bytes_match(replica_evidence_bytes, replica_evidence_hash, 65536) AND verified_at IS NOT NULL)
    ) IS TRUE),
    CONSTRAINT chk_complaint_artifact_times CHECK (complaint_finite_times(created_at, retain_until, verified_at))
);
CREATE INDEX idx_complaint_artifact_run ON complaint_import_artifacts (run_id, data_scope_id);

CREATE TABLE complaint_legacy_records (
    collection_code varchar(32) COLLATE "C" NOT NULL,
    key_id varchar(128) COLLATE "C" NOT NULL,
    document_hmac bytea NOT NULL,
    payload_hash bytea NOT NULL,
    run_id uuid NOT NULL,
    data_scope_id uuid NOT NULL,
    test_only boolean NOT NULL,
    assigned_id uuid NOT NULL,
    assigned_parent_id uuid,
    complaint_ref uuid,
    reconciliation_code varchar(64) COLLATE "C" NOT NULL,
    state varchar(16) COLLATE "C" NOT NULL,
    cutoff_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    CONSTRAINT pk_complaint_legacy_records PRIMARY KEY (collection_code, key_id, document_hmac),
    CONSTRAINT uq_complaint_legacy_assignment UNIQUE (assigned_id, data_scope_id),
    CONSTRAINT fk_complaint_legacy_run FOREIGN KEY (run_id, data_scope_id)
        REFERENCES complaint_import_runs (id, data_scope_id) ON DELETE RESTRICT,
    CONSTRAINT fk_complaint_legacy_content FOREIGN KEY (complaint_ref, data_scope_id)
        REFERENCES complaints (id, data_scope_id) ON DELETE SET NULL (complaint_ref),
    CONSTRAINT chk_complaint_legacy_scope CHECK (complaint_scope_valid(data_scope_id, test_only) AND NOT test_only),
    CONSTRAINT chk_complaint_legacy_identity CHECK (
        complaint_ascii_valid(collection_code, 32) AND complaint_ascii_valid(key_id, 128)
        AND complaint_digest_valid(document_hmac) AND complaint_digest_valid(payload_hash) AND complaint_is_v4(assigned_id)
        AND (complaint_ref IS NULL OR complaint_ref = assigned_id)
        AND (assigned_parent_id IS NULL OR (complaint_is_v4(assigned_parent_id) AND assigned_parent_id <> assigned_id))
    ),
    CONSTRAINT chk_complaint_legacy_state CHECK (
        state IN ('PROMOTED', 'RESTORED') AND reconciliation_code IN ('MAPPED', 'AMBIGUOUS_NOTICE_PARENT')
        AND (reconciliation_code <> 'AMBIGUOUS_NOTICE_PARENT' OR assigned_parent_id IS NULL)
    ),
    CONSTRAINT chk_complaint_legacy_expiry CHECK (
        expires_at = ((cutoff_at AT TIME ZONE 'UTC') + interval '13 months') AT TIME ZONE 'UTC'
        AND complaint_finite_times(cutoff_at, expires_at, created_at)
    )
);
CREATE INDEX idx_complaint_legacy_run ON complaint_legacy_records (run_id, data_scope_id);
CREATE INDEX idx_complaint_legacy_content ON complaint_legacy_records (complaint_ref, data_scope_id);
CREATE INDEX idx_complaint_legacy_expiry ON complaint_legacy_records (expires_at);

-- Shared extensions preserve ordinary source/auth audit rows and the original source proof scope.
-- These nullable columns do not make the old audit adapter safe for a dedicated deletion pool;
-- explicit transaction/capacity-aware writers are a separate implementation gate.
ALTER TABLE audit_log
    ADD COLUMN complaint_data_scope_id uuid,
    ADD COLUMN complaint_actor_kind varchar(16) COLLATE "C",
    ADD CONSTRAINT chk_audit_complaint_shape CHECK ((
        (left(action, 10) COLLATE "C" <> 'COMPLAINT_' AND complaint_data_scope_id IS NULL AND complaint_actor_kind IS NULL)
        OR (action COLLATE "C" IN ('COMPLAINT_CREATED', 'COMPLAINT_REPLIED', 'COMPLAINT_CONTENT_EDITED',
                'COMPLAINT_STATUS_CHANGED', 'COMPLAINT_CLOSED', 'COMPLAINT_DELETE_AUTHORIZED', 'COMPLAINT_DELETED',
                'COMPLAINT_INSTALLATION_ENROLLED', 'COMPLAINT_INSTALLATION_DELETE_AUTHORIZED',
                'COMPLAINT_INSTALLATION_DELETED', 'COMPLAINT_INSTALLATION_RETIRED',
                'COMPLAINT_RETENTION_AUTHORIZED', 'COMPLAINT_RETENTION_APPLIED', 'COMPLAINT_RECOVERY_APPLIED',
                'COMPLAINT_RECOVERY_CONFLICT', 'COMPLAINT_IMPORT_SEALED', 'COMPLAINT_IMPORT_PROMOTED',
                'COMPLAINT_TEST_RUN_ACTIVATED', 'COMPLAINT_TEST_RUN_SEALED', 'COMPLAINT_TEST_RUN_PURGED',
                'COMPLAINT_CATALOG_PROJECTED', 'COMPLAINT_JOURNAL_RETIREMENT_AUTHORIZED', 'COMPLAINT_JOURNAL_RETIRED',
                'COMPLAINT_CAPACITY_RECONCILED', 'COMPLAINT_RESTORE_RECONCILED')
            AND complaint_scope_valid(complaint_data_scope_id, complaint_data_scope_id <> '00000000-0000-0000-0000-000000000000'::uuid)
            AND ((complaint_actor_kind = 'ADMIN' AND actor_user_id IS NOT NULL)
                OR (complaint_actor_kind IN ('INSTALLATION', 'SYSTEM') AND actor_user_id IS NULL)))
    ) IS TRUE);
CREATE INDEX idx_audit_actor ON audit_log (actor_user_id);
CREATE INDEX idx_audit_complaint_scope ON audit_log (complaint_data_scope_id, created_at, id)
    WHERE complaint_data_scope_id IS NOT NULL;

ALTER TABLE admin_step_up_grants DROP CONSTRAINT chk_admin_step_up_scope;
ALTER TABLE admin_step_up_grants ADD CONSTRAINT chk_admin_step_up_scope
    CHECK (scope IN ('source-admin-mutation', 'complaint-moderation-mutation'));
