package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection

internal object PgRotationProbeCases {
    fun handles(mode: PgOpeningProbeCase): Boolean = mode in setOf(
        PgOpeningProbeCase.ROTATE_ORDINARY, PgOpeningProbeCase.ROTATE_CONJUNCTION, PgOpeningProbeCase.ROTATE_DELETION,
        PgOpeningProbeCase.ROTATE_HELD_AUX, PgOpeningProbeCase.ROTATE_NEXT_HOST, PgOpeningProbeCase.ROTATE_PREFER_E,
        PgOpeningProbeCase.ROTATE_REQUIRE_E, PgOpeningProbeCase.ROTATE_MISSING_CONTACT, PgOpeningProbeCase.ROTATE_BOUND_MODEL,
    )

    fun verify(mode: PgOpeningProbeCase) {
        check(handles(mode))
        PgRotationPeer(mode).use { peer ->
            peer.bind()
            val opening = PersistencePgDriverOpening.prepare(
                PersistenceDriverBootstrap.prepare(),
                endpoint(mode, peer),
                policy(mode),
                PersistencePathStyle.POSIX,
            )
            verifyPreparedOpening(mode, peer, opening)
        }
    }

    private fun verifyPreparedOpening(mode: PgOpeningProbeCase, peer: PgRotationPeer, opening: PersistencePgDriverOpening) {
        val witness = if (mode === PgOpeningProbeCase.ROTATE_REQUIRE_E) PgPrimaryIdentityWitness() else null
        var observation: PgRequireErrorObservation? = null
        val scope = PgOpeningProbeScope(opening)
        try {
            scope.use {
                if (witness != null) {
                    observation = PgRequireErrorObservation.install(checkNotNull(scope.binding.rendezvous.thread)) {
                        withPgCurrentPrimary(pgRotationOwner(checkNotNull(scope.retained.get())), witness::capture)
                    }
                }
                peer.start()
                val result = scope.request()
                if (mode === PgOpeningProbeCase.ROTATE_REQUIRE_E) {
                    verifyRequireFailure(scope, result, checkNotNull(witness), checkNotNull(observation))
                } else {
                    val success = result as? PersistenceFactoryResult.Success<PersistenceJdbcCandidate> ?: error("Synthetic rotation opening failed.")
                    val entry = scope.verifyRetained(success)
                    check(requireNotNull(entry.driverScope).extentSource.primaryOpeningEnded.get())
                    check(pgScopePhase(requireNotNull(entry.driverScope)).get() === PersistencePgScopePhase.ENDED)
                    val primary = pgRotationEntry(entry, PersistenceTransportRole.PRIMARY)
                    check(!primary.allCallsSealed && !primary.extentEnded)
                    val socket = primary.raw.get() as TrackedPersistenceSocket
                    check(socket.inetAddress != null && socket.supportedOptions().isNotEmpty())
                    PgRotationCalls(peer).use { calls -> verifyCancellations(mode, entry, peer, calls) }
                    requireNotNull(entry.raw.get()).close()
                }
                peer.verify()
            }
        } finally {
            // Scope cleanup, not request/idle, establishes actual worker exit before restoring the leaf.
            observation?.close()
        }
    }

    private fun verifyCancellations(mode: PgOpeningProbeCase, entry: PersistencePhysicalEntry, peer: PgRotationPeer, calls: PgRotationCalls) {
        val raw = requireNotNull(entry.raw.get())
        when (mode) {
            PgOpeningProbeCase.ROTATE_HELD_AUX -> heldCancellation(entry, peer, calls, raw)

            PgOpeningProbeCase.ROTATE_MISSING_CONTACT -> missingContact(entry, peer, raw)

            PgOpeningProbeCase.ROTATE_ORDINARY, PgOpeningProbeCase.ROTATE_CONJUNCTION, PgOpeningProbeCase.ROTATE_DELETION -> {
                pgCancel(raw)
                val first = verifyAuxiliary(entry, null, Thread.currentThread())
                val thread = calls.start { pgCancel(raw) }
                calls.await(thread)
                val second = verifyAuxiliary(entry, first, thread)
                pgCancel(raw)
                verifyAuxiliary(entry, second, Thread.currentThread())
            }

            else -> {
                pgCancel(raw)
                verifyAuxiliary(entry, null, Thread.currentThread())
                if (mode === PgOpeningProbeCase.ROTATE_BOUND_MODEL) PgBoundRotationModelCases.verify(entry)
            }
        }
        check(entry.raw.get() === raw)
        println("PG_ROTATION_STATE mode=${mode.name} source_exit=true primary_usable=true exact_membership=true proof=REAL_DRIVER+PROTOCOL_PEER")
    }

    private fun heldCancellation(entry: PersistencePhysicalEntry, peer: PgRotationPeer, calls: PgRotationCalls, raw: Connection) {
        val thread = calls.start { pgCancel(raw) }
        peer.awaitHeld()
        awaitPgFixtureFact {
            val snapshot = pgRotationOwner(entry).snapshot() as? PersistenceTransportSnapshot.Available
            snapshot?.auxiliary?.activeBusiness == 1 && thread.stackTrace.any { it.className == "sun.nio.ch.NioSocketImpl" && it.methodName == "implRead" }
        }
        val first = pgRotationEntry(entry, PersistenceTransportRole.AUX_CANCEL)
        val firstRaw = first.raw.get()
        requireHeld(first)
        pgCancel(raw) // Concurrent contender must return without a new socket; IOException is swallowed by pgjdbc.
        check(pgRotationEntry(entry, PersistenceTransportRole.AUX_CANCEL) === first && first.raw.get() === firstRaw)
        requireHeld(first)
        check(peer.cancellations == 1 && thread.isAlive)
        peer.releaseHeld()
        calls.await(thread)
        verifyAuxiliary(entry, null, thread)
        pgCancel(raw)
        val second = verifyAuxiliary(entry, first, Thread.currentThread())
        val later = calls.start { pgCancel(raw) }
        calls.await(later)
        verifyAuxiliary(entry, second, later)
        println("PG_ROTATION_HELD active_before=true active_after=true contender_allocated=false later_rotated=true")
    }

    private fun requireHeld(entry: PersistenceTransportEntry<*>) {
        check(!entry.extentEnded && !entry.allCallsSealed && !entry.businessSealed)
        check(entry.business.count { it != null } == 1 && entry.observations.all { it == null })
        check(entry.firstClose === PersistenceTransportClosePhase.NOT_STARTED)
    }

    private fun missingContact(entry: PersistencePhysicalEntry, peer: PgRotationPeer, raw: Connection) {
        peer.refuseCancellationConnection()
        pgCancel(raw)
        val first = pgRotationEntry(entry, PersistenceTransportRole.AUX_CANCEL)
        val socket = first.raw.get() as TrackedPersistenceSocket
        check(first.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED && socket.isClosed)
        check(!first.extentEnded && !first.allCallsSealed)
        check(first.business.all { it == null } && first.observations.all { it == null })
        pgCancel(raw)
        check(pgRotationEntry(entry, PersistenceTransportRole.AUX_CANCEL) === first && first.raw.get() === socket)
        check(!first.extentEnded && !first.allCallsSealed && peer.cancellations == 0)
        println("PG_ROTATION_MISSING_CONTACT constructor_cleanup_closed=true extent_ended=false second_allocated=false")
    }

    private fun verifyAuxiliary(entry: PersistencePhysicalEntry, previous: PersistenceTransportEntry<*>?, caller: Thread): PersistenceTransportEntry<*> {
        val current = pgRotationEntry(entry, PersistenceTransportRole.AUX_CANCEL)
        check(current !== previous && current.record !== previous?.record)
        check(current.invocation.get() === PersistenceTransportInvocation.RETURNED && current.construction === PersistenceTransportConstruction.RETURNED)
        check(current.firstClose === PersistenceTransportClosePhase.API_CLOSE_ACKNOWLEDGED)
        check(current.extentEnded && current.allCallsSealed && current.businessSealed)
        check(current.business.all { it == null } && current.observations.all { it == null })
        val socket = current.raw.get() as TrackedPersistenceSocket
        val origin = pgRotationOrigin(socket)
        check(origin.allocatingCaller === caller && origin.allocatingCaller !== entry.driverScope?.let { pgScopeCaller(it) })
        check(origin.factory === pgCapturedFactory(entry) && origin.extent === current.extent && socket.isClosed)
        check(pgRetainedSockets(entry).size == 2)
        if (previous != null) {
            val stale = previous.raw.get() as TrackedPersistenceSocket
            check(runCatching { stale.inetAddress }.exceptionOrNull() is IllegalStateException)
            check(runCatching { stale.supportedOptions() }.exceptionOrNull() is IllegalStateException)
            check(!pgRotationOwner(entry).completeAuxiliaryClose(requireNotNull(previous.auxiliaryCloseReceipt)))
        }
        return current
    }

    private fun verifyRequireFailure(
        scope: PgOpeningProbeScope,
        result: PersistenceFactoryResult<PersistenceJdbcCandidate>,
        witness: PgPrimaryIdentityWitness,
        observation: PgRequireErrorObservation,
    ) {
        check(result is PersistenceFactoryResult.Failed)
        scope.awaitIdle()
        val entry = requireNotNull(scope.retained.get())
        check(entry.raw.get() == null && entry.scopeEnded && entry.unknown && entry.retirementRequested.get())
        check(scope.outcome.get() === PersistencePhysicalOpening.FAILED)
        check(requireNotNull(entry.driverScope).extentSource.primaryOpeningEnded.get())
        check(pgRetainedSockets(entry).size == 1 && pgRetainedSockets(entry).single().isClosed)
        check((pgRotationOwner(entry).snapshot() as PersistenceTransportSnapshot.Available).auxiliary == null)
        observation.requireObserved()
        withPgCurrentPrimary(pgRotationOwner(entry), witness::requireUnchanged)
        println(
            "PG_ROTATION_REQUIRE no_downgrade=true retained_primary=1 no_connection=true e_decoded=true " +
                "original_primary_unchanged=true observation=TEST_ONLY_EXACT_DRIVER_EVENT",
        )
    }

    private fun endpoint(mode: PgOpeningProbeCase, peer: PgRotationPeer): ResolvedPersistenceEndpoint {
        // Only the ordinary held-AUX fixture uses the approved 60s timeout; all repeated/deletion fixtures keep their original settings.
        val extra = when (mode) {
            PgOpeningProbeCase.ROTATE_NEXT_HOST -> mapOf(
                "PGHOST" to "127.0.0.1,127.0.0.1",
                "PGPORT" to "${peer.port},${peer.secondPort}",
                "loadBalanceHosts" to "false",
            )

            PgOpeningProbeCase.ROTATE_PREFER_E -> mapOf("sslmode" to "prefer", "sslcert" to "", "sslkey" to "")

            PgOpeningProbeCase.ROTATE_REQUIRE_E -> mapOf("sslmode" to "require", "sslcert" to "", "sslkey" to "")

            PgOpeningProbeCase.ROTATE_HELD_AUX -> mapOf("cancelSignalTimeout" to "60")

            else -> emptyMap()
        }
        return pgProbeEndpoint(peer.port, extra)
    }

    private fun policy(mode: PgOpeningProbeCase): PersistenceDriverAttemptPolicy = when (mode) {
        PgOpeningProbeCase.ROTATE_CONJUNCTION -> PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
        PgOpeningProbeCase.ROTATE_DELETION -> PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
        else -> PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT
    }
}

/** Fixed own-project inspection only; no private pgjdbc/JDK field access or production capability. */
internal fun pgRotationOwner(entry: PersistencePhysicalEntry): PersistenceTransportOwner<*> =
    PersistencePhysicalTransportBinding::class.java.getDeclaredField("owner").also { it.isAccessible = true }.get(requireNotNull(entry.transports))
        as PersistenceTransportOwner<*>

internal fun pgRotationEntry(entry: PersistencePhysicalEntry, role: PersistenceTransportRole): PersistenceTransportEntry<*> {
    val owner = pgRotationOwner(entry)
    check(owner.snapshot() is PersistenceTransportSnapshot.Available)
    val entries = PersistenceTransportOwner::class.java.getDeclaredField("entries").also { it.isAccessible = true }.get(owner) as Array<*>
    return entries[role.ordinal] as PersistenceTransportEntry<*>
}

internal fun pgRotationOrigin(socket: TrackedPersistenceSocket): PersistencePgTransportOrigin {
    val binding = TrackedPersistenceSocket::class.java.getDeclaredField("binding").also { it.isAccessible = true }.get(socket)
    return PersistenceTransportBinding::class.java.getDeclaredField("origin").also { it.isAccessible = true }.get(binding) as PersistencePgTransportOrigin
}

private fun pgScopeCaller(scope: PersistencePgFactoryScope): Thread = (
    PersistencePgFactoryScope::class.java.getDeclaredField("caller").also {
        it.isAccessible = true
    }.get(scope) as java.util.concurrent.atomic.AtomicReference<*>
    ).get() as Thread
