# Registered TEST installation bootstrap — source candidate

This implements the bounded TEST bootstrap connection from App29 v6 §§4.1/4.4. It is **not
activation, deployment readiness, LIVE support, or execution evidence**. The normal application
does not create a registered composition bean; its complaint routes still return404 before body
buffering, bearer authentication, or database access. Backend operational activation remains closed.

## Explicit composition, not configuration authority

`ComplaintTestBootstrapHttpCompositionV1.fromRegistered` accepts only the existing privately issued
`ComplaintTestNamespaceRegistrationV1` and its original ordinary `PersistencePhaseOwnership` /
`JdbcTemplate` pair. Registration requires the actual same-process first PROJECT and the target's
primary/replica raw readback. Cold full-D construction, an ACTIVE row, a matching digest, a
`PROVENANCE_REQUIRED` diagnostic, or a Boolean cannot construct this registration.

The selected assembly must register that concrete bean **before**
`ComplaintTestBootstrapHttpConfigurationV1` is evaluated. Its conditional `@Order(1)` installation
chain and exact `SimpleUrlHandlerMapping` dispatch through the original `ComplaintHttpIngressBridge`.
`WebDiagnosticsConfig` places that same bridge before the body-size filter; it validates the fixed
bodyless request inside ingress before generic buffering. No standalone producer is mapped.
Absent assembly preserves the no-argument/default disabled filter. The bootstrap-only composition
does not enable enrollment, sessions, `/me`, delete-all, owner, operation-status, or Admin routes.
Unrelated user/Admin routes retain their qualified `@Order(2)` chain.

## Wire and admission

- Exact public `GET /api/v1/installations/bootstrap`; no query or body. Supplied bearer credentials,
  including malformed/duplicate credentials, are ignored. There is no user JWT/UserRepository work.
- Wrong verb/path404; invalid/duplicate framing or contract headers400; non-identity encoding415.
  Actual EOF is checked before any database read. HEAD errors contain no body; no redirects.
- Original per-process ingress precedes validation. The existing `chargeBootstrap` is consumed once
  before checkout:120 trusted-IP attempts/hour, existing current/previous HMAC overlap, bounded
  cardinality and pruning. This retained TEST profile is memory/one-instance, not distributed Redis.
- Actual quota exhaustion returns429 with the existing maximum required `Retry-After`.
  Unavailable admission, resource/registration/current-state uncertainty and allocation failure
  return bounded503, never a fabricated quota result or fallback scope.
- Success is exactly `{"dataScopeId":"<registered canonical TEST UUID>","contractVersion":1}`.
  Success/problems carry `X-Kira-Complaint-Contract: 1` and `Cache-Control: no-store, no-transform`,
  identity encoding, and at most16KiB. No credential, installation identifier, mode, ETag or Location.

## Actual read and release

The adapter retains the original process, checks local registration/full-D/resource identity,
charges admission outside SQL, and enters the existing read-only installation-current-state phase.
One bounded joined statement compares the exact registered run, TEST control, and global current
catalog/control singleton against the retained activation generation/envelope, configuration,
database/restore/writer/trust identities and accepted catalog. Missing/different, stale or
SEALED/PURGING/PURGED rows refuse. Maintenance/creation/scan flags and ordinary journal health are
not bootstrap gates; bootstrap performs no ordinary journal/provider operation.

Only that concrete retained read may return its scope, once, after the existing phase proves known
commit and physical/resource release. Registration is checked again after release and before the
adapter returns; the handler rechecks the original ingress before delivery. The old assessment
path stays diagnostic and cannot mint bootstrap success. No new phase, lifecycle, permit or quota
registry is introduced. The added boundary identity check rejects another phase owner even on the
same pool. Reads do not update leases, timestamps, counters, audits or credentials.

The observation is one committed snapshot, not a reusable enrollment grant. A concurrent seal can
make the retained TEST tuple stale; enrollment must still lock/recheck its exact scope. The tuple is
never rewritten or silently rebound to LIVE. This bootstrap-only mount leaves enrollment closed.

## Intentionally unimplemented / verification required

There is **no LIVE bootstrap producer**. LIVE needs its own concrete retained current
catalog/projection, installed full-D, and restore/replay release authority, plus the existing
TEST-contamination/terminal-evidence checks. Cold LIVE settings or SQL equality cannot substitute.
Restored/quarantined deployments have no public registered bootstrap assembly. A changed restore
or accepted-catalog identity makes an already selected stale TEST process refuse503; this code
does not invent authority renewal, restart recovery, or a current-catalog advancement bypass.

Focused handler/admission/default-Spring tests and real PROJECT/readback/TLS integration delegates
are authored. They include current-row drift, original resources, known/unknown commit and release
failures, revocation, and terminal old-scope preservation. They are **NOT_RUN in this authoring
candidate**. Independent frozen-source review and a separately authorized verification gate remain
required; no source milestone grants build, service, CI, deployment or activation permission.
