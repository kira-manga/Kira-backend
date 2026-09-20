package me.manga.kira.backend.common.infrastructure.persistence

internal enum class PgLifecycleNegotiationCut {
    NONE,
    INITIAL_RESPONSE,
    FINAL_STARTUP,
    ;

    fun failAt(reached: PgLifecycleNegotiationCut, peer: PgLifecycleNegotiationPeer, index: Int) {
        if (this !== reached) return
        peer.awaitHeldGate(index, this)
        println("PG_NEGOTIATION_CONTROL_PHASE phase=$name gate_held=true proof=MODEL_ASSERTION_REAL_FIXTURE")
        throw PgLifecycleNegotiationGateFailure(this)
    }
}

internal class PgLifecycleNegotiationGateFailure(val phase: PgLifecycleNegotiationCut) : IllegalStateException("Deliberate witnessed negotiation gate failure.")

internal class PgLifecycleNegotiationExtraClient(val listener: Int) : IllegalStateException("Unexpected reconnect, downgrade or wrong-host attempt.")

/** Separate negative children; the original26 lanes and their positive receipt protocol are unchanged. */
internal enum class PgLifecycleNegotiationControl(
    val recipe: PgLifecycleNegotiationMode,
    val deletion: Boolean = false,
    val cut: PgLifecycleNegotiationCut = PgLifecycleNegotiationCut.NONE,
    val extraListener: Int? = null,
) {
    INITIAL_ORDINARY(PgLifecycleNegotiationMode.PREFER_N, cut = PgLifecycleNegotiationCut.INITIAL_RESPONSE),
    INITIAL_DELETION(PgLifecycleNegotiationMode.PREFER_N, deletion = true, cut = PgLifecycleNegotiationCut.INITIAL_RESPONSE),
    FINAL_PLAIN_ORDINARY(PgLifecycleNegotiationMode.PREFER_N, cut = PgLifecycleNegotiationCut.FINAL_STARTUP),
    FINAL_PLAIN_DELETION(PgLifecycleNegotiationMode.PREFER_N, deletion = true, cut = PgLifecycleNegotiationCut.FINAL_STARTUP),
    FINAL_TLS_ORDINARY(PgLifecycleNegotiationMode.ALLOW_28000_TLS, cut = PgLifecycleNegotiationCut.FINAL_STARTUP),
    FINAL_TLS_DELETION(PgLifecycleNegotiationMode.ALLOW_28000_TLS, deletion = true, cut = PgLifecycleNegotiationCut.FINAL_STARTUP),
    EXTRA_SINGLE_LISTENER(PgLifecycleNegotiationMode.PREFER_N, extraListener = 0),
    EXTRA_FIRST_HOST(PgLifecycleNegotiationMode.NEXT_HOST, extraListener = 0),
    EXTRA_SECOND_HOST(PgLifecycleNegotiationMode.NEXT_HOST, extraListener = 1),
}
