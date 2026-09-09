package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path

internal object PgLifecycleTlsCases {
    fun isTlsCase(mode: PgLifecycleCase): Boolean = mode in CASES

    fun verify(mode: PgLifecycleCase) {
        check(isTlsCase(mode))
        val recipe = recipe(mode)
        val deletion = mode in DELETION_CASES
        PgLifecycleTlsMaterial.inChild().use { material ->
            val rootCertificate = material.rootCertificate(recipe === PgLifecycleTlsMode.WRONG_CA)
            PgLifecycleTlsPeer(recipe, material.serverContext(recipe)).use { peer ->
                peer.start()
                PgLifecycleTestScope(endpoint(peer.port, rootCertificate)).use { scope ->
                    verifyScope(scope, peer, deletion, rootCertificate, recipe)
                }
                peer.verifyNoDowngrade()
            }
        }
        println("PG_LIFECYCLE_TLS_VERIFIED mode=${mode.name} attempts=2 no_plaintext_downgrade=true proof=REAL_TLS+PROTOCOL_PEER")
    }

    private fun verifyScope(scope: PgLifecycleTestScope, peer: PgLifecycleTlsPeer, deletion: Boolean, rootCertificate: Path, recipe: PgLifecycleTlsMode) {
        PgLifecycleAdmissionScheduling(scope).use { admission ->
            // The same scanner serves both lanes; retain its ordinary G-only cut before any actor starts.
            installLifecycleModelLock(scope, PgLifecycleAdmissionLock(admission))
            scope.start()
            if (deletion) scope.prepareDeletion()
            var previous: PgLifecycleTlsWitness? = null
            repeat(2) { index -> previous = attempt(scope, peer, index, deletion, rootCertificate, recipe, previous, admission) }
            PgLifecycleTlsAssertions.shutdown(scope)
        }
    }

    private fun attempt(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleTlsPeer,
        index: Int,
        deletion: Boolean,
        rootCertificate: Path,
        recipe: PgLifecycleTlsMode,
        previous: PgLifecycleTlsWitness?,
        admission: PgLifecycleAdmissionScheduling,
    ): PgLifecycleTlsWitness {
        awaitLifecycleFact { scope.binding(deletion).isOwnedReceiverReady() }
        peer.arm(index)
        return PgLifecycleTlsRequest(scope, deletion).use { caller ->
            try {
                admission.during("fixture=TLS ordinal=$index deletion=$deletion recipe=${recipe.name}") {
                    caller.start()
                    PgLifecycleDatabaseDiagnostics.preservingFailure(
                        {
                            println(
                                "PG_LIFECYCLE_STARTUP_DIAGNOSTIC fixture=TLS ordinal=$index deletion=$deletion recipe=${recipe.name} " +
                                    caller.diagnostic() + " " + peer.diagnostic(index),
                            )
                        },
                        { peer.awaitSslRequest(index) },
                    )
                }
                val witness = PgLifecycleTlsAssertions.admitted(scope, deletion, rootCertificate)
                if (previous != null) {
                    check(witness.entry.record !== previous.entry.record && witness.entry.record.slotHint == previous.entry.record.slotHint)
                    check(!previous.entry.candidate.requestRetirement())
                    check(!witness.entry.retirementRequested.get()) { "A retired TLS alias affected its replacement." }
                }
                peer.release(index)
                val result = caller.await()
                val receipt = settle(scope, peer, index, deletion, recipe, witness, result)
                PgLifecycleTlsAssertions.retired(scope, deletion, witness, receipt, raw = recipe === PgLifecycleTlsMode.MATCHED)
                peer.awaitComplete(index)
                witness
            } finally {
                peer.release(index) // Also release on an assertion failure before S, then the caller/scope owners drain.
            }
        }
    }

    private fun settle(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleTlsPeer,
        index: Int,
        deletion: Boolean,
        recipe: PgLifecycleTlsMode,
        witness: PgLifecycleTlsWitness,
        result: PersistenceFactoryResult<PersistenceJdbcCandidate>,
    ): PersistenceFactoryReceipt {
        if (recipe === PgLifecycleTlsMode.MATCHED) {
            check(result is PersistenceFactoryResult.Success) { "Matching verify-full TLS did not return an opaque candidate: $result" }
            check(result.value === witness.entry.candidate && witness.entry.raw.get() != null)
            awaitLifecycleFact { result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED }
            peer.requireStillConnected(index)
            check(scope.retire(result, deletion) === witness.entry)
            return result.receipt
        }
        check(result is PersistenceFactoryResult.Failed && result.reason === PersistenceFactoryFailure.CREATE_FAILED) {
            "TLS rejection must be accepted CREATE_FAILED, not settings refusal, caller timeout or fallback success: $result"
        }
        check(witness.entry.raw.get() == null)
        return result.receipt
    }

    private fun endpoint(port: Int, rootCertificate: Path): ResolvedPersistenceEndpoint = pgProbeEndpoint(
        port,
        mapOf(
            "sslmode" to "verify-full",
            "sslfactory" to "org.postgresql.ssl.LibPQFactory",
            "sslNegotiation" to "postgres",
            "sslrootcert" to rootCertificate.toString(),
            "sslcert" to "",
            "sslkey" to "",
            "sslResponseTimeout" to "2000",
            "channelBinding" to "disable", // The synthetic TLS peer deliberately authenticates using password, not SCRAM-PLUS.
            "scramMaxIterations" to "100000",
            "queryTimeout" to "0",
            "readOnly" to "false",
            "targetServerType" to "any",
            "loadBalanceHosts" to "false",
        ),
    )

    private fun recipe(mode: PgLifecycleCase): PgLifecycleTlsMode = when (mode) {
        PgLifecycleCase.TLS_VERIFY_FULL_ORDINARY, PgLifecycleCase.TLS_VERIFY_FULL_DELETION -> PgLifecycleTlsMode.MATCHED
        PgLifecycleCase.TLS_WRONG_CA_ORDINARY, PgLifecycleCase.TLS_WRONG_CA_DELETION -> PgLifecycleTlsMode.WRONG_CA
        PgLifecycleCase.TLS_WRONG_HOST_ORDINARY, PgLifecycleCase.TLS_WRONG_HOST_DELETION -> PgLifecycleTlsMode.WRONG_HOST
        PgLifecycleCase.TLS_PARTIAL_HANDSHAKE_ORDINARY, PgLifecycleCase.TLS_PARTIAL_HANDSHAKE_DELETION -> PgLifecycleTlsMode.PARTIAL_HANDSHAKE
        else -> error("Not an integrated TLS lifecycle case.")
    }

    private val DELETION_CASES = setOf(
        PgLifecycleCase.TLS_VERIFY_FULL_DELETION,
        PgLifecycleCase.TLS_WRONG_CA_DELETION,
        PgLifecycleCase.TLS_WRONG_HOST_DELETION,
        PgLifecycleCase.TLS_PARTIAL_HANDSHAKE_DELETION,
    )
    private val CASES = DELETION_CASES + setOf(
        PgLifecycleCase.TLS_VERIFY_FULL_ORDINARY,
        PgLifecycleCase.TLS_WRONG_CA_ORDINARY,
        PgLifecycleCase.TLS_WRONG_HOST_ORDINARY,
        PgLifecycleCase.TLS_PARTIAL_HANDSHAKE_ORDINARY,
    )
}
