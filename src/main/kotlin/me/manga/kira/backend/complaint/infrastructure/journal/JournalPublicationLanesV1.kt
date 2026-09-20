package me.manga.kira.backend.complaint.infrastructure.journal

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.JournalRemoteCapacityV1
import me.manga.kira.backend.complaint.infrastructure.CommittedTestOwnerDeleteWork
import me.manga.kira.backend.security.TestOwnerDeleteCodecAttemptV1
import me.manga.kira.backend.complaint.infrastructure.CommittedOwnerDeleteAllWork
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogEpochSealCustodyV1
import me.manga.kira.backend.complaint.infrastructure.catalog.ReleasedCutoffPublicationV1
import me.manga.kira.backend.complaint.infrastructure.catalog.VersionBoundEpochSealAcquisitionV1
import me.manga.kira.backend.security.JournalCodecAttemptV1
import me.manga.kira.backend.security.ComplaintJournalDeletionKindV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealCustodyV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Explicit, shared in-process J custody, not a DB permit, distributed fence or runtime authority.
 * Retain this same owner across factory replacement. No configured-N allocation, worker or queue.
 * The lock protects bookkeeping only: never AUTH, provider construction/calls or resource close.
 */
internal class JournalPublicationLanesV1 private constructor(
    private val journal: ComplaintJournalConfigurationV1?,
    private val limits: JournalRemoteCapacityV1,
) : AutoCloseable {
    constructor(journal: ComplaintJournalConfigurationV1) : this(journal, journal.declaration().limits.capacity)
    constructor(journal: TestOwnerDeleteJournalConfigurationV1) : this(null, journal.declaration().limits.capacity) {
        retainTestJournal(journal)
    }

    private var testJournal: TestOwnerDeleteJournalConfigurationV1? = null
    private val testPrivacy = HashSet<TestOwnerDeleteReservation>()
    private val testAllPrivacy = HashSet<TestOwnerDeleteAllReservation>()
    private val lock = ReentrantLock()
    private val routine = HashSet<RoutineReservation>()
    private val privacy = HashSet<OwnerDeleteAllReservation>()
    private val cutoff = HashSet<OwnerDeleteAllReservation>()
    private val seals = HashSet<CatalogEpochSealCustodyV1>()
    private val testSeals = HashSet<TestOrdinarySealCustodyV1>()
    private var stopping = false

    internal fun requireJournal(expected: ComplaintJournalConfigurationV1) = requireJournalPublication(journal === expected)

    /** Shared accounting registration only. It neither authenticates a TEST namespace nor enlarges N/R. */
    internal fun retainTestJournal(selected: TestOwnerDeleteJournalConfigurationV1) {
        val capacity = selected.declaration().limits.capacity
        requireJournalPublication(capacity.maximumPublicationLanes == limits.maximumPublicationLanes && capacity.routinePublicationLanes == limits.routinePublicationLanes)
        lock.withLock { requireJournalPublication(!stopping && (testJournal == null || testJournal === selected)); testJournal = selected }
    }

    internal fun requireTestJournal(selected: TestOwnerDeleteJournalConfigurationV1) = lock.withLock { requireJournalPublication(testJournal === selected) }

    internal fun tryTestOwnerDelete(factory: TestOwnerDeleteJournalPublisherFactoryV1): TestOwnerDeleteReservation? {
        if (!lock.tryLock()) return null
        try {
            if (stopping || factory.isClosed() || routineCount() + privacy.size + testPrivacy.size + testAllPrivacy.size >= limits.maximumPublicationLanes.toLong()) return null
            factory.requireLane(this)
            requireTestJournal(factory.journalConfiguration())
            return TestOwnerDeleteReservation(factory).also { testPrivacy.add(it) }
        } finally {
            lock.unlock()
        }
    }

    private fun requireRunning(owner: TestOwnerDeleteReservation) = lock.withLock {
        requireJournalPublication(!stopping && !owner.factory.isClosed() && owner in testPrivacy)
    }

    private fun release(owner: TestOwnerDeleteReservation): Boolean = lock.withLock {
        testPrivacy.remove(owner)
        !stopping && !owner.factory.isClosed()
    }

    internal fun closeFactory(factory: TestOwnerDeleteJournalPublisherFactoryV1) {
        val owned = lock.withLock { testPrivacy.filter { it.factory === factory } }
        closeOwners(owned)
    }

    internal fun tryTestOwnerDeleteAll(factory: TestOwnerDeleteAllJournalPublisherFactoryV1): TestOwnerDeleteAllReservation? {
        if (!lock.tryLock()) return null
        try {
            if (stopping || factory.isClosed() || routineCount() + privacy.size + testPrivacy.size + testAllPrivacy.size >= limits.maximumPublicationLanes.toLong()) return null
            factory.requireLane(this)
            requireTestJournal(factory.journalConfiguration())
            return TestOwnerDeleteAllReservation(factory).also { testAllPrivacy.add(it) }
        } finally {
            lock.unlock()
        }
    }

    private fun requireRunning(owner: TestOwnerDeleteAllReservation) = lock.withLock {
        requireJournalPublication(!stopping && !owner.factory.isClosed() && owner in testAllPrivacy)
    }

    private fun release(owner: TestOwnerDeleteAllReservation): Boolean = lock.withLock {
        testAllPrivacy.remove(owner)
        !stopping && !owner.factory.isClosed()
    }

    internal fun closeFactory(factory: TestOwnerDeleteAllJournalPublisherFactoryV1) {
        val owned = lock.withLock { testAllPrivacy.filter { it.factory === factory } }
        closeOwners(owned)
    }

    /** Unstarted accounting-only reservation; the separate private receiptless path below owns actual routine publication. */
    fun tryRoutinePublication(): RoutineReservation? {
        if (!lock.tryLock()) return null
        try {
            if (stopping || (privacy.isNotEmpty() || testPrivacy.isNotEmpty() || testAllPrivacy.isNotEmpty()) || routineCount() >= limits.routinePublicationLanes) return null
            return RoutineReservation().also { routine.add(it) }
        } finally {
            lock.unlock()
        }
    }

    internal fun tryOwnerDeleteAll(factory: OwnerDeleteAllJournalPublisherFactoryV1): OwnerDeleteAllReservation? {
        if (!lock.tryLock()) return null
        try {
            // Widen BEFORE addition: the validated J permits limits up to Int.MAX_VALUE.
            if (stopping || factory.isClosed() || routineCount() + privacy.size + testPrivacy.size + testAllPrivacy.size >= limits.maximumPublicationLanes.toLong()) return null
            factory.requireLane(this)
            factory.requireJournal(checkNotNull(journal))
            return OwnerDeleteAllReservation(factory, routineOwner = false).also { privacy.add(it) }
        } finally {
            lock.unlock()
        }
    }

    internal fun tryCutoff(factory: OwnerDeleteAllJournalPublisherFactoryV1): OwnerDeleteAllReservation? {
        if (!lock.tryLock()) return null
        try {
            if (stopping || factory.isClosed() || (privacy.isNotEmpty() || testPrivacy.isNotEmpty() || testAllPrivacy.isNotEmpty())) return null
            if (routineCount() >= limits.routinePublicationLanes) return null
            factory.requireLane(this)
            factory.requireJournal(checkNotNull(journal))
            return OwnerDeleteAllReservation(factory, routineOwner = true).also { cutoff.add(it) }
        } finally {
            lock.unlock()
        }
    }

    internal fun tryEpochSeal(owner: CatalogEpochSealCustodyV1): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (stopping || owner.acquisitionStopped() || (privacy.isNotEmpty() || testPrivacy.isNotEmpty() || testAllPrivacy.isNotEmpty())) return false
            if (routineCount() >= limits.routinePublicationLanes) return false
            owner.requireLane(this)
            return seals.add(owner)
        } finally {
            lock.unlock()
        }
    }

    internal fun requireEpochSealRunning(owner: CatalogEpochSealCustodyV1) = lock.withLock {
        requireJournalPublication(!stopping && !owner.acquisitionStopped() && owner in seals)
    }

    internal fun releaseEpochSeal(owner: CatalogEpochSealCustodyV1) {
        owner.requireClosedLane(this) // Native close already returned; no owner monitor while holding the registry lock.
        lock.withLock { seals.remove(owner) }
    }

    internal fun closeEpochSealAcquisition(acquisition: VersionBoundEpochSealAcquisitionV1) {
        val owned = lock.withLock { seals.filter { it.belongsTo(acquisition) } }
        closeOwners(owned)
    }

    internal fun tryTestOrdinarySeal(owner: TestOrdinarySealCustodyV1): Boolean {
        if (!lock.tryLock()) return false
        try {
            if (stopping || owner.acquisitionStopped() || privacy.isNotEmpty() || testPrivacy.isNotEmpty() || testAllPrivacy.isNotEmpty() || routineCount() >= limits.routinePublicationLanes) return false
            owner.requireLane(this)
            return testSeals.add(owner)
        } finally { lock.unlock() }
    }
    internal fun requireTestOrdinarySealRunning(owner: TestOrdinarySealCustodyV1) = lock.withLock {
        requireJournalPublication(!stopping && !owner.acquisitionStopped() && owner in testSeals)
    }
    internal fun releaseTestOrdinarySeal(owner: TestOrdinarySealCustodyV1) {
        owner.requireClosedLane(this)
        lock.withLock { testSeals.remove(owner) }
    }
    internal fun closeTestOrdinarySealAcquisition(acquisition: VersionBoundTestOrdinarySealV1) {
        val owned = lock.withLock { testSeals.filter { it.belongsTo(acquisition) } }
        closeOwners(owned)
    }

    // Call only under the registry lock. Widen before every addition, including held/failed seal owners.
    private fun routineCount(): Long = routine.size.toLong() + cutoff.size + seals.size + testSeals.size

    fun activeOwners(): JournalPublicationLaneSnapshotV1 = lock.withLock { JournalPublicationLaneSnapshotV1(routineCount().toInt(), privacy.size + testPrivacy.size + testAllPrivacy.size) }

    private fun requireRunning(owner: OwnerDeleteAllReservation) = lock.withLock {
        requireJournalPublication(!stopping && !owner.factory.isClosed() && (if (owner.routineOwner) owner in cutoff else owner in privacy))
    }

    /** Called only by the concrete reservation after no future start is possible and actual close returned. */
    private fun release(owner: OwnerDeleteAllReservation): Boolean = lock.withLock {
        if (owner.routineOwner) cutoff.remove(owner) else privacy.remove(owner)
        !stopping && !owner.factory.isClosed()
    }

    internal fun closeFactory(factory: OwnerDeleteAllJournalPublisherFactoryV1) {
        val owned = lock.withLock { (privacy + cutoff).filter { it.factory === factory } }
        closeOwners(owned)
    }

    override fun close() {
        val owned = lock.withLock {
            stopping = true
            routine.clear() // Routine reservations have no remote entry and can never start one later.
            buildList<AutoCloseable> {
                addAll(privacy)
                addAll(testPrivacy)
                addAll(testAllPrivacy)
                addAll(cutoff)
                addAll(seals)
                addAll(testSeals)
            }
        }
        closeOwners(owned)
    }

    private fun closeOwners(owned: List<AutoCloseable>) {
        var failure: Throwable? = null
        owned.forEach { owner ->
            val closing = runCatching(owner::close).exceptionOrNull()
            if (closing != null && replaceJournalPublicationFailure(failure, closing)) failure = closing
        }
        failure?.let { throw it }
    }

    internal inner class RoutineReservation internal constructor() : AutoCloseable {
        override fun close() = lock.withLock {
            routine.remove(this)
            Unit
        }

        override fun toString(): String = "RoutineJournalReservationV1(unstarted,accounting-only)"
    }

    /**
     * The registry retains this actual construction owner before AUTH or any KMS/S3 construction.
     * publish is one-shot and includes close. No caller assertion/callback can release started work.
     */
    internal inner class OwnerDeleteAllReservation internal constructor(
        internal val factory: OwnerDeleteAllJournalPublisherFactoryV1,
        internal val routineOwner: Boolean,
    ) : AutoCloseable {
        private val lifecycle = Any()
        private val construction = OwnerDeleteAllJournalPublisherV1.Construction()
        private var state = PublicationOwnerStateV1.RESERVED
        private var stopRequested = false
        private var caller: Thread? = null
        private var attempt: JournalCodecAttemptV1? = null
        private var closeFailure: Throwable? = null

        fun publish(work: CommittedOwnerDeleteAllWork.Prepared): OwnerDeleteAllJournalReadbackV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                synchronized(lifecycle) {
                    requireJournalPublication(!routineOwner && state == PublicationOwnerStateV1.RESERVED && !stopRequested)
                    requireRunning(this)
                    state = PublicationOwnerStateV1.RUNNING
                    caller = Thread.currentThread()
                }
                withJournalPublicationCleanup(
                    {
                        factory.requirePrepared(work)
                        val time = factory.startAttempt().also { synchronized(lifecycle) { attempt = it } }
                        val publisher = factory.construct(this, construction, time)
                        requireConstructing(factory, construction, time)
                        publisher.publish(work, time).also { requireConstructing(factory, construction, time) }
                    },
                    ::finishPublication,
                )
            }

        /** Fixed receiptless ordinary path. Retains the SAME construction/cleanup lifecycle as API publication. */
        internal fun publishCutoff(work: ReleasedCutoffPublicationV1, originalBudget: PersistenceTimeBudget): OwnerDeleteAllJournalReadbackV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                synchronized(lifecycle) {
                    requireJournalPublication(routineOwner && state == PublicationOwnerStateV1.RESERVED && !stopRequested)
                    requireRunning(this)
                    state = PublicationOwnerStateV1.RUNNING
                    caller = Thread.currentThread()
                }
                withJournalPublicationCleanup(
                    {
                        val time = factory.startCutoffAttempt(work, originalBudget).also { synchronized(lifecycle) { attempt = it } }
                        val publisher = factory.construct(this, construction, time)
                        requireConstructing(factory, construction, time)
                        publisher.publishCutoff(work, time).also { requireConstructing(factory, construction, time) }
                    },
                    ::finishPublication,
                )
            }

        internal fun requireConstructing(
            expected: OwnerDeleteAllJournalPublisherFactoryV1,
            custody: OwnerDeleteAllJournalPublisherV1.Construction,
            time: JournalCodecAttemptV1,
        ) = synchronized(lifecycle) {
            requireJournalPublication(
                factory === expected && construction === custody && attempt === time &&
                    state == PublicationOwnerStateV1.RUNNING && !stopRequested && caller === Thread.currentThread(),
            )
            requireRunning(this)
            time.remainingMillis(1)
        }

        private fun finishPublication() {
            synchronized(lifecycle) { state = PublicationOwnerStateV1.CLEANING }
            // Never let an expired clock, interruption or stop request skip actual resource cleanup.
            val closing = runCatching { journalPublicationClose { construction.close() } }
            val timing = runCatching { journalPublicationCall { attempt?.remainingMillis(1) } }
            val stopped = synchronized(lifecycle) {
                caller = null
                if (closing.isSuccess) {
                    state = PublicationOwnerStateV1.CLOSED
                    val running = release(this)
                    stopRequested || !running
                } else {
                    closeFailure = closing.exceptionOrNull()
                    state = PublicationOwnerStateV1.RETAINED
                    stopRequested
                }
            }
            withJournalPublicationCleanup(
                {
                    timing.getOrThrow()
                    requireJournalPublication(!stopped)
                },
                { closing.getOrThrow() },
            )
        }

        override fun close() = synchronized(lifecycle) {
            stopRequested = true
            when (state) {
                PublicationOwnerStateV1.RESERVED -> {
                    // Linearized against RUNNING: an idle release permanently forbids later construction.
                    state = PublicationOwnerStateV1.CLOSED
                    release(this)
                }

                PublicationOwnerStateV1.CLOSED -> Unit

                PublicationOwnerStateV1.RETAINED -> throw checkNotNull(closeFailure)

                PublicationOwnerStateV1.RUNNING, PublicationOwnerStateV1.CLEANING -> {
                    // The original synchronous caller owns cleanup. No cancel/timeout is a native return.
                    throw JournalPublicationExceptionV1(JournalPublicationFailureV1.CLEANUP_FAILURE)
                }
            }
            Unit
        }

        override fun toString(): String = "OwnerDeleteAllJournalReservationV1(dormant,owned,redacted,no-runtime-authority)"
    }

    internal inner class TestOwnerDeleteReservation internal constructor(
        internal val factory: TestOwnerDeleteJournalPublisherFactoryV1,
    ) : AutoCloseable {
        private val lifecycle = Any()
        private val construction = TestOwnerDeleteJournalPublisherV1.Construction()
        private var state = PublicationOwnerStateV1.RESERVED
        private var stopRequested = false
        private var caller: Thread? = null
        private var attempt: TestOwnerDeleteCodecAttemptV1? = null
        private var closeFailure: Throwable? = null

        fun publish(work: CommittedTestOwnerDeleteWork.Prepared): TestOwnerDeleteJournalReadbackV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                synchronized(lifecycle) {
                    requireJournalPublication(state == PublicationOwnerStateV1.RESERVED && !stopRequested)
                    requireRunning(this)
                    state = PublicationOwnerStateV1.RUNNING
                    caller = Thread.currentThread()
                }
                withJournalPublicationCleanup(
                    {
                        factory.requirePrepared(work)
                        val time = factory.startAttempt().also { synchronized(lifecycle) { attempt = it } }
                        val publisher = factory.construct(this, construction, time)
                        requireConstructing(factory, construction, time)
                        publisher.publish(work, time).also { requireConstructing(factory, construction, time) }
                    },
                    ::finishPublication,
                )
            }

        internal fun readExisting(tuple: me.manga.kira.backend.security.TestOwnerDeleteJournalTupleV1, targetId: java.util.UUID, routingKeyId: String): TestOwnerDeleteJournalReadbackV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                factory.requireRecoveryRead() // A registered continuation publishes only its exact retained primary work.
                requireJournalPublication(tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE)
                synchronized(lifecycle) {
                    requireJournalPublication(state == PublicationOwnerStateV1.RESERVED && !stopRequested)
                    requireRunning(this)
                    state = PublicationOwnerStateV1.RUNNING
                    caller = Thread.currentThread()
                }
                withJournalPublicationCleanup({
                    val time = factory.startAttempt().also { synchronized(lifecycle) { attempt = it } }
                    val publisher = factory.construct(this, construction, time)
                    requireConstructing(factory, construction, time)
                    publisher.readExisting(tuple, targetId, routingKeyId, time).also { requireConstructing(factory, construction, time) }
                }, ::finishPublication)
            }

        internal fun requireConstructing(
            expected: TestOwnerDeleteJournalPublisherFactoryV1,
            custody: TestOwnerDeleteJournalPublisherV1.Construction,
            time: TestOwnerDeleteCodecAttemptV1,
        ) = synchronized(lifecycle) {
            requireJournalPublication(
                factory === expected && construction === custody && attempt === time &&
                    state == PublicationOwnerStateV1.RUNNING && !stopRequested && caller === Thread.currentThread(),
            )
            requireRunning(this)
            time.remainingMillis(1)
        }

        private fun finishPublication() {
            synchronized(lifecycle) { state = PublicationOwnerStateV1.CLEANING }
            // Never let an expired clock, interruption or stop request skip actual resource cleanup.
            val closing = runCatching { journalPublicationClose { construction.close() } }
            val timing = runCatching { journalPublicationCall { attempt?.remainingMillis(1) } }
            val stopped = synchronized(lifecycle) {
                caller = null
                if (closing.isSuccess) {
                    state = PublicationOwnerStateV1.CLOSED
                    val running = release(this)
                    stopRequested || !running
                } else {
                    closeFailure = closing.exceptionOrNull()
                    state = PublicationOwnerStateV1.RETAINED
                    stopRequested
                }
            }
            withJournalPublicationCleanup(
                {
                    timing.getOrThrow()
                    requireJournalPublication(!stopped)
                },
                { closing.getOrThrow() },
            )
        }

        override fun close() = synchronized(lifecycle) {
            stopRequested = true
            when (state) {
                PublicationOwnerStateV1.RESERVED -> {
                    // Linearized against RUNNING: an idle release permanently forbids later construction.
                    state = PublicationOwnerStateV1.CLOSED
                    release(this)
                }

                PublicationOwnerStateV1.CLOSED -> Unit

                PublicationOwnerStateV1.RETAINED -> throw checkNotNull(closeFailure)

                PublicationOwnerStateV1.RUNNING, PublicationOwnerStateV1.CLEANING -> {
                    // The original synchronous caller owns cleanup. No cancel/timeout is a native return.
                    throw JournalPublicationExceptionV1(JournalPublicationFailureV1.CLEANUP_FAILURE)
                }
            }
            Unit
        }

        override fun toString(): String = "TestOwnerDeleteJournalReservationV1(dormant,owned,redacted,no-runtime-authority)"
    }

    internal inner class TestOwnerDeleteAllReservation internal constructor(
        internal val factory: TestOwnerDeleteAllJournalPublisherFactoryV1,
    ) : AutoCloseable {
        private val lifecycle = Any()
        private val construction = TestOwnerDeleteAllJournalPublisherV1.Construction()
        private var state = PublicationOwnerStateV1.RESERVED
        private var stopRequested = false
        private var caller: Thread? = null
        private var attempt: TestOwnerDeleteCodecAttemptV1? = null
        private var closeFailure: Throwable? = null

        fun publish(work: CommittedOwnerDeleteAllWork.Prepared): TestOwnerDeleteJournalReadbackV1 =
            journalPublicationCall(JournalPublicationFailureV1.INVALID_BINDING) {
                requireConnectionFree()
                synchronized(lifecycle) {
                    requireJournalPublication(state == PublicationOwnerStateV1.RESERVED && !stopRequested)
                    requireRunning(this)
                    state = PublicationOwnerStateV1.RUNNING
                    caller = Thread.currentThread()
                }
                withJournalPublicationCleanup(
                    {
                        factory.requirePrepared(work)
                        val time = factory.startAttempt().also { synchronized(lifecycle) { attempt = it } }
                        val publisher = factory.construct(this, construction, time)
                        requireConstructing(factory, construction, time)
                        publisher.publish(work, time).also { requireConstructing(factory, construction, time) }
                    },
                    ::finishPublication,
                )
            }

        internal fun requireConstructing(
            expected: TestOwnerDeleteAllJournalPublisherFactoryV1,
            custody: TestOwnerDeleteAllJournalPublisherV1.Construction,
            time: TestOwnerDeleteCodecAttemptV1,
        ) = synchronized(lifecycle) {
            requireJournalPublication(
                factory === expected && construction === custody && attempt === time &&
                    state == PublicationOwnerStateV1.RUNNING && !stopRequested && caller === Thread.currentThread(),
            )
            requireRunning(this)
            time.remainingMillis(1)
        }

        private fun finishPublication() {
            synchronized(lifecycle) { state = PublicationOwnerStateV1.CLEANING }
            // Never let an expired clock, interruption or stop request skip actual resource cleanup.
            val closing = runCatching { journalPublicationClose { construction.close() } }
            val timing = runCatching { journalPublicationCall { attempt?.remainingMillis(1) } }
            val stopped = synchronized(lifecycle) {
                caller = null
                if (closing.isSuccess) {
                    state = PublicationOwnerStateV1.CLOSED
                    val running = release(this)
                    stopRequested || !running
                } else {
                    closeFailure = closing.exceptionOrNull()
                    state = PublicationOwnerStateV1.RETAINED
                    stopRequested
                }
            }
            withJournalPublicationCleanup(
                {
                    timing.getOrThrow()
                    requireJournalPublication(!stopped)
                },
                { closing.getOrThrow() },
            )
        }

        override fun close() = synchronized(lifecycle) {
            stopRequested = true
            when (state) {
                PublicationOwnerStateV1.RESERVED -> {
                    // Linearized against RUNNING: an idle release permanently forbids later construction.
                    state = PublicationOwnerStateV1.CLOSED
                    release(this)
                }

                PublicationOwnerStateV1.CLOSED -> Unit

                PublicationOwnerStateV1.RETAINED -> throw checkNotNull(closeFailure)

                PublicationOwnerStateV1.RUNNING, PublicationOwnerStateV1.CLEANING -> {
                    // The original synchronous caller owns cleanup. No cancel/timeout is a native return.
                    throw JournalPublicationExceptionV1(JournalPublicationFailureV1.CLEANUP_FAILURE)
                }
            }
            Unit
        }

        override fun toString(): String = "TestOwnerDeleteAllJournalReservationV1(dormant,owned,redacted,no-runtime-authority)"
    }

    override fun toString(): String = "JournalPublicationLanesV1(shared-in-process,redacted,no-runtime-authority)"
}

private enum class PublicationOwnerStateV1 { RESERVED, RUNNING, CLEANING, RETAINED, CLOSED }

/** One locked observation for diagnostics/tests only, never authorization or a quiescence receipt. */
internal data class JournalPublicationLaneSnapshotV1(val routineOwners: Int, val privacyOwners: Int) {
    val totalOwners: Long get() = routineOwners.toLong() + privacyOwners.toLong()
}
