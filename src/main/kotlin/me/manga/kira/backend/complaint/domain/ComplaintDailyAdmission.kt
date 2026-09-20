package me.manga.kira.backend.complaint.domain

/** Database-UTC day is supplied by the locked persistence adapter, never read from an application-zone clock here. */
data class ComplaintDailyAdmission(val utcEpochDay: Long?, val count: Long, val dailyLimit: Long) {
    init {
        if (count < 0 || dailyLimit < 0 || count > dailyLimit) rejectCapacity(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET)
        if (utcEpochDay == null && count != 0L) rejectCapacity(ComplaintCapacityFailureCode.INVALID_DAILY_BUCKET)
    }

    /** The writer calls this only for a newly inserted permanent identity, never for an exact retry. */
    fun admitNewIdentity(databaseUtcEpochDay: Long): ComplaintDailyAdmission {
        val laterDay = utcEpochDay == null || databaseUtcEpochDay > utcEpochDay
        val effectiveDay = if (laterDay) databaseUtcEpochDay else utcEpochDay
        val priorCount = if (laterDay) 0L else count
        if (priorCount >= dailyLimit) rejectCapacity(ComplaintCapacityFailureCode.DAILY_LIMIT_REACHED)
        // The preceding comparison proves +1 fits, even when the configured limit is Long.MAX_VALUE.
        return ComplaintDailyAdmission(effectiveDay, priorCount + 1L, dailyLimit)
    }

    override fun toString(): String = "ComplaintDailyAdmission(redacted)"
}
