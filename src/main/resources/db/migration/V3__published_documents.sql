-- V3__published_documents.sql — the materialized whole-document snapshots served to the app, plus the
-- global publication-serialization singleton (PLAN §5 / §9).
--
-- The served artifact is a materialized snapshot (not assembled per request) so that ETag/checksum are
-- stable stored bytes and whole-document anti-rollback ("revision only grows") is a single DB sequence.

-- Monotonic document-revision source. START WITH 100 (= kira.config.minimum-server-revision default):
-- the first generated value IS 100 and is legal. Gaps are NORMAL and documented (values consumed by
-- rolled-back transactions are not returned) — revisions are unique and strictly increasing but NOT
-- contiguous; nothing may assume contiguity (PLAN §5). Hibernate never sees this sequence (no entity
-- uses a SEQUENCE generator — document_revision is assigned by the app via nextval in Phase 6), so
-- ddl-auto=validate does not touch it.
CREATE SEQUENCE seq_document_revision START WITH 100 INCREMENT BY 1 MINVALUE 100 NO CYCLE;

-- published_documents — immutable served snapshots. document_json holds the EXACT canonical bytes
-- served (text, not jsonb — deliberately, PLAN §5). created_at has NO DB default: the application sets
-- it to the SAME injected-Clock instant serialized as the document's generatedAt (PLAN §9 steps 7-8).
CREATE TABLE published_documents (
    id                uuid        NOT NULL,
    document_revision bigint      NOT NULL,   -- from seq_document_revision (monotonic, never reused)
    schema_version    int         NOT NULL,
    document_json     text        NOT NULL,   -- the exact canonical bytes served
    checksum          char(64)    NOT NULL,   -- SHA-256 of document_json = the ETag
    canon_version     varchar(16) NOT NULL,   -- the canonicalization algorithm ('kcj-1' in v1)
    source_count      int         NOT NULL,
    created_by        uuid        NOT NULL,
    created_at        timestamptz NOT NULL,   -- no DB default (PLAN §5/§9): = document generatedAt instant
    notes             text        NULL,
    CONSTRAINT pk_published_documents PRIMARY KEY (id),
    -- UNIQUE so document_publication_state.latest_document_revision can reference it (below).
    CONSTRAINT uq_published_documents_revision UNIQUE (document_revision),
    CONSTRAINT fk_published_documents_created_by
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);

-- document_publication_state — the GLOBAL publication-serialization lock (PLAN §9): every
-- state-visible document mutation begins by locking this singleton row FOR UPDATE. It is ALSO the one
-- authoritative latest-document pointer (latest_document_revision) — every "latest" read resolves
-- through this single-row read; MAX(document_revision) is NEVER a read path (it appears only inside the
-- startup consistency check). The pointer FK -> published_documents(document_revision) can never dangle:
-- the snapshot row is inserted before the pointer moves, in the same transaction (PLAN §9 steps 8-9).
CREATE TABLE document_publication_state (
    id                       int         NOT NULL,
    latest_document_revision bigint      NULL,   -- NULL until first publish
    updated_at               timestamptz NOT NULL,
    CONSTRAINT pk_document_publication_state PRIMARY KEY (id),
    CONSTRAINT chk_document_publication_state_singleton CHECK (id = 1),
    CONSTRAINT fk_publication_state_latest
        FOREIGN KEY (latest_document_revision)
        REFERENCES published_documents (document_revision) ON DELETE RESTRICT
);

-- Seed the singleton row (PLAN §5 — seeded by V3). Pointer starts NULL (fresh install, no snapshots).
INSERT INTO document_publication_state (id, latest_document_revision, updated_at)
    VALUES (1, NULL, now());
