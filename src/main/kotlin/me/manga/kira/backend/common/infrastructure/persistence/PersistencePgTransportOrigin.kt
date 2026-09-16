package me.manga.kira.backend.common.infrastructure.persistence

/** Prepared by the actual allocation path before G/T/native work; frame metadata alone is not receiver ownership. */
internal class PersistencePgTransportOrigin(
    val scope: PersistencePgFactoryScope,
    val factory: TrackedPgSocketFactory,
    val image: PersistencePgDriverImage,
    val extent: PersistenceTransportExtent,
    val allocatingCaller: Thread,
) {
    fun isCurrentAllocation(): Boolean = allocatingCaller === Thread.currentThread() && scope.acceptsAllocation(this)

    fun isDirectAuxiliaryClose(): Boolean = extent.role === PersistenceTransportRole.AUX_CANCEL &&
        allocatingCaller === Thread.currentThread() && scope.ownsOrigin(this) &&
        !scope.ownershipLockHeld() && PersistencePgDriverFrames.auxiliaryCloseAllowed(image)

    override fun toString(): String = "PersistencePgTransportOrigin(redacted)"
}
