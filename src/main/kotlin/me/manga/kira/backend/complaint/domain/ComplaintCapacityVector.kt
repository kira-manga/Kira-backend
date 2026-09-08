package me.manga.kira.backend.complaint.domain

/** Immutable logical units. The persistence decoder additionally checks SQL array dimensions, lower bound and NULLs. */
class ComplaintCapacityVector private constructor(private val amounts: LongArray) {
    operator fun get(counter: ComplaintCapacityCounter): Long = amounts[counter.storedOrdinal - 1]

    fun toLongArray(): LongArray = amounts.copyOf()

    fun isZero(): Boolean = amounts.all { it == 0L }

    fun with(counter: ComplaintCapacityCounter, amount: Long): ComplaintCapacityVector {
        requireNonnegative(amount)
        return ComplaintCapacityVector(amounts.copyOf().also { it[counter.storedOrdinal - 1] = amount })
    }

    operator fun plus(other: ComplaintCapacityVector): ComplaintCapacityVector = ComplaintCapacityVector(
        LongArray(ComplaintCapacityEncoding.WIDTH) { index -> add(amounts[index], other.amounts[index]) },
    )

    operator fun minus(other: ComplaintCapacityVector): ComplaintCapacityVector = ComplaintCapacityVector(
        LongArray(ComplaintCapacityEncoding.WIDTH) { index -> subtract(amounts[index], other.amounts[index]) },
    )

    fun scaled(factor: Long): ComplaintCapacityVector {
        requireNonnegative(factor)
        return ComplaintCapacityVector(LongArray(ComplaintCapacityEncoding.WIDTH) { index -> multiply(amounts[index], factor) })
    }

    fun fitsWithin(ceiling: ComplaintCapacityVector): Boolean = amounts.indices.all { amounts[it] <= ceiling.amounts[it] }

    override fun equals(other: Any?): Boolean = other is ComplaintCapacityVector && amounts.contentEquals(other.amounts)

    override fun hashCode(): Int = amounts.contentHashCode()

    override fun toString(): String = "ComplaintCapacityVector(v1, redacted)"

    companion object {
        val ZERO = ComplaintCapacityVector(LongArray(ComplaintCapacityEncoding.WIDTH))

        fun of(amounts: LongArray, version: Int = ComplaintCapacityEncoding.VERSION): ComplaintCapacityVector {
            ComplaintCapacityEncoding.requireVersion(version)
            if (amounts.size != ComplaintCapacityEncoding.WIDTH) rejectCapacity(ComplaintCapacityFailureCode.INVALID_VECTOR_WIDTH)
            val snapshot = amounts.copyOf()
            if (snapshot.any { it < 0L }) rejectCapacity(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT)
            return ComplaintCapacityVector(snapshot)
        }

        fun units(counter: ComplaintCapacityCounter, amount: Long): ComplaintCapacityVector = ZERO.with(counter, amount)

        private fun requireNonnegative(amount: Long) {
            if (amount < 0L) rejectCapacity(ComplaintCapacityFailureCode.NEGATIVE_AMOUNT)
        }

        private fun add(left: Long, right: Long): Long {
            if (left > Long.MAX_VALUE - right) rejectCapacity(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW)
            return left + right
        }

        private fun subtract(left: Long, right: Long): Long {
            if (left < right) rejectCapacity(ComplaintCapacityFailureCode.INSUFFICIENT_UNITS)
            return left - right
        }

        private fun multiply(amount: Long, factor: Long): Long {
            if (amount != 0L && factor > Long.MAX_VALUE / amount) rejectCapacity(ComplaintCapacityFailureCode.AMOUNT_OVERFLOW)
            return amount * factor
        }
    }
}
