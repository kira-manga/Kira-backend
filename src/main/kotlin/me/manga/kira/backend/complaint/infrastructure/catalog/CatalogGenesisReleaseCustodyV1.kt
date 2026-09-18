package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import java.io.InterruptedIOException
import java.nio.channels.ClosedByInterruptException
import java.nio.file.FileSystems
import java.nio.file.Path

/**
 * Caller-retained, Linux-only byte custody for one fixed `genesis` allocation, with no deleting lifecycle.
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
internal class CatalogGenesisReleaseCustodyV1 private constructor(
    root: Path,
    private val allocationBytes: ByteArray,
    private val originalBudget: PersistenceTimeBudget,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val files = LinuxGenesisReleaseFilesV1(this, root)
    private var state = State.RETAINED
    private var claimedRootKey: Any? = null
    private var closeIssued = false
    private var closeFailure: CatalogGenesisCustodyFailureV1? = null

    fun open(): CatalogGenesisCustodyObservationV1 = openAllocation(existingOnly = false)

    /** Later stages may acquire existing custody only; absence never initializes a replacement allocation or lock. */
    fun openExisting(): CatalogGenesisCustodyObservationV1 = openAllocation(existingOnly = true)

    private fun openAllocation(existingOnly: Boolean): CatalogGenesisCustodyObservationV1 = perform(State.RETAINED) {
        state = State.OPENING
        claim(files.openRoot()) // Before opening ANY descriptor for the permanent lock inode.
        val observation = files.openAllocation(allocationBytes, existingOnly)
        state = State.OPEN
        observation
    }

    fun putIfAbsent(leaf: CatalogGenesisReleaseLeafV1, bytes: ByteArray): CatalogGenesisCustodyObservationV1 = perform(State.OPEN) {
        requireGenesisCustody(bytes.size in 1..leaf.maximumBytes, CatalogGenesisCustodyFailureV1.INVALID_INPUT)
        files.putIfAbsent(allocationBytes, leaf, bytes.copyOf())
    }

    /** A null is only absence under this held lock, not evidence that an effect was never attempted. */
    fun read(leaf: CatalogGenesisReleaseLeafV1): ByteArray? = perform(State.OPEN) {
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
            closeFailure = CatalogGenesisCustodyFailureV1.IO_UNCERTAIN
            state = State.CLOSED
            val fatal = files.closeAll()
            releaseClaimAfterActualCleanup()
            closeFailure = if (files.cleanupComplete()) completionFailure() else CatalogGenesisCustodyFailureV1.CLEANUP_UNCERTAIN
            if (fatal != null) throw fatal
        }
        closeFailure?.let { throw CatalogGenesisCustodyExceptionV1(it) }
    }

    internal fun checkpoint(candidate: LinuxGenesisReleaseFilesV1) {
        requireCaller()
        requireGenesisCustody(files === candidate && (state == State.OPENING || state == State.OPEN), CatalogGenesisCustodyFailureV1.INVALID_STATE)
        checkpoint()
    }

    internal fun requireLockClaim(candidate: LinuxGenesisReleaseFilesV1, key: Any) {
        checkpoint(candidate)
        synchronized(claims) {
            requireGenesisCustody(claimedRootKey == key && claims[key] === this, CatalogGenesisCustodyFailureV1.LOCK_UNAVAILABLE)
        }
    }

    private fun checkpoint() {
        requireCaller()
        requireConnectionFree()
        requireGenesisCustody(!Thread.currentThread().isInterrupted, CatalogGenesisCustodyFailureV1.INTERRUPTED)
        try {
            originalBudget.remainingMillis(BUDGET_CEILING_MILLIS)
        } catch (_: PersistenceBoundaryException) {
            throw CatalogGenesisCustodyExceptionV1(CatalogGenesisCustodyFailureV1.TIME_BUDGET)
        }
    }

    private fun requireCaller() = requireGenesisCustody(caller === Thread.currentThread(), CatalogGenesisCustodyFailureV1.WRONG_CALLER)

    // Catch every failure to finish actual cleanup first; only then distinguish and rethrow fatal Errors.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun <T> perform(expected: State, action: () -> T): T {
        requireCaller() // An unrelated caller cannot poison the owner or steal cleanup.
        requireConnectionFree()
        try {
            requireGenesisCustody(state == expected, CatalogGenesisCustodyFailureV1.INVALID_STATE)
            checkpoint()
            val result = action()
            checkpoint()
            return result
        } catch (failure: Throwable) {
            state = State.FAILED
            preserveInterruption(failure)
            val cleanupFatal = files.closeAll()
            releaseClaimAfterActualCleanup()
            if (failure is Error) throw failure
            if (cleanupFatal != null) throw cleanupFatal
            val code = if (!files.cleanupComplete()) CatalogGenesisCustodyFailureV1.CLEANUP_UNCERTAIN else failureCode(failure)
            throw CatalogGenesisCustodyExceptionV1(code)
        }
    }

    // Called after actual cleanup: sanitize ordinary budget failures, but never downgrade a fatal Error.
    @Suppress("TooGenericExceptionCaught", "InstanceOfCheckForException")
    private fun completionFailure(): CatalogGenesisCustodyFailureV1? = try {
        checkpoint()
        null
    } catch (failure: Throwable) {
        preserveInterruption(failure)
        if (failure is Error) throw failure
        failureCode(failure)
    }

    private fun claim(key: Any) = synchronized(claims) {
        requireGenesisCustody(!claims.containsKey(key) && claims.size < MAX_ACTIVE_ROOTS, CatalogGenesisCustodyFailureV1.LOCK_UNAVAILABLE)
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

    override fun toString(): String = "CatalogGenesisReleaseCustodyV1(retained,redacted,no-capability)"

    private enum class State { RETAINED, OPENING, OPEN, FAILED, CLOSED }

    companion object {
        private const val BUDGET_CEILING_MILLIS = 600_000L
        private const val MAX_ACTIVE_ROOTS = 16
        private const val MAX_PATH_DEPTH = 64
        private const val MAX_PATH_CHARS = 2048

        // Closing a competing channel can drop POSIX process-associated locks held by another channel.
        // Keep the ORIGINAL owner strongly claimed by actual (device,inode) fileKey, including uncertainty.
        private val claims = mutableMapOf<Any, CatalogGenesisReleaseCustodyV1>()

        /** Bounded capture only: no filesystem call, construction/open, lock, or fresh time budget. */
        fun retain(root: Path, allocationBytes: ByteArray, originalBudget: PersistenceTimeBudget): CatalogGenesisReleaseCustodyV1 {
            requireGenesisCustody(
                root.fileSystem === FileSystems.getDefault() && root.isAbsolute && root == root.normalize() &&
                    root.nameCount in 1..MAX_PATH_DEPTH && root.toString().length <= MAX_PATH_CHARS &&
                    allocationBytes.size in 1..CatalogGenesisCapacity.MAX_DOCUMENT_BYTES,
                CatalogGenesisCustodyFailureV1.INVALID_INPUT,
            )
            return CatalogGenesisReleaseCustodyV1(root, allocationBytes.copyOf(), originalBudget)
        }

        internal fun preserveInterruption(failure: Throwable) {
            if (failure is InterruptedException || failure is InterruptedIOException || failure is ClosedByInterruptException) {
                Thread.currentThread().interrupt()
            }
        }

        private fun failureCode(failure: Throwable): CatalogGenesisCustodyFailureV1 = when (failure) {
            is CatalogGenesisCustodyExceptionV1 -> failure.code
            is InterruptedException, is InterruptedIOException, is ClosedByInterruptException -> CatalogGenesisCustodyFailureV1.INTERRUPTED
            else -> CatalogGenesisCustodyFailureV1.IO_UNCERTAIN
        }
    }
}
