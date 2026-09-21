# Explicit registered TEST HTTP startup v1

**Source-authored only: NOT_COMPILED / NOT_RUN. Not activation or deployment authority.**

`ComplaintTestProcessAssemblyV1.beginRegisteredHttpStartup(registration)` retains one
`ComplaintTestRegisteredHttpStartupV1` before JPA or servlet initialization. Selection, `start()`,
`localPort` and `close()` require the original assembly caller. The registration must belong to
that exact process, have actually released identity admission, and have the born-with initial
checkpoint CREATE policy. No desired-only document or historical `Completed` replaces this.

## Fixed context, not the full application

- `start()` constructs the actual original `LocalContainerEntityManagerFactoryBean` over the
  already-registered ordinary pool, validates the User/Audit schema without migrations, and builds
  its guarded JPA transaction manager, pool-sized ordinary admission, ownership and fixed JDBC
  template. It binds that exact pair once and uses the existing counted JPA audit adapter.
- The context receives these exact instances before conditional HTTP configuration evaluation.
  Its user JWT key is the original acquired provider, with that provider's bound settings, not a
  second environment key. There is no supplied context, replacement pool, EMF or audit factory.
- A real embedded Tomcat/DispatcherServlet listener binds **HTTP `127.0.0.1`, ephemeral port**.
  `localPort` reports its location only. No wildcard/public listener or TLS deployment is provided.
- Only `GET /api/v1/installations/bootstrap`, `POST /api/v1/installations`,
  `POST /api/v1/installations/session`, `POST /api/v1/complaints` and
  `POST /api/v1/complaint-operations/status` are selected. A deny-only pre-buffer namespace filter
  excludes every other path; the original bridge/admission and security dispatcher still govern
  every selected request. Unsupported methods remain denied.
- Existing `SecurityConfig` is composed, including its user chain, but this standalone context
  **does not host account/source/Admin routes**. There is no application/component scan. Normal
  `KiraBackendApplication` startup and its closed complaint boundary are unchanged. Full-app
  startup/coexistence remains an unfinished requirement, not satisfied by this explicit context.

## Custody and shutdown

The original assembly retains the child throughout its lifetime. Spring has no inferred destroy
method for borrowed persistence/key instances. The child never closes the native pool, assembly
or public trust. Their original assembly owns that later destruction.

One close attempt irreversibly stops the original ingress, revokes the registration, and observes
**both** zero original ingress reservations/contexts and zero original ordinary owners before
closing the context or EMF. Stopping admission is not draining/cancelling existing work and does
not erase previously issued JWTs. Shutdown need not finish delivering an HTTP response.

After actual close calls return, context inactivity, Tomcat `DESTROYED`, and a closed initialized
EMF are checked. Failed/unreturned initialization or close cannot establish a reusable/closed
owner. A refused ordinary-pair bind may have an earlier JPA owner: disposal of this startup's own
factory cannot prove that owner released. That uncertainty retains assembly/native custody too;
there is no inspection, rebind or takeover of the earlier pair.

Initialization has one 60-second elapsed budget starting at child construction. Direct runtime
close has one 10-second attempt; assembly shutdown supplies its **already-started** budget instead.
Timeouts/unknown outcomes retain handles and fail closed, never renew waits, retry destruction or
permit native/trust teardown. Elapsed checks are not cancellation or physical-disposal proof.

## Verification boundary

Authored selectors in the existing `TestRegisteredInitialCheckpointCreateIT` cover real TCP
identity exchange/counting, missing-checkpoint refusal, genuine native seal/checkpoint then
CREATE/replay/status, original-caller/resource identity, held-ingress drain before JPA closure,
and original assembly teardown of a still-running retained child. The separate
`ComplaintRegisteredStartupAdmissionTest` covers irreversible stop versus actual context release.
All are **NOT_COMPILED / NOT_RUN**; there is no runtime or fault-qualification claim.

Unreturned/failed JPA or servlet initialization/closure and drain timeouts still need separately
authorized custody-safe fault qualification. An in-process fixture must not use fallback native
teardown to turn an unproved child into a successful cleanup result.

`ComplaintTestProcessAssemblyV1.begin()` still selects `UNKNOWN`. This startup does not call
`GuardedDataSource.start()` or authorize business readiness; positive test source uses only the
existing controlled fixture launch. No LIVE support, checkpoint gate opening, broader lifecycle,
provider deadline relaxation, normal-app activation or execution readiness is introduced.
