package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.ComplaintCapacityBalance
import me.manga.kira.backend.complaint.domain.ComplaintCapacityConfiguration
import me.manga.kira.backend.complaint.domain.ComplaintCapacityEncoding
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/** Bounded comparison data only. A counter snapshot, digest or exact effect can never enter PROJECT. */
internal class CatalogTestRunActivationProjectionRowsV1(
    val counters: CatalogTestRunActivationProjectionCountersV1,
    val projectedAt: Instant?,
    effectFingerprint: ByteArray?,
    frozen: CatalogTestRunActivationFrozenV1,
) {
    private val effect = effectFingerprint?.copyOf()
    val projectedCapacityHash: String = if (projectedAt == null) counters.afterProjectionHash(frozen) else counters.semanticHash

    init {
        check((projectedAt == null) == (effect == null))
        check(effect == null || effect.size == 32)
    }

    fun requireSame(other: CatalogTestRunActivationProjectionRowsV1) {
        check(projectedAt == other.projectedAt && effect.contentEquals(other.effect) && projectedCapacityHash == other.projectedCapacityHash)
        counters.requireSame(other.counters)
    }

    /** Initial registration retains these bounded comparisons, not FrozenV1's closed projector graph. */
    internal fun requireInitialAdmission(observed: CatalogTestRunActivationProjectionCountersV1, at: Instant, fingerprint: ByteArray) {
        check(projectedAt == at && effect != null && effect.contentEquals(fingerprint) && projectedCapacityHash == observed.semanticHash)
        counters.requireSame(observed)
    }

    fun requireProjectionTransition(before: CatalogTestRunActivationProjectionRowsV1, frozen: CatalogTestRunActivationFrozenV1) {
        check(projectedAt != null && effect != null && before.projectedAt == null && before.effect == null)
        counters.requireProjectionTransition(before.counters, frozen)
        check(counters.semanticHash == before.projectedCapacityHash)
    }

    override fun toString(): String = "CatalogTestRunActivationProjectionRowsV1(bounded-exact-effect,no-authority)"
}

/**
 * Exactly22 typed counters and the one daily bucket. Physical fingerprints include updated_at/xmin
 * for the two-round preimage check; the separately versioned semantic digest binds cold custody to
 * the exact balances without pretending a future transaction timestamp is already known.
 */
internal class CatalogTestRunActivationProjectionCountersV1(
    val configuration: ComplaintCapacityConfiguration,
    val balance: ComplaintCapacityBalance,
    val daily: ComplaintDailyAdmission,
    fingerprints: List<ByteArray>,
) {
    private val rows = fingerprints.map { it.copyOf() }
    val semanticHash: String = semanticHash(configuration, balance, daily)

    init {
        check(rows.size == ComplaintCapacityEncoding.lockOrder().size && rows.all { it.size == 32 })
    }

    fun requireSame(other: CatalogTestRunActivationProjectionCountersV1) {
        check(configuration == other.configuration && balance == other.balance && daily == other.daily && semanticHash == other.semanticHash)
        check(rows.indices.all { rows[it].contentEquals(other.rows[it]) })
    }

    fun requireSettled(before: CatalogTestRunActivationProjectionCountersV1, expected: ComplaintCapacityBalance) {
        check(configuration == before.configuration && balance == expected && daily == before.daily)
        // Counter rows with no charge/reserve delta must not be touched, even by a semantically empty UPDATE.
        ComplaintCapacityEncoding.lockOrder().forEachIndexed { index, counter ->
            if (before.balance.free[counter] == expected.free[counter] && before.balance.actual[counter] == expected.actual[counter] &&
                before.balance.testReserved[counter] == expected.testReserved[counter]) check(rows[index].contentEquals(before.rows[index]))
        }
    }

    fun afterProjectionHash(frozen: CatalogTestRunActivationFrozenV1): String =
        semanticHash(configuration, afterProjection(frozen), daily)

    fun requireProjectionTransition(before: CatalogTestRunActivationProjectionCountersV1, frozen: CatalogTestRunActivationFrozenV1) =
        requireSettled(before, before.afterProjection(frozen))

    private fun afterProjection(frozen: CatalogTestRunActivationFrozenV1): ComplaintCapacityBalance =
        ComplaintCapacityLedger(configuration, balance).chargeCreation(frozen.capacityDigest(), frozen.projectionCharge)
            .reserveTest(frozen.capacityDigest(), frozen.reserve).balance

    override fun toString(): String = "CatalogTestRunActivationProjectionCountersV1(exact-comparison,no-counter-authority)"

    companion object {
        /** Closed v1 binary encoding: domain LP32, version/count, P32/closed byte, typed name/ordinal and six int64 values per counter, daily. */
        private fun semanticHash(configuration: ComplaintCapacityConfiguration, balance: ComplaintCapacityBalance, daily: ComplaintDailyAdmission): String {
            val digest = MessageDigest.getInstance("SHA-256")
            fun int(value: Int) { digest.update(ByteBuffer.allocate(4).putInt(value).array()) }
            fun long(value: Long) { digest.update(ByteBuffer.allocate(8).putLong(value).array()) }
            fun text(value: String) { val bytes = value.toByteArray(Charsets.UTF_8); int(bytes.size); digest.update(bytes) }
            text("kira-test-run-projection-capacity-v1")
            int(1)
            val order = ComplaintCapacityEncoding.lockOrder()
            int(order.size)
            val policy = checkNotNull(configuration.digestBytes()).also { check(it.size == 32) }
            digest.update(policy)
            digest.update(if (configuration.creationClosed) 1.toByte() else 0.toByte())
            order.forEach { counter ->
                int(counter.storedOrdinal)
                text(counter.storedName)
                long(balance.hardLimit[counter]); long(balance.creationLimit[counter]); long(balance.free[counter])
                long(balance.actual[counter]); long(balance.recoveryReserved[counter]); long(balance.testReserved[counter])
            }
            val day = daily.utcEpochDay
            digest.update(if (day == null) 0.toByte() else 1.toByte())
            if (day != null) long(day)
            long(daily.count)
            long(daily.dailyLimit)
            return HexFormat.of().formatHex(digest.digest())
        }
    }
}
