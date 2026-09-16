package me.manga.kira.backend.complaint.domain

/** Dormant lower accounting port, not test-run/catalog authority or a production allocation writer. */
internal fun interface ComplaintTestReserveSpend {
    fun spend(expectation: ComplaintTestReserveSpendExpectation): ComplaintTestReserveSpendResult
}

internal enum class ComplaintTestReserveSpendResult { SPENT }

/**
 * Immutable comparison data only. The store independently locks the exact ACTIVE test run and
 * compares its original and unused vectors. These values neither authenticate an allocation nor
 * authorize a terminal transition, reserve release or replay; those writers/producers remain W04.
 */
internal class ComplaintTestReserveSpendExpectation private constructor(
    val scope: ComplaintDataScope,
    val accountingVersion: Int,
    val originalReserve: ComplaintCapacityVector,
    val expectedUnused: ComplaintCapacityVector,
    val toActual: ComplaintCapacityVector,
    val toRecovery: ComplaintCapacityVector,
) {
    override fun toString(): String = "ComplaintTestReserveSpendExpectation(redacted)"

    companion object {
        fun of(
            scope: ComplaintDataScope,
            originalReserve: ComplaintCapacityVector,
            expectedUnused: ComplaintCapacityVector,
            toActual: ComplaintCapacityVector,
            toRecovery: ComplaintCapacityVector,
            accountingVersion: Int = ComplaintCapacityEncoding.VERSION,
        ): ComplaintTestReserveSpendExpectation {
            require(scope.testOnly)
            ComplaintCapacityEncoding.requireVersion(accountingVersion)
            return ComplaintTestReserveSpendExpectation(scope, accountingVersion, originalReserve, expectedUnused, toActual, toRecovery)
        }
    }
}
