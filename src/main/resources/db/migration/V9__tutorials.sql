-- Backend-managed bilingual tutorial publishing and immutable media.

CREATE TABLE tutorial_categories (
    id                    uuid         NOT NULL,
    slug                  varchar(64)  NOT NULL,
    status                varchar(16)  NOT NULL DEFAULT 'DRAFT',
    position              integer      NOT NULL,
    published_revision_id uuid         NULL,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    CONSTRAINT pk_tutorial_categories PRIMARY KEY (id),
    CONSTRAINT uq_tutorial_categories_slug UNIQUE (slug),
    CONSTRAINT uq_tutorial_categories_position UNIQUE (position),
    CONSTRAINT chk_tutorial_categories_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_tutorial_categories_position CHECK (position >= 0)
);

CREATE TABLE tutorial_category_revisions (
    id              uuid        NOT NULL,
    category_id     uuid        NOT NULL,
    revision_number integer     NOT NULL,
    content         jsonb       NOT NULL,
    created_by      uuid        NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_tutorial_category_revisions PRIMARY KEY (id),
    CONSTRAINT uq_tutorial_category_revision UNIQUE (category_id, revision_number),
    CONSTRAINT uq_tutorial_category_revision_owner UNIQUE (category_id, id),
    CONSTRAINT chk_tutorial_category_revision_number CHECK (revision_number > 0),
    CONSTRAINT fk_tutorial_category_revision_category
        FOREIGN KEY (category_id) REFERENCES tutorial_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tutorial_category_revision_creator
        FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);

ALTER TABLE tutorial_categories
    ADD CONSTRAINT fk_tutorial_category_published_revision
        FOREIGN KEY (id, published_revision_id) REFERENCES tutorial_category_revisions (category_id, id) ON DELETE RESTRICT;

CREATE TABLE tutorial_media (
    id               uuid          NOT NULL,
    storage_filename varchar(96)   NOT NULL,
    content_type     varchar(16)   NOT NULL,
    byte_size        bigint        NOT NULL,
    width            integer       NOT NULL,
    height           integer       NOT NULL,
    sha256           char(64)      NOT NULL,
    published        boolean       NOT NULL DEFAULT false,
    created_by       uuid          NULL,
    created_at       timestamptz   NOT NULL,
    CONSTRAINT pk_tutorial_media PRIMARY KEY (id),
    CONSTRAINT uq_tutorial_media_filename UNIQUE (storage_filename),
    CONSTRAINT chk_tutorial_media_content_type CHECK (content_type IN ('image/jpeg', 'image/png')),
    CONSTRAINT chk_tutorial_media_byte_size CHECK (byte_size > 0 AND byte_size <= 4194304),
    CONSTRAINT chk_tutorial_media_dimensions CHECK (
        width > 0 AND height > 0 AND width <= 4096 AND height <= 4096 AND width::bigint * height::bigint <= 16000000
    ),
    CONSTRAINT chk_tutorial_media_sha256 CHECK (sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT fk_tutorial_media_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);

CREATE TABLE tutorials (
    id                    uuid         NOT NULL,
    slug                  varchar(96)  NOT NULL,
    status                varchar(16)  NOT NULL DEFAULT 'DRAFT',
    position              integer      NOT NULL,
    featured_position     integer      NULL,
    published_revision_id uuid         NULL,
    created_at            timestamptz  NOT NULL,
    updated_at            timestamptz  NOT NULL,
    CONSTRAINT pk_tutorials PRIMARY KEY (id),
    CONSTRAINT uq_tutorials_slug UNIQUE (slug),
    CONSTRAINT uq_tutorials_position UNIQUE (position),
    CONSTRAINT uq_tutorials_featured_position UNIQUE (featured_position),
    CONSTRAINT chk_tutorials_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    CONSTRAINT chk_tutorials_position CHECK (position >= 0),
    CONSTRAINT chk_tutorials_featured_position CHECK (featured_position IS NULL OR featured_position >= 0)
);

CREATE TABLE tutorial_revisions (
    id              uuid        NOT NULL,
    tutorial_id     uuid        NOT NULL,
    revision_number integer     NOT NULL,
    category_id     uuid        NOT NULL,
    content         jsonb       NOT NULL,
    created_by      uuid        NULL,
    created_at      timestamptz NOT NULL,
    CONSTRAINT pk_tutorial_revisions PRIMARY KEY (id),
    CONSTRAINT uq_tutorial_revision UNIQUE (tutorial_id, revision_number),
    CONSTRAINT uq_tutorial_revision_owner UNIQUE (tutorial_id, id),
    CONSTRAINT chk_tutorial_revision_number CHECK (revision_number > 0),
    CONSTRAINT fk_tutorial_revision_tutorial FOREIGN KEY (tutorial_id) REFERENCES tutorials (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tutorial_revision_category FOREIGN KEY (category_id) REFERENCES tutorial_categories (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tutorial_revision_creator FOREIGN KEY (created_by) REFERENCES users (id) ON DELETE RESTRICT
);

ALTER TABLE tutorials
    ADD CONSTRAINT fk_tutorial_published_revision
        FOREIGN KEY (id, published_revision_id) REFERENCES tutorial_revisions (tutorial_id, id) ON DELETE RESTRICT;

CREATE TABLE tutorial_revision_media (
    revision_id uuid        NOT NULL,
    media_id    uuid        NOT NULL,
    slot_key    varchar(128) NOT NULL,
    CONSTRAINT pk_tutorial_revision_media PRIMARY KEY (revision_id, slot_key),
    CONSTRAINT uq_tutorial_revision_media_slot_asset UNIQUE (revision_id, slot_key, media_id),
    CONSTRAINT fk_tutorial_revision_media_revision FOREIGN KEY (revision_id) REFERENCES tutorial_revisions (id) ON DELETE RESTRICT,
    CONSTRAINT fk_tutorial_revision_media_asset FOREIGN KEY (media_id) REFERENCES tutorial_media (id) ON DELETE RESTRICT
);

CREATE INDEX idx_tutorial_category_revisions_category ON tutorial_category_revisions (category_id, revision_number DESC);
CREATE INDEX idx_tutorial_revisions_tutorial ON tutorial_revisions (tutorial_id, revision_number DESC);
CREATE INDEX idx_tutorial_revisions_category ON tutorial_revisions (category_id);
CREATE INDEX idx_tutorial_revision_media_asset ON tutorial_revision_media (media_id);
CREATE INDEX idx_tutorial_media_created_at ON tutorial_media (created_at DESC);
