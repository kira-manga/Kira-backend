package me.manga.kira.backend.complaint.domain

import java.util.Base64

/** Dormant lower accounting port. No request, event, catalog or unused-work authority is supplied here. */
internal fun interface ComplaintRecoverySettlement {
    fun settle(expectation: ComplaintRecoverySettlementExpectation): ComplaintRecoverySettlementResult
}

internal enum class ComplaintRecoverySettlementResult { SETTLED, REPLAYED }

/**
 * Immutable comparison data, NOT authenticated evidence. The store must independently lock and
 * compare the original scoped promise. Production policy/event/usage proof producers remain W04.
 * In particular, neither canonical event spelling nor a caller's zero use proves work unused.
 */
internal class ComplaintRecoverySettlementExpectation private constructor(
    val eventId: String,
    val scope: ComplaintDataScope,
    val accountingVersion: Int,
    val originalPromise: ComplaintCapacityVector,
    val actualUse: ComplaintCapacityVector,
) {
    override fun toString(): String = "ComplaintRecoverySettlementExpectation(redacted)"

    companion object {
        private val EVENT_ID = Regex("[A-Za-z0-9_-]{43}")

        fun of(
            eventId: String,
            scope: ComplaintDataScope,
            originalPromise: ComplaintCapacityVector,
            actualUse: ComplaintCapacityVector,
            accountingVersion: Int = ComplaintCapacityEncoding.VERSION,
        ): ComplaintRecoverySettlementExpectation {
            require(EVENT_ID.matches(eventId))
            val decoded = Base64.getUrlDecoder().decode(eventId)
            require(decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == eventId)
            ComplaintCapacityEncoding.requireVersion(accountingVersion)
            return ComplaintRecoverySettlementExpectation(eventId, scope, accountingVersion, originalPromise, actualUse)
        }
    }
}
