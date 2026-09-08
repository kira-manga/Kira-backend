package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Actual invocation exits, not success receipts; retained before factory dispatch. */
internal class PersistenceOpeningFacts {
    val driverEntered = AtomicBoolean()
    val driverEnded = AtomicBoolean()
    val factoryEntered = AtomicBoolean()
    val factoryEnded = AtomicBoolean()
    val awaitingResourcePhase = AtomicBoolean()
    val fatal = AtomicBoolean()
    val outcome = AtomicReference<PersistencePhysicalOpening?>()
    val scopeCallEnded = AtomicBoolean()

    override fun toString(): String = "PersistenceOpeningFacts(redacted)"
}
