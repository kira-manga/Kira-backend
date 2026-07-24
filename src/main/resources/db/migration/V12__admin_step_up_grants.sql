-- Short-lived, one-time proof that an authenticated ADMIN re-entered the account password before a
-- catalog mutation. Only SHA-256 token hashes are stored; the bearer proof itself is returned once.
CREATE TABLE admin_step_up_grants (
    id          uuid         NOT NULL,
    user_id     uuid         NOT NULL,
    token_hash  char(64)     NOT NULL,
    scope       varchar(64)  NOT NULL,
    created_at  timestamptz  NOT NULL,
    expires_at  timestamptz  NOT NULL,
    used_at     timestamptz  NULL,
    CONSTRAINT pk_admin_step_up_grants PRIMARY KEY (id),
    CONSTRAINT uq_admin_step_up_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_admin_step_up_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE RESTRICT,
    CONSTRAINT chk_admin_step_up_scope CHECK (scope = 'source-admin-mutation'),
    CONSTRAINT chk_admin_step_up_expiry CHECK (expires_at > created_at),
    CONSTRAINT chk_admin_step_up_token_hash CHECK (token_hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_admin_step_up_user_expiry
    ON admin_step_up_grants (user_id, expires_at DESC);
