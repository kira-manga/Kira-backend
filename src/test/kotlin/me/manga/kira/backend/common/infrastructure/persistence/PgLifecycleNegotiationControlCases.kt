package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

/** Uses the actual request/peer paths; only the deliberate gate exception is MODEL. No manufactured cleanup receipt. */
internal object PgLifecycleNegotiationControlCases {
    fun verify(control: PgLifecycleNegotiationControl) {
        val recipe = control.recipe
        val material = if (recipe.requiresMaterial) PgLifecycleTlsMaterial.inChild() else null
        material.use {
            val context = if (recipe.realTls) requireNotNull(material).serverContext(PgLifecycleTlsMode.MATCHED) else null
            PgLifecycleNegotiationPeer(recipe, context).use { peer ->
                peer.start()
                val endpoint = PgLifecycleNegotiationSettings.endpoint(peer, recipe, material?.rootCertificate(wrong = false))
                PgLifecycleTestScope(endpoint).use { scope ->
                    scope.start()
                    if (control.deletion) scope.prepareDeletion()
                    exercise(scope, peer, endpoint, control)
                }
            }
        }
        error("Negative negotiation control returned without its exact witnessed failure.")
    }

    private fun exercise(
        scope: PgLifecycleTestScope,
        peer: PgLifecycleNegotiationPeer,
        endpoint: ResolvedPersistenceEndpoint,
        control: PgLifecycleNegotiationControl,
    ) {
        val recipe = control.recipe
        val first = PgLifecycleNegotiationCases.attempt(scope, peer, 0, control.deletion, recipe, endpoint, null, control.cut)
        check(control.cut === PgLifecycleNegotiationCut.NONE) { "Deliberate gate failure was not reached." }
        if (recipe === PgLifecycleNegotiationMode.NEXT_HOST) {
            val ended = System.nanoTime()
            awaitLifecycleFact(1_000) { System.nanoTime() - ended >= 2_000_000 }
        }
        PgLifecycleNegotiationCases.attempt(scope, peer, 1, control.deletion, recipe, endpoint, first)
        PgLifecycleTlsAssertions.shutdown(scope) // Actual no-future-driver-allocation before the guard negative.
        val listener = requireNotNull(control.extraListener)
        Socket().use { extra ->
            val port = if (listener == 0) peer.port else peer.secondPort
            extra.connect(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), port), 1_000)
            println("PG_NEGOTIATION_CONTROL_PHASE phase=EXTRA_CLIENT listener=$listener root_ended=true proof=REAL_SOCKET")
            peer.verifyNoExtraConnections()
        }
    }
}
