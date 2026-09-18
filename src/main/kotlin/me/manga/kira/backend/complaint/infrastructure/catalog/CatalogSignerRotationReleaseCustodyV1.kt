package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerRotationCapacityV1
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.FileSystems
import java.nio.file.Path
import java.util.concurrent.CancellationException

/**
 * Caller-retained, Linux-only byte custody for one fixed `rotation-overlap-2` allocation, with no deleting lifecycle.
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
internal class CatalogSignerRotationReleaseCustodyV1 private constructor(
    root: Path,
    private val allocationBytes: ByteArray,
    private val originalBudget: PersistenceTimeBudget,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val files = LinuxSignerRotationReleaseFilesV1(this, root)
    private var state = State.RETAINED
    private var claimedRootKey: Any? = null
    private var closeIssued = false
    private var closeFailure: CatalogSignerRotationCustodyFailureV1? = null

    fun open(): CatalogSignerRotationCustodyObservationV1 = openAllocation(existingOnly = false)

    /** Later stages may acquire existing custody only; absence never initializes a replacement allocation or lock. */
    fun openExisting(): CatalogSignerRotationCustodyObservationV1 = openAllocation(existingOnly = true)

    private fun openAllocation(existingOnly: Boolean): CatalogSignerRotationCustodyObservationV1 = perform(State.RETAINED) {
        state = State.OPENING
        claim(files.openRoot()) // Before opening ANY descriptor for the permanent lock inode.
        val observation = files.openAllocation(allocationBytes, existingOnly)
        state = State.OPEN
        observation
    }

    fun putIfAbsent(leaf: CatalogSignerRotationReleaseLeafV1, bytes: ByteArray): CatalogSignerRotationCustodyObservationV1 = perform(State.OPEN) {
        requireSignerRotationCustody(bytes.size in 1..leaf.maximumBytes, CatalogSignerRotationCustodyFailureV1.INVALID_INPUT)
        files.putIfAbsent(allocationBytes, leaf, bytes.copyOf())
    }

    /** A null is only absence under this held lock, not evidence that an effect was never attempted. */
    fun read(leaf: CatalogSignerRotationReleaseLeafV1): ByteArray? = perform(State.OPEN) {
        files.read(allocationBytes, leaf)
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
            closeFailure = CatalogSignerRotationCustodyFailureV1.IO_UNCERTAIN
            state = State.CLOSED
            val disposal = closeFiles()
            val observation = runCatching {
                closeFailure = if (files.cleanupComplete()) completionFailure() else CatalogSignerRotationCustodyFailureV1.CLEANUP_UNCERTAIN
            }.exceptionOrNull()
            val problem = if (observation == null) disposal else preferSignerRotationCleanup(disposal, observation)
            if (problem != null) {
                closeFailure = CatalogSignerRotationCustodyFailureV1.CLEANUP_UNCERTAIN
                throwSignal(problem)
                throw CatalogSignerRotationCustodyExceptionV1(failureCode(problem))
            }
        }
        closeFailure?.let { throw CatalogSignerRotationCustodyExceptionV1(it) }
    }

    internal fun checkpoint(candidate: LinuxSignerRotationReleaseFilesV1) {
        requireCaller()
        requireSignerRotationCustody(files === candidate && (state == State.OPENING || state == State.OPEN), CatalogSignerRotationCustodyFailureV1.INVALID_STATE)
        checkpoint()
    }

    internal fun requireLockClaim(candidate: LinuxSignerRotationReleaseFilesV1, key: Any) {
        checkpoint(candidate)
        synchronized(claims) {
            requireSignerRotationCustody(claimedRootKey == key && claims[key] === this, CatalogSignerRotationCustodyFailureV1.LOCK_UNAVAILABLE)
        }
    }

    private fun checkpoint() {
        requireCaller()
        requireConnectionFree()
        requireSignerRotationCustody(!Thread.currentThread().isInterrupted, CatalogSignerRotationCustodyFailureV1.INTERRUPTED)
        try {
            originalBudget.remainingMillis(BUDGET_CEILING_MILLIS)
        } catch (_: PersistenceBoundaryException) {
            throw CatalogSignerRotationCustodyExceptionV1(CatalogSignerRotationCustodyFailureV1.TIME_BUDGET)
        }
    }

    private fun requireCaller() = requireSignerRotationCustody(caller === Thread.currentThread(), CatalogSignerRotationCustodyFailureV1.WRONG_CALLER)

    // Catch every failure to finish actual cleanup first; never replace fatal/cancellation/interruption with an ordinary custody reason.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun <T> perform(expected: State, action: () -> T): T {
        requireCaller() // An unrelated caller cannot poison the owner or steal cleanup.
        requireConnectionFree()
        try {
            requireSignerRotationCustody(state == expected, CatalogSignerRotationCustodyFailureV1.INVALID_STATE)
            checkpoint()
            val result = action()
            checkpoint()
            return result
        } catch (failure: Throwable) {
            state = State.FAILED
            preserveInterruption(failure)
            val cleanup = closeFiles()
            throwSignal(if (cleanup == null) failure else preferSignerRotationCleanup(failure, cleanup))
            val code = if (!files.cleanupComplete()) CatalogSignerRotationCustodyFailureV1.CLEANUP_UNCERTAIN else failureCode(failure)
            throw CatalogSignerRotationCustodyExceptionV1(code)
        }
    }

    // Called after actual cleanup: sanitize ordinary budget failures, but never downgrade a fatal Error.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun completionFailure(): CatalogSignerRotationCustodyFailureV1? = try {
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
        requireSignerRotationCustody(!claims.containsKey(key) && claims.size < MAX_ACTIVE_ROOTS, CatalogSignerRotationCustodyFailureV1.LOCK_UNAVAILABLE)
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

    override fun toString(): String = "CatalogSignerRotationReleaseCustodyV1(retained,redacted,no-capability)"

    private enum class State { RETAINED, OPENING, OPEN, FAILED, CLOSED }

    companion object {
        private const val BUDGET_CEILING_MILLIS = 600_000L
        private const val MAX_ACTIVE_ROOTS = 16
        private const val MAX_PATH_DEPTH = 64
        private const val MAX_PATH_CHARS = 2048

        // Closing a competing channel can drop POSIX process-associated locks held by another channel.
        // Keep the ORIGINAL owner strongly claimed by actual (device,inode) fileKey, including uncertainty.
        private val claims = mutableMapOf<Any, CatalogSignerRotationReleaseCustodyV1>()

        /** Bounded capture only: no filesystem call, construction/open, lock, or fresh time budget. */
        fun retain(root: Path, allocationBytes: ByteArray, originalBudget: PersistenceTimeBudget): CatalogSignerRotationReleaseCustodyV1 {
            requireSignerRotationCustody(
                root.fileSystem === FileSystems.getDefault() && root.isAbsolute && root == root.normalize() &&
                    root.nameCount in 1..MAX_PATH_DEPTH && root.toString().length <= MAX_PATH_CHARS &&
                    allocationBytes.size in 1..CatalogSignerRotationCapacityV1.MAX_DOCUMENT_BYTES,
                CatalogSignerRotationCustodyFailureV1.INVALID_INPUT,
            )
            return CatalogSignerRotationReleaseCustodyV1(root, allocationBytes.copyOf(), originalBudget)
        }

        internal fun preserveInterruption(failure: Throwable) {
            if (failure is InterruptedException || failure is InterruptedIOException || failure is ClosedByInterruptException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun failureCode(failure: Throwable): CatalogSignerRotationCustodyFailureV1 = when (failure) {
            is CatalogSignerRotationCustodyExceptionV1 -> failure.code
            is InterruptedException, is InterruptedIOException, is ClosedByInterruptException -> CatalogSignerRotationCustodyFailureV1.INTERRUPTED
            else -> CatalogSignerRotationCustodyFailureV1.IO_UNCERTAIN
        }
    }
}
