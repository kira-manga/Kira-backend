package me.manga.kira.backend.completion.application

/** Caller relinquishment and real Callable-body exit jointly own cleanup; Future cancellation is neither. */
internal class CompletionExecutionOwnership(private val permit: CompletionPermit) : AutoCloseable {
    private val lock = Any()
    private var entered = false
    private var exited = false
    private var callerClosed = false
    private var releaseAttempted = false

    fun tryEnter(): Boolean = synchronized(lock) {
        if (callerClosed || entered) {
            false
        } else {
            entered = true
            true
        }
    }

    /** Only the entered body activates; its startup decision still gates invocation after this I/O. */
    fun activate(): CompletionActivation {
        synchronized(lock) { check(entered && !exited) { "Completion execution must be running to activate admission" } }
        return permit.activate()
    }

    fun workerExited() {
        val shouldRelease = synchronized(lock) {
            check(entered) { "Completion execution cannot exit before entry" }
            exited = true
            claimRelease()
        }
        if (shouldRelease) release()
    }

    override fun close() {
        val shouldRelease = synchronized(lock) {
            callerClosed = true
            claimRelease()
        }
        if (shouldRelease) release()
    }

    /** Called only under lock; never perform provider, Redis or persistence work here. */
    private fun claimRelease(): Boolean {
        if (releaseAttempted || !callerClosed) return false
        if (entered && !exited) return false
        releaseAttempted = true
        return true
    }

    private fun release() {
        // Owed cleanup must not lose its only attempt merely because cancellation left this bit set.
        val interrupted = Thread.interrupted()
        try {
            permit.close()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
            // Do not clear a new interrupt that arrived while close was running.
        }
    }
}
