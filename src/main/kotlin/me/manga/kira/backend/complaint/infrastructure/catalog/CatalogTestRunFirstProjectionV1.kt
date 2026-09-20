package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationAttemptV1
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One same-process completion handoff, NOT target resource or registration authority. No public
 * receipt, row, digest, recovered PROJECT or caller-supplied state can issue it. Failure spends it.
 */
internal class CatalogTestRunFirstProjectionV1 private constructor(
    internal val target: VersionBoundTestNamespaceProcessV1,
    private val state: State,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private val spent = AtomicBoolean()

    internal fun claim(original: ComplaintTestNamespaceRegistrationAttemptV1): State {
        requireTestActivation(spent.compareAndSet(false, true))
        requireConnectionFree()
        requireTestActivation(caller === Thread.currentThread() && original.process === target)
        target.requireRegistrationTarget()
        return state
    }

    override fun close() { spent.set(true) }
    override fun toString(): String = "CatalogTestRunFirstProjectionV1(one-target-completion-only,redacted)"

    /** Original released comparison data. Constructing this data cannot construct/claim the continuation. */
    internal class State(
        val frozen: CatalogTestRunActivationFrozenV1,
        val signed: CatalogTestRunActivationSignedV1,
        val snapshot: CatalogTestRunActivationSnapshotV1,
    )

    companion object {
        internal fun issuedBy(original: CatalogTestRunActivationV1, target: VersionBoundTestNamespaceProcessV1): CatalogTestRunFirstProjectionV1 =
            CatalogTestRunFirstProjectionV1(target, original.firstProjectionState(target))
    }
}
