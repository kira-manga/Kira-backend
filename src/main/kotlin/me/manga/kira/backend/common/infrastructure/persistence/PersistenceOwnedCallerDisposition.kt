package me.manga.kira.backend.common.infrastructure.persistence

/** Closed, preinitialized states: publication of logical failure allocates no result or callback. */
internal enum class PersistenceOwnedCallerDisposition(val phase: PersistenceOwnedCallerPhase, val reason: PersistenceFactoryFailure? = null) {
    PREPARED(PersistenceOwnedCallerPhase.PREPARED),
    ATTACHED(PersistenceOwnedCallerPhase.ATTACHED),
    TAKEN(PersistenceOwnedCallerPhase.TAKEN),
    REFUSED_NOT_READY(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.NOT_READY),
    REFUSED_BUSY(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.BUSY),
    REFUSED_CLOSED(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.CLOSED),
    REFUSED_BROKEN(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.BROKEN),
    REFUSED_TIMEOUT(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.TIMEOUT),
    REFUSED_INTERRUPTED(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.INTERRUPTED),
    REFUSED_CREATE_FAILED(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.CREATE_FAILED),
    REFUSED_COORDINATION_FAILED(PersistenceOwnedCallerPhase.REFUSED, PersistenceFactoryFailure.COORDINATION_FAILED),
    ABANDONED_NOT_READY(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.NOT_READY),
    ABANDONED_BUSY(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.BUSY),
    ABANDONED_CLOSED(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.CLOSED),
    ABANDONED_BROKEN(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.BROKEN),
    ABANDONED_TIMEOUT(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.TIMEOUT),
    ABANDONED_INTERRUPTED(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.INTERRUPTED),
    ABANDONED_CREATE_FAILED(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.CREATE_FAILED),
    ABANDONED_COORDINATION_FAILED(PersistenceOwnedCallerPhase.ABANDONED, PersistenceFactoryFailure.COORDINATION_FAILED),
}

internal enum class PersistenceOwnedCallerPhase {
    PREPARED,
    ATTACHED,
    TAKEN,
    REFUSED,
    ABANDONED,
}
