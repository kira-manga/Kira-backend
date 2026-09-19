package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.CancellationException

/**
 * Caller-retained Linux-only byte custody for the one fixed TEST activation allocation, with no deleting lifecycle.
 * Call retain BEFORE open and keep this original owner through close, including failed construction.
 *
 * Provisioning must independently bind this exact durable root outside DB restore/deployment/temporary
 * custody, with independent recovery inputs. Choosing another root is NOT global allocation enforcement.
 * Root/custodian/mount replacement, rollback and other same-JVM code opening our lock inode are outside
 * this boundary. Software fsync/reread is not hardware qualification or evidence of an earlier close.
 *
 * Existing bytes and absence are historical observations only. No method interprets approval, authorizes
 * re-sign/PUT, recreates a verifier handle, or proves an entire stage completed. No secrets belong here.
 * A failed operation closes actual resources where possible, poisons this owner and never repairs files.
 */
internal class CatalogTestRunActivationReleaseCustodyV1 private constructor(
    root: Path,
    private var allocationBytes: ByteArray?,
    private val originalBudget: PersistenceTimeBudget,
) : AutoCloseable {
    internal val allocationDirectoryName: String = "test-run-activation"
    internal val leaves: List<CatalogTestRunActivationReleaseLeafV1> = CatalogTestRunActivationReleaseLeafV1.entries.toList()
    private val caller = Thread.currentThread()
    private val files = LinuxTestRunActivationReleaseFilesV1(this, root)
    private var state = State.RETAINED
    private var claimedRootKey: Any? = null
    private var closeIssued = false
    private var closeFailure: CatalogTestRunActivationCustodyFailureV1? = null

    fun open(): CatalogTestRunActivationCustodyObservationV1 = openAllocation(existingOnly = false)

    /** Later stages may acquire existing custody only; absence never initializes a replacement allocation or lock. */
    fun openExisting(): CatalogTestRunActivationCustodyObservationV1 = openAllocation(existingOnly = true)

    /** Historical byte discovery under the original permanent lock; never creates or repairs missing custody. */
    internal fun discoverExisting(): ByteArray = perform(State.RETAINED) {
        requireTestActivationCustody(allocationBytes == null, CatalogTestRunActivationCustodyFailureV1.INVALID_STATE)
        state = State.OPENING
        claim(files.openRoot())
        val actual = files.discoverExistingAllocation()
        allocationBytes = actual.copyOf()
        state = State.OPEN
        actual
    }

    private fun openAllocation(existingOnly: Boolean): CatalogTestRunActivationCustodyObservationV1 = perform(State.RETAINED) {
        state = State.OPENING
        claim(files.openRoot()) // Before opening ANY descriptor for the permanent lock inode.
        val observation = files.openAllocation(checkNotNull(allocationBytes), existingOnly)
        state = State.OPEN
        observation
    }

    fun putIfAbsent(leaf: CatalogTestRunActivationReleaseLeafV1, bytes: ByteArray): CatalogTestRunActivationCustodyObservationV1 = perform(State.OPEN) {
        requireTestActivationCustody(leaf in leaves && bytes.size in 1..leaf.maximumBytes, CatalogTestRunActivationCustodyFailureV1.INVALID_INPUT)
        files.putIfAbsent(checkNotNull(allocationBytes), leaf, bytes.copyOf())
    }

    /** A null is only absence under this held lock, not evidence that an effect was never attempted. */
    fun read(leaf: CatalogTestRunActivationReleaseLeafV1): ByteArray? = perform(State.OPEN) {
        requireTestActivationCustody(leaf in leaves, CatalogTestRunActivationCustodyFailureV1.INVALID_INPUT)
        files.read(checkNotNull(allocationBytes), leaf)
    }

    /**
     * Only the original connection-free caller closes. Expiry/interruption cannot skip actual cleanup;
     * the same original budget is checked AFTER it. An uncertain close is sticky and never retried.
     */
    override fun close() {
        requireCaller()
        requireConnectionFree()
        if (!closeIssued) {
            closeIssued = true
            closeFailure = CatalogTestRunActivationCustodyFailureV1.IO_UNCERTAIN
            state = State.CLOSED
            val disposal = closeFiles()
            val observation = runCatching {
                closeFailure = if (files.cleanupComplete()) completionFailure() else CatalogTestRunActivationCustodyFailureV1.CLEANUP_UNCERTAIN
            }.exceptionOrNull()
            val problem = if (observation == null) disposal else preferSignerRotationCleanup(disposal, observation)
            if (problem != null) {
                closeFailure = CatalogTestRunActivationCustodyFailureV1.CLEANUP_UNCERTAIN
                throwSignal(problem)
                throw CatalogTestRunActivationCustodyExceptionV1(failureCode(problem))
            }
        }
        closeFailure?.let { throw CatalogTestRunActivationCustodyExceptionV1(it) }
    }

    internal fun checkpoint(candidate: LinuxTestRunActivationReleaseFilesV1) {
        requireCaller()
        requireTestActivationCustody(
            files === candidate && (state == State.OPENING || state == State.OPEN),
            CatalogTestRunActivationCustodyFailureV1.INVALID_STATE,
        )
        checkpoint()
    }

    internal fun requireLockClaim(candidate: LinuxTestRunActivationReleaseFilesV1, key: Any) {
        checkpoint(candidate)
        synchronized(claims) {
            requireTestActivationCustody(claimedRootKey == key && claims[key] === this, CatalogTestRunActivationCustodyFailureV1.LOCK_UNAVAILABLE)
        }
    }

    private fun checkpoint() {
        requireCaller()
        requireConnectionFree()
        requireTestActivationCustody(!Thread.currentThread().isInterrupted, CatalogTestRunActivationCustodyFailureV1.INTERRUPTED)
        try {
            originalBudget.remainingMillis(BUDGET_CEILING_MILLIS)
        } catch (_: PersistenceBoundaryException) {
            throw CatalogTestRunActivationCustodyExceptionV1(CatalogTestRunActivationCustodyFailureV1.TIME_BUDGET)
        }
    }

    private fun requireCaller() = requireTestActivationCustody(caller === Thread.currentThread(), CatalogTestRunActivationCustodyFailureV1.WRONG_CALLER)

    // Catch every failure to finish actual cleanup first; never replace fatal/cancellation/interruption with an ordinary custody reason.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun <T> perform(expected: State, action: () -> T): T {
        requireCaller() // An unrelated caller cannot poison the owner or steal cleanup.
        requireConnectionFree()
        try {
            requireTestActivationCustody(state == expected, CatalogTestRunActivationCustodyFailureV1.INVALID_STATE)
            checkpoint()
            val result = action()
            checkpoint()
            return result
        } catch (failure: Throwable) {
            state = State.FAILED
            preserveInterruption(failure)
            val cleanup = closeFiles()
            throwSignal(if (cleanup == null) failure else preferSignerRotationCleanup(failure, cleanup))
            val code = if (!files.cleanupComplete()) CatalogTestRunActivationCustodyFailureV1.CLEANUP_UNCERTAIN else failureCode(failure)
            throw CatalogTestRunActivationCustodyExceptionV1(code)
        }
    }

    // Called after actual cleanup: sanitize ordinary budget failures, but never downgrade a fatal Error.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun completionFailure(): CatalogTestRunActivationCustodyFailureV1? = try {
        checkpoint()
        null
    } catch (failure: Throwable) {
        preserveInterruption(failure)
        throwSignal(failure)
        failureCode(failure)
    }

    private fun closeFiles(): Throwable? {
        val disposal = runCatching { files.closeAll()?.let { throw it } }.exceptionOrNull()
        val settlement = runCatching(::releaseClaimAfterActualCleanup).exceptionOrNull()
        return if (settlement == null) disposal else preferSignerRotationCleanup(disposal, settlement)
    }

    private fun throwSignal(problem: Throwable) {
        val signal = signerRotationSignal(problem)
        if (signal is Error || signal is CancellationException || signal is InterruptedException) throw signal
    }

    private fun claim(key: Any) = synchronized(claims) {
        requireTestActivationCustody(!claims.containsKey(key) && claims.size < MAX_ACTIVE_ROOTS, CatalogTestRunActivationCustodyFailureV1.LOCK_UNAVAILABLE)
        claims[key] = this
        claimedRootKey = key
    }

    private fun releaseClaimAfterActualCleanup() {
        if (!files.cleanupComplete()) return
        synchronized(claims) {
            val key = claimedRootKey
            if (key != null && claims[key] === this) {
                claims.remove(key)
                claimedRootKey = null
            }
        }
    }

    override fun toString(): String = "CatalogTestRunActivationReleaseCustodyV1(retained,redacted,no-capability)"

    private enum class State { RETAINED, OPENING, OPEN, FAILED, CLOSED }

    companion object {
        private const val BUDGET_CEILING_MILLIS = 30_000L
        private const val MAX_ACTIVE_ROOTS = 16
        private const val MAX_PATH_DEPTH = 64
        private const val MAX_PATH_CHARS = 2048

        // Closing a competing channel can drop POSIX process-associated locks held by another channel.
        // Keep the ORIGINAL owner strongly claimed by actual (device,inode) fileKey, including uncertainty.
        private val claims = mutableMapOf<Any, CatalogTestRunActivationReleaseCustodyV1>()

        /** Bounded capture only: no filesystem call, construction/open, lock, or fresh time budget. */
        fun retain(root: Path, allocationBytes: ByteArray, originalBudget: PersistenceTimeBudget): CatalogTestRunActivationReleaseCustodyV1 {
            requireTestActivationCustody(
                root.fileSystem === FileSystems.getDefault() && root.isAbsolute && root == root.normalize() &&
                    root.nameCount in 1..MAX_PATH_DEPTH && root.toString().length <= MAX_PATH_CHARS &&
                    allocationBytes.size in 1..4096,
                CatalogTestRunActivationCustodyFailureV1.INVALID_INPUT,
            )
            return CatalogTestRunActivationReleaseCustodyV1(root, allocationBytes.copyOf(), originalBudget)
        }

        /** Same bounded root capture, but there is deliberately no expected new-token allocation to initialize. */
        internal fun retainExisting(root: Path, originalBudget: PersistenceTimeBudget): CatalogTestRunActivationReleaseCustodyV1 {
            requireTestActivationCustody(
                root.fileSystem === FileSystems.getDefault() && root.isAbsolute && root == root.normalize() &&
                    root.nameCount in 1..MAX_PATH_DEPTH && root.toString().length <= MAX_PATH_CHARS,
                CatalogTestRunActivationCustodyFailureV1.INVALID_INPUT,
            )
            return CatalogTestRunActivationReleaseCustodyV1(root, null, originalBudget)
        }

        internal fun preserveInterruption(failure: Throwable) {
            if (failure is InterruptedException || failure is InterruptedIOException || failure is ClosedByInterruptException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun failureCode(failure: Throwable): CatalogTestRunActivationCustodyFailureV1 = when (failure) {
            is CatalogTestRunActivationCustodyExceptionV1 -> failure.code
            is InterruptedException, is InterruptedIOException, is ClosedByInterruptException -> CatalogTestRunActivationCustodyFailureV1.INTERRUPTED
            else -> CatalogTestRunActivationCustodyFailureV1.IO_UNCERTAIN
        }
    }
}
