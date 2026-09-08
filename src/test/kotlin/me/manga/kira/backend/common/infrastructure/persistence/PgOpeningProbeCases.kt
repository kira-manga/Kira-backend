package me.manga.kira.backend.common.infrastructure.persistence

import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

internal enum class PgOpeningProbeCase {
    ORDINARY_CONTRACT,
    ORDINARY_CONJUNCTION,
    DELETION_CONJUNCTION,
    PARTIAL_STARTUP_FAILURE,
    ORIGINAL_PROVIDER,
    CONFIGURED_BRIDGE_REFUSED,
    WRONG_OPENING_REFUSED,
    SETTINGS,
    ASSERTION_FAILURE,
    MISSING_RECEIPT,
    ROTATE_ORDINARY,
    ROTATE_CONJUNCTION,
    ROTATE_DELETION,
    ROTATE_HELD_AUX,
    ROTATE_NEXT_HOST,
    ROTATE_PREFER_E,
    ROTATE_REQUIRE_E,
    ROTATE_MISSING_CONTACT,
    ROTATE_BOUND_MODEL,
}

internal object PgOpeningProbeCases {
    fun verify(mode: PgOpeningProbeCase) {
        if (PgRotationProbeCases.handles(mode)) {
            PgRotationProbeCases.verify(mode)
            return
        }
        if (mode === PgOpeningProbeCase.SETTINGS) {
            PgOpeningSettingsCases.verify()
            return
        }
        val partial = mode === PgOpeningProbeCase.PARTIAL_STARTUP_FAILURE
        PgProtocolPeer(partial).use { peer ->
            peer.bind()
            val policy = policy(mode)
            val original = mode === PgOpeningProbeCase.ORIGINAL_PROVIDER || mode === PgOpeningProbeCase.CONFIGURED_BRIDGE_REFUSED
            val extras = if (original) mapOf("socketFactoryArg" to "synthetic-ignored") else emptyMap()
            val configured = if (mode === PgOpeningProbeCase.CONFIGURED_BRIDGE_REFUSED) {
                mapOf("socketFactory" to TrackedPgSocketFactory::class.java.name)
            } else {
                emptyMap()
            }
            val endpoint = pgProbeEndpoint(peer.port, extras + configured)
            val prepared = PersistenceDriverBootstrap.prepare()
            val opening = PersistencePgDriverOpening.prepare(prepared, endpoint, policy, PersistencePathStyle.POSIX)
            val wrong = mode === PgOpeningProbeCase.WRONG_OPENING_REFUSED
            val invoked = if (wrong) PersistencePgDriverOpening.prepare(prepared, endpoint, policy, PersistencePathStyle.POSIX) else opening
            val noConnect = wrong || mode === PgOpeningProbeCase.CONFIGURED_BRIDGE_REFUSED
            PgOpeningProbeScope(opening, invoked).use { scope ->
                if (!noConnect) peer.start()
                val result = scope.request()
                if (partial || noConnect) {
                    requireFailure(scope, result, wrong, original)
                } else {
                    val success = result as? PersistenceFactoryResult.Success<PersistenceJdbcCandidate> ?: error("Synthetic request did not succeed.")
                    val entry = scope.verifyRetained(success)
                    verifyConnection(entry, peer, original)
                }
                if (!noConnect) peer.verify()
            }
        }
    }

    private fun verifyConnection(entry: PersistencePhysicalEntry, peer: PgProtocolPeer, original: Boolean) {
        val raw = requireNotNull(entry.raw.get())
        if (original) {
            check(entry.transports == null && entry.driverScope == null) { "Original provider acquired internal tracking." }
        } else {
            check(pgRetainedSockets(entry).size == 1)
            requireBridgeRefusals(entry, peer)
            check(pgScopePhase(requireNotNull(entry.driverScope)).get() === PersistencePgScopePhase.ENDED)
        }
        pgCancel(raw)
        if (!original) {
            val sockets = pgRetainedSockets(entry)
            check(sockets.size == 2 && sockets[0] !== sockets[1])
            check(!sockets[0].isClosed && sockets[1].isClosed)
        }
        check(entry.raw.get() === raw)
        // Observe this first public close returning; a second close/isClosed is not a substitute.
        raw.close()
        println("PG_JDBC_CLOSE first_call_returned=true raw_identity_retained=true original_provider=$original proof=PUBLIC_API_ONLY")
        check(entry.raw.get() === raw && raw.isClosed)
        if (!original) check(pgRetainedSockets(entry).all { it.isClosed })
    }

    private fun requireBridgeRefusals(entry: PersistencePhysicalEntry, peer: PgProtocolPeer) {
        val factory = pgCapturedFactory(entry)
        val before = pgRetainedSockets(entry)
        try {
            TrackedPgSocketFactory()
            error("Direct bridge constructor was not refused.")
        } catch (_: SocketException) {
            // Expected public constructor boundary, not an unrelated null/address failure.
        }
        val calls = listOf<() -> Socket>(
            { factory.createSocket() },
            { factory.createSocket("127.0.0.1", peer.port) },
            { factory.createSocket("127.0.0.1", peer.port, peer.address, 0) },
            { factory.createSocket(peer.address, peer.port) },
            { factory.createSocket(peer.address, peer.port, peer.address, 0) },
        )
        for (call in calls) {
            val unexpected = AtomicReference<Socket?>()
            var refused = false
            try {
                try {
                    unexpected.set(call())
                } catch (failure: SocketException) {
                    check(failure.message in setOf("Persistence driver bridge is unavailable.", "Connected persistence factory overloads are unavailable."))
                    refused = true
                }
            } finally {
                unexpected.get()?.close()
            }
            check(refused && unexpected.get() == null) { "Captured factory accepted an unauthenticated call." }
        }
        val after = pgRetainedSockets(entry)
        check(before.size == after.size && before.indices.all { before[it] === after[it] })
        println("PG_BRIDGE_REFUSALS direct_constructor=true direct_allocation=true connected=4 retained_sockets_unchanged=true")
    }

    private fun requireFailure(scope: PgOpeningProbeScope, result: PersistenceFactoryResult<PersistenceJdbcCandidate>, wrong: Boolean, original: Boolean) {
        check(result is PersistenceFactoryResult.Failed) { "Synthetic failed opening was not retained as an accepted failure." }
        scope.awaitIdle()
        val entry = requireNotNull(scope.retained.get())
        check(entry.raw.get() == null && entry.retirementRequested.get())
        check(entry === scope.binding.ledger.entries[entry.record.slotHint])
        check(result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED)
        if (wrong) {
            check(scope.outcome.get() === PersistencePhysicalOpening.REFUSED)
            check(entry.opening === PersistencePhysicalOpeningPhase.UNCLAIMED && !entry.scopeEnded)
            check(pgRetainedSockets(entry).isEmpty())
        } else {
            check(scope.outcome.get() === PersistencePhysicalOpening.FAILED)
            check(entry.opening === PersistencePhysicalOpeningPhase.SETTLED && entry.scopeEnded && entry.unknown)
            if (original) check(entry.transports == null && entry.driverScope == null) else check(pgRetainedSockets(entry).size == 1)
        }
        val proof = if (wrong) "MODEL_ASSOCIATION_REAL_WORKER" else "REAL_DRIVER_MODEL_TERMINAL"
        println("PG_OPENING_FAILURE no_raw=true physical_retained=true wrong_opening=$wrong original_provider=$original proof=$proof")
    }

    private fun policy(mode: PgOpeningProbeCase): PersistenceDriverAttemptPolicy = when (mode) {
        PgOpeningProbeCase.ORDINARY_CONJUNCTION -> PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION
        PgOpeningProbeCase.DELETION_CONJUNCTION -> PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION
        PgOpeningProbeCase.ORIGINAL_PROVIDER, PgOpeningProbeCase.CONFIGURED_BRIDGE_REFUSED -> PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER
        else -> PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT
    }
}

internal fun pgProbeEndpoint(port: Int, extra: Map<String, String> = emptyMap()): ResolvedPersistenceEndpoint = ResolvedPersistenceEndpoint(
    mapOf(
        "PGHOST" to "127.0.0.1", "PGPORT" to port.toString(), "PGDBNAME" to "fixture", "user" to "fixture",
        "password" to PgProtocolPeer.PASSWORD, "ApplicationName" to PgProtocolPeer.APPLICATION, "loginTimeout" to "0",
        "requireAuth" to "password", "gssEncMode" to "disable", "sslmode" to "disable", "connectTimeout" to "2",
        "socketTimeout" to "3", "cancelSignalTimeout" to "2",
    ) + extra,
    PersistenceLoginPolicy.resolve(null, 6_000),
)
