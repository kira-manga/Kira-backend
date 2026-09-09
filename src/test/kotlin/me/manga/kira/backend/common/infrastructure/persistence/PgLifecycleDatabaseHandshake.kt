package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID
import java.util.concurrent.locks.LockSupport

internal enum class PgLifecycleDatabaseParty {
    PARENT,
    CHILD,
}

internal enum class PgLifecycleDatabasePhase(val producer: PgLifecycleDatabaseParty) {
    PREPARED(PgLifecycleDatabaseParty.CHILD),
    START(PgLifecycleDatabaseParty.PARENT),
    RETAINED(PgLifecycleDatabaseParty.CHILD),
    ARRIVAL_CONFIRMED(PgLifecycleDatabaseParty.PARENT),
    FAULT_ARMED(PgLifecycleDatabaseParty.CHILD),
    DEADLINE_DRIVER_ACTIVE(PgLifecycleDatabaseParty.CHILD),
    DEADLINE_OBSERVED(PgLifecycleDatabaseParty.PARENT),
    LATE_RAW_RETAINED(PgLifecycleDatabaseParty.CHILD),
    LIVE(PgLifecycleDatabaseParty.CHILD),
    STALE_REJECTED(PgLifecycleDatabaseParty.CHILD),
    RETIRE(PgLifecycleDatabaseParty.PARENT),
    RETIRED(PgLifecycleDatabaseParty.CHILD),
    ABSENCE_CONFIRMED(PgLifecycleDatabaseParty.PARENT),
    WEAK_CLEANUP_CONFIRMED(PgLifecycleDatabaseParty.PARENT),
    OWNER_DRAINED(PgLifecycleDatabaseParty.CHILD),
    EXIT(PgLifecycleDatabaseParty.PARENT),
}

/** One finite monotonic allowance per phase; polling never replaces its original budget. */
internal class PgLifecycleDatabaseDeadline(allowanceMillis: Long = 12_000) {
    private val budget = PersistenceTimeBudget.start(allowanceMillis)

    fun checkRemaining() {
        budget.remainingMillis(1)
    }

    fun pause() = LockSupport.parkNanos(budget.remainingMillis(2) * 1_000_000)

    fun millis(ceiling: Long): Long = budget.remainingMillis(ceiling)
}

/** Exact single-use phase receipts in a private directory, not stdout/stdin polling or a raw JDBC channel. */
internal class PgLifecycleDatabaseHandshake(
    private val directory: Path,
    private val nonce: String,
    private val party: PgLifecycleDatabaseParty,
    private val case: PgLifecycleDatabaseCase = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY),
) {
    private val consumed = mutableSetOf<String>()

    init {
        check(directory.isAbsolute && Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
        check(UUID.fromString(nonce).toString() == nonce)
    }

    fun publish(phase: PgLifecycleDatabasePhase, ordinal: Int = 0) {
        check(phase.producer === party)
        requireApplicable(phase, ordinal)
        val path = path(phase, ordinal)
        check(!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { "Duplicate synthetic phase receipt." }
        val temporary = path.resolveSibling(path.fileName.toString() + ".pending")
        Files.createFile(temporary, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        Files.writeString(temporary, record(phase, ordinal), StandardOpenOption.WRITE)
        Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE)
    }

    fun await(
        phase: PgLifecycleDatabasePhase,
        ordinal: Int = 0,
        deadline: PgLifecycleDatabaseDeadline = PgLifecycleDatabaseDeadline(),
        progress: () -> Unit = {},
    ) {
        check(phase.producer !== party)
        requireApplicable(phase, ordinal)
        val path = path(phase, ordinal)
        val key = path.fileName.toString()
        check(key !in consumed) { "A phase receipt cannot be consumed twice." }
        while (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            progress()
            deadline.pause()
        }
        deadline.checkRemaining()
        progress()
        check(Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) && Files.size(path) in 1..256) { "Synthetic phase identity or framing differs." }
        val bytes = Files.newInputStream(path).use { it.readNBytes(257) }
        check(bytes.contentEquals(record(phase, ordinal).toByteArray(Charsets.US_ASCII))) { "Synthetic phase identity or framing differs." }
        check(consumed.add(key))
    }

    fun path(phase: PgLifecycleDatabasePhase, ordinal: Int): Path {
        check(ordinal in 0 until case.attempts)
        return directory.resolve("${phase.producer.name}_${ordinal}_${phase.name}.receipt")
    }

    private fun requireApplicable(phase: PgLifecycleDatabasePhase, ordinal: Int) {
        val applicable = when (phase) {
            PgLifecycleDatabasePhase.ARRIVAL_CONFIRMED, PgLifecycleDatabasePhase.FAULT_ARMED -> case.supplemental
            PgLifecycleDatabasePhase.DEADLINE_DRIVER_ACTIVE, PgLifecycleDatabasePhase.DEADLINE_OBSERVED -> case.deadlineFailure
            PgLifecycleDatabasePhase.LATE_RAW_RETAINED -> case.lateReturn
            PgLifecycleDatabasePhase.LIVE, PgLifecycleDatabasePhase.RETIRE -> case.succeeds
            PgLifecycleDatabasePhase.STALE_REJECTED -> case.succeeds && ordinal == 1
            PgLifecycleDatabasePhase.WEAK_CLEANUP_CONFIRMED -> case.originalProvider && !case.returnsRaw
            PgLifecycleDatabasePhase.ABSENCE_CONFIRMED -> !case.originalProvider || case.returnsRaw
            PgLifecycleDatabasePhase.PREPARED, PgLifecycleDatabasePhase.OWNER_DRAINED, PgLifecycleDatabasePhase.EXIT -> ordinal == 0
            else -> true
        }
        check(applicable) { "Synthetic phase is not applicable to the exact case and attempt." }
    }

    private fun record(phase: PgLifecycleDatabasePhase, ordinal: Int): String =
        "PG_DATABASE_PHASE v=2 nonce=$nonce ordinal=$ordinal phase=${phase.name} ${case.label}\n"
}

internal fun pgLifecycleDatabasePrivateDirectory(path: Path): Path {
    check(path.isAbsolute)
    return Files.createDirectory(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
}
