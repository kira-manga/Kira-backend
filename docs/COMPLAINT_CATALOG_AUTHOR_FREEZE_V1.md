# Fixed catalog-author freeze core (internal)

This is a **programmatic, non-web core**, not an executable release command. No CLI/manifest DSL,
startup bean, HTTP endpoint, grant/migration execution, deployment, PUT, first-D invocation or TARGET
finalizer is included. App29 and production qualification remain incomplete.

## Entry and actual ownership

Call `CatalogGenesisFreezeV1.begin()` before owned input, secret or custody I/O, then exactly one
`freeze(request, secretCredentials, signingCredentials, primaryReadCredentials, replicaReadCredentials)`
or `resume(...)`. All four credentials are explicit AWS sessions; no default provider chain is used.
The fixed original 60-second budget includes actual bounded file acquisition, exact-version Secrets
Manager acquisition, driver/pool preparation, SQL, namespace listings, KMS Sign, durable custody and
cleanup. Finite cooperative/SDK limits do not guarantee hard DNS/native/filesystem cancellation.

The request contains file **paths**, not an assertion that acquisition was already completed.
Intent/T0/Tn are read inside this owner. `approvalInputs` contains the exact existing canonical JSON
list of `OfflineCatalogGenesisApprovalV1` from the intent; this comparison does not authenticate
human approval. The immutable KMS ARN/key/SPKI pin must match the raw-verified current trust signer.
The independent 32-byte capacity-policy digest is the existing SQL ledger comparison/accounting
input; this core does **not** parse raw deployment P or claim TARGET P/D verification. The genuine
TARGET graph remains the responsibility of the existing desired/first-D assembly and later callers.

The author DB password is acquired for the fixed `kira_complaint_catalog_operator` login. Its named
root retains its original driver/scanner/shared Timer and one coordinator; ordinary/deletion starts
are permanently barred and no rotation participant is enabled. SNAPSHOT/PREPARE/SIGNATURE require
the original live freeze attempt and remaining budget; legacy no-attempt entries and other phases
are refused. Each actual phase checks both `session_user` and `current_user`, and the exact database,
on its original connection. No TARGET credential, descriptor or D is replaced, and the separate
config operator/pristine/first-D routes are unchanged.

## Independently provisioned database privileges

Provision LOGIN/password-SCRAM and database CONNECT/schema USAGE externally, with no runtime/config
membership or inherited/PUBLIC/table-wide/default mutation grants. Existing `scripts/db/create-roles.sql`
is a broad runtime role example, not this narrow author profile.

- SELECT `complaint_journal_control`, `complaint_catalog_mutations`, `complaint_capacity_counters`.
- Control UPDATE **only `updated_at`** for PostgreSQL's existing control `FOR UPDATE` requirement;
  the freeze statements do not change that column or any desired/accepted/control field.
- History INSERT only the columns explicitly listed in `CatalogGenesisMutationSql.INSERT_GENESIS`:
  `operation_token,catalog_writer_generation,approval_bytes,approval_hash,unsigned_bytes,unsigned_hash,`
  `signer_one_id,signer_one_algorithm,object_key,created_at,operation_type,predecessor_generation,`
  `predecessor_hash,successor_generation,canonicalizer,signer_policy,state`.
- History UPDATE only `signer_one_signature,envelope_bytes,envelope_hash`.
- Counter UPDATE only `free_units,actual_units,test_reserved_units,updated_at`, matching the existing
  once-only prepaid G1 charge. No policy, hard-limit or configuration changes are granted.

Column grants do not encode PREPARED-only row/value predicates; fixed phase/store checks remain
essential. The config operator remains history SELECT-only. SET ROLE or a supplied manifest login
does not satisfy author authentication. Independently protect the credentials and other writers.

## Stable release custody and recovery

The independently provisioned Linux root must be outside SQL restore/deployment/temporary/CA
custody and independently bind this one release and namespace pair. Existing owner/mode/file-key,
exclusive lock, complete immutable leaves, file+directory force and exact reread checks apply.
Dedicated custodian/no-rollback recovery inputs, genuine approvals and old-writer fencing remain
external prerequisites; choosing a different root cannot be used to reset an unresolved release.

`FREEZE_ARMED` precedes PREPARE. A positive `FREEZE_PREPARED_NO_SIGNATURE` record is written only
after actual PREPARE commit/release and a fresh exact unsigned snapshot. `FREEZE_SIGN_ARMED` precedes
the genuine SDK Sign, after both genuine empty namespace listings and actual provider cleanup.
`FREEZE_SIGNATURE_PERSISTENCE_ARMED` binds the exact returned signature hash and is durable **before
the first local signature write or SQL signature CAS**. Exact signature bytes precede SQL; exact
envelope and `FREEZE_SIGNATURE_PERSISTED` follow its actual committed/released reread.

Only the complete original allocation/inputs and positive unsigned receipt, mandatory pre-effect
arm ordering, fresh exact unsigned SQL and fresh genuine namespace checks establish narrow
pre-freeze re-sign eligibility under those independent no-rollback/fencing assumptions. A missing
Sign outcome, restart or absent restored SQL alone does not. Supplied pin input, frozen bytes,
partial/conflicting inventory, or unresolved signature-persistence/pin-commitment arms prevent
re-sign. Once frozen, bytes are raw-verified and reused exactly, never replaced with another PSS
signature. Stable records have no changing retry clocks, approval signatures or verifier handles.

Without independently released post-sign pin input, the successful cleaned result is only
`SIGNED_AWAITING_RELEASE`. A later `resume` rereads an explicit independent 64-byte lowercase-hex
ASCII pin file (no newline), verifies it against the exact signed reread, durably records
`FREEZE_PIN_COMMITMENT_ARMED` before local pin commitment, then writes exact PIN/`FREEZE_OUTCOME`.
Only after all actual cleanup does it return `FROZEN`, a historical software result, not authority
to select D, PUT, finalize or activate runtime. A stored pin without fresh independent input is not
silently promoted into an invocation's release input. No computed hash is returned as pin authority.

Supported resume includes an exact complete retained signature with an exact unsigned/same-signed
PREPARED SQL row after uncertain signature SQL completion: raw verification then same-signature CAS
or no-op, never Sign. SQL absence, missing positive prepare receipt, an armed persistence with no
complete signature, or partial/conflicting custody requires external recovery; the core does not
recreate PREPARE, repair/delete files, manufacture evidence or claim those paths implemented.

Release records never prove total prior cleanup or current provider/DB state. Each invocation
revalidates raw inputs and owns its actual cleanup; failure/expiry/unknown close cannot report
success. The author-only phase cleanup uses a zero-remaining system-clock snapshot when expired
so original closes can still be attempted without renewed work/wait budget. Fatal errors retain
priority over cancellation, then interruption; freeze/resume emit cause-free cancellation/interrupt
signals, restore any interrupted flag even when a higher-priority signal wins, and keep cleanup
failure sticky regardless of the outward signal.

On failure the retained component is **failed/quarantined**, not a recoverable long-lived service.
An active phase quarantine can prevent file/custody close from dispatching at all. Its original
handles and exclusive lock remain retained; the sticky owner does not retry effects or turn later
retirement into an on-time cleanup receipt. Actual owning-process retirement is required for this
failure boundary. Enforcing one-shot CLI/owned-process failure retirement is **unfinished internal
integration work and a prerequisite to operational use**, not an externally blocked software task
or an implemented eventual in-process cleanup guarantee. Fixture teardown of the same previously
undispatched custody after genuine original lease/root retirement is only test disposal, never
production success or cleanup proof.

File durability on actual hardware, cloud/IAM policy and independent custody/approval need separate
qualification; fixture success cannot establish them.
