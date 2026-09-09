package me.manga.kira.backend.common.infrastructure.persistence

internal object PgLifecycleNegotiationCases {
    fun isNegotiationCase(mode: PgLifecycleCase): Boolean = recipe(mode) != null

    fun requiresMaterial(mode: PgLifecycleCase): Boolean = recipe(mode)?.requiresMaterial == true

    fun verify(mode: PgLifecycleCase) {
        val recipe = requireNotNull(recipe(mode))
        val deletion = mode in DELETION_CASES
        val material = if (recipe.requiresMaterial) PgLifecycleTlsMaterial.inChild() else null
        material.use {
            val root = material?.rootCertificate(wrong = false)
            val context = if (recipe.realTls) requireNotNull(material).serverContext(PgLifecycleTlsMode.MATCHED) else null
            PgLifecycleNegotiationPeer(recipe, context).use { peer ->
                peer.start()
                val endpoint = PgLifecycleNegotiationSettings.endpoint(peer, recipe, root)
                PgLifecycleTestScope(endpoint).use { scope ->
                    verifyScope(scope, peer, deletion, recipe, endpoint)
                }
                peer.verifyNoExtraConnections()
            }
        }
        val proof = if (recipe.realTls) "REAL_TLS+PROTOCOL_PEER" else "REAL_DRIVER+NEGOTIATION_PROTOCOL_PEER"
        println(
            "PG_LIFECYCLE_NEGOTIATION_VERIFIED mode=${mode.name} attempts=2 rotated=${recipe.rotates} " +
                "tls_handshakes=${if (recipe.realTls) 2 else 0} proof=$proof",
        )
    }

    private fun verifyScope(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleNegotiationPeer,
        deletion: Boolean,
        recipe: PgLifecycleNegotiationMode,
        endpoint: ResolvedPersistenceEndpoint,
    ) {
        PgLifecycleAdmissionScheduling(scope).use { admission ->
            // The same scanner serves both lanes; retain its ordinary G-only cut before any actor starts.
            installLifecycleModelLock(scope, PgLifecycleAdmissionLock(admission))
            scope.start()
            if (deletion) scope.prepareDeletion()
            runAttempts(scope, peer, deletion, recipe, endpoint, admission)
            PgLifecycleTlsAssertions.shutdown(scope)
        }
    }

    private fun runAttempts(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleNegotiationPeer,
        deletion: Boolean,
        recipe: PgLifecycleNegotiationMode,
        endpoint: ResolvedPersistenceEndpoint,
        admission: PgLifecycleAdmissionScheduling,
    ) {
        var previous: PgLifecycleNegotiationWitness? = null
        var previousEndedAt = 0L
        repeat(2) { index ->
            if (index > 0 && recipe === PgLifecycleNegotiationMode.NEXT_HOST) {
                // pgjdbc's host-status clock has millisecond resolution. This wait is OUTSIDE every caller budget;
                // hostRecheckSeconds=0 then reconsiders the refused first host without private cache mutation.
                awaitLifecycleFact(1_000) { System.nanoTime() - previousEndedAt >= 2_000_000 }
            }
            previous = attempt(scope, peer, index, deletion, recipe, endpoint, previous, admission)
            previousEndedAt = System.nanoTime()
        }
    }

    internal fun attempt(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleNegotiationPeer,
        index: Int,
        deletion: Boolean,
        recipe: PgLifecycleNegotiationMode,
        endpoint: ResolvedPersistenceEndpoint,
        previous: PgLifecycleNegotiationWitness?,
        admission: PgLifecycleAdmissionScheduling,
        cut: PgLifecycleNegotiationCut = PgLifecycleNegotiationCut.NONE,
    ): PgLifecycleNegotiationWitness {
        awaitLifecycleFact { scope.binding(deletion).isOwnedReceiverReady() }
        peer.arm(index)
        return PgLifecycleTlsRequest(scope, deletion).use { caller ->
            try {
                admission.during("fixture=NEGOTIATION ordinal=$index deletion=$deletion recipe=${recipe.name}") {
                    caller.start()
                    PgLifecycleDatabaseDiagnostics.preservingFailure(
                        {
                            println(
                                "PG_LIFECYCLE_STARTUP_DIAGNOSTIC fixture=NEGOTIATION ordinal=$index deletion=$deletion recipe=${recipe.name} " +
                                    caller.diagnostic() + " " + peer.diagnostic(index),
                            )
                        },
                        { peer.awaitInitial(index) },
                    )
                }
                val witness = PgLifecycleNegotiationAssertions.admitted(scope, deletion, endpoint)
                cut.failAt(PgLifecycleNegotiationCut.INITIAL_RESPONSE, peer, index)
                if (previous != null) PgLifecycleNegotiationAssertions.staleAlias(scope, deletion, previous, witness)
                peer.releaseResponse(index)
                val terminalWitness = if (recipe.succeeds) {
                    peer.awaitFinalStartup(index)
                    PgLifecycleNegotiationAssertions.finalPrimary(scope, deletion, endpoint, witness, recipe.rotates).also {
                        cut.failAt(PgLifecycleNegotiationCut.FINAL_STARTUP, peer, index)
                        peer.releaseAuthentication(index)
                    }
                } else {
                    PgLifecycleTlsWitness(witness.entry, witness.first.record)
                }
                val result = caller.await()
                val receipt = settle(scope, peer, index, deletion, recipe, witness, previous, result)
                PgLifecycleTlsAssertions.retired(scope, deletion, terminalWitness, receipt, raw = recipe.succeeds)
                if (recipe.rotates) PgLifecycleNegotiationAssertions.predecessorRemainsDisposed(witness)
                peer.awaitComplete(index)
                witness
            } catch (failure: PgLifecycleNegotiationGateFailure) {
                // Mark fixture cleanup before releasing its held gate; preserve any genuine cleanup failure on the exact deliberate exception.
                runCatching { peer.close() }.exceptionOrNull()?.let { failure.addSuppressed(it) }
                throw failure
            } finally {
                peer.release(index) // Failure before either gate still releases the owned peer; caller/root owners then drain independently.
            }
        }
    }

    private fun settle(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleNegotiationPeer,
        index: Int,
        deletion: Boolean,
        recipe: PgLifecycleNegotiationMode,
        witness: PgLifecycleNegotiationWitness,
        previous: PgLifecycleNegotiationWitness?,
        result: PersistenceFactoryResult<PersistenceJdbcCandidate>,
    ): PersistenceFactoryReceipt {
        if (recipe.succeeds) {
            check(result is PersistenceFactoryResult.Success) { "Supported negotiation must return an opaque candidate, not timeout/refusal/failure: $result" }
            check(result.value === witness.entry.candidate && witness.entry.raw.get() != null)
            awaitLifecycleFact { result.receipt.state() === PersistenceFactoryProcessing.PROCESSING_ENDED }
            peer.requireStillConnected(index)
            if (previous != null) PgLifecycleNegotiationAssertions.staleAlias(scope, deletion, previous, witness)
            peer.requireStillConnected(index)
            check(scope.retire(result, deletion) === witness.entry)
            return result.receipt
        }
        check(result is PersistenceFactoryResult.Failed && result.reason === PersistenceFactoryFailure.CREATE_FAILED) {
            "Required-mode E/N must be accepted CREATE_FAILED with no plaintext successor, never settings refusal or caller timeout: $result"
        }
        check(witness.entry.raw.get() == null)
        return result.receipt
    }

    private fun recipe(mode: PgLifecycleCase): PgLifecycleNegotiationMode? = when (mode) {
        PgLifecycleCase.NEGOTIATION_PREFER_E_ORDINARY, PgLifecycleCase.NEGOTIATION_PREFER_E_DELETION -> PgLifecycleNegotiationMode.PREFER_E

        PgLifecycleCase.NEGOTIATION_PREFER_N_ORDINARY, PgLifecycleCase.NEGOTIATION_PREFER_N_DELETION -> PgLifecycleNegotiationMode.PREFER_N

        PgLifecycleCase.NEGOTIATION_REQUIRE_E_ORDINARY, PgLifecycleCase.NEGOTIATION_REQUIRE_E_DELETION -> PgLifecycleNegotiationMode.REQUIRE_E

        PgLifecycleCase.NEGOTIATION_REQUIRE_N_ORDINARY, PgLifecycleCase.NEGOTIATION_REQUIRE_N_DELETION -> PgLifecycleNegotiationMode.REQUIRE_N

        PgLifecycleCase.NEGOTIATION_VERIFY_CA_E_ORDINARY, PgLifecycleCase.NEGOTIATION_VERIFY_CA_E_DELETION -> PgLifecycleNegotiationMode.VERIFY_CA_E

        PgLifecycleCase.NEGOTIATION_VERIFY_CA_N_ORDINARY, PgLifecycleCase.NEGOTIATION_VERIFY_CA_N_DELETION -> PgLifecycleNegotiationMode.VERIFY_CA_N

        PgLifecycleCase.NEGOTIATION_VERIFY_FULL_E_ORDINARY, PgLifecycleCase.NEGOTIATION_VERIFY_FULL_E_DELETION -> PgLifecycleNegotiationMode.VERIFY_FULL_E

        PgLifecycleCase.NEGOTIATION_VERIFY_FULL_N_ORDINARY, PgLifecycleCase.NEGOTIATION_VERIFY_FULL_N_DELETION -> PgLifecycleNegotiationMode.VERIFY_FULL_N

        PgLifecycleCase.NEGOTIATION_PREFER_RESPONSE_TIMEOUT_ORDINARY, PgLifecycleCase.NEGOTIATION_PREFER_RESPONSE_TIMEOUT_DELETION ->
            PgLifecycleNegotiationMode.PREFER_RESPONSE_TIMEOUT

        PgLifecycleCase.NEGOTIATION_NEXT_HOST_ORDINARY, PgLifecycleCase.NEGOTIATION_NEXT_HOST_DELETION -> PgLifecycleNegotiationMode.NEXT_HOST

        PgLifecycleCase.NEGOTIATION_PREFER_TLS_28000_ORDINARY, PgLifecycleCase.NEGOTIATION_PREFER_TLS_28000_DELETION ->
            PgLifecycleNegotiationMode.PREFER_TLS_28000

        PgLifecycleCase.NEGOTIATION_ALLOW_28000_TLS_ORDINARY, PgLifecycleCase.NEGOTIATION_ALLOW_28000_TLS_DELETION -> PgLifecycleNegotiationMode.ALLOW_28000_TLS

        PgLifecycleCase.NEGOTIATION_ALLOW_IO_TLS_ORDINARY, PgLifecycleCase.NEGOTIATION_ALLOW_IO_TLS_DELETION -> PgLifecycleNegotiationMode.ALLOW_IO_TLS

        else -> null
    }

    private val DELETION_CASES = setOf(
        PgLifecycleCase.NEGOTIATION_PREFER_E_DELETION,
        PgLifecycleCase.NEGOTIATION_PREFER_N_DELETION,
        PgLifecycleCase.NEGOTIATION_REQUIRE_E_DELETION,
        PgLifecycleCase.NEGOTIATION_REQUIRE_N_DELETION,
        PgLifecycleCase.NEGOTIATION_VERIFY_CA_E_DELETION,
        PgLifecycleCase.NEGOTIATION_VERIFY_CA_N_DELETION,
        PgLifecycleCase.NEGOTIATION_VERIFY_FULL_E_DELETION,
        PgLifecycleCase.NEGOTIATION_VERIFY_FULL_N_DELETION,
        PgLifecycleCase.NEGOTIATION_PREFER_RESPONSE_TIMEOUT_DELETION,
        PgLifecycleCase.NEGOTIATION_NEXT_HOST_DELETION,
        PgLifecycleCase.NEGOTIATION_PREFER_TLS_28000_DELETION,
        PgLifecycleCase.NEGOTIATION_ALLOW_28000_TLS_DELETION,
        PgLifecycleCase.NEGOTIATION_ALLOW_IO_TLS_DELETION,
    )
}
