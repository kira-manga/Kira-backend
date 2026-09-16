package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** Allocated before reservation. Only the actual attempt's final two-party settlement writes it. */
internal class PersistenceFactoryProcessingCell {
    private val processing = AtomicReference(PersistenceFactoryProcessing.PENDING)
    val receipt: PersistenceFactoryReceipt = FactoryReceiptView(processing)

    fun complete(unresolved: Boolean) {
        val state = if (unresolved) PersistenceFactoryProcessing.PROCESSING_ENDED_UNRESOLVED else PersistenceFactoryProcessing.PROCESSING_ENDED
        processing.compareAndSet(PersistenceFactoryProcessing.PENDING, state)
    }

    override fun toString(): String = "PersistenceFactoryProcessingCell"
}

private class FactoryReceiptView(private val processing: AtomicReference<PersistenceFactoryProcessing>) : PersistenceFactoryReceipt {
    override fun state(): PersistenceFactoryProcessing = processing.get()

    override fun toString(): String = "PersistenceFactoryReceipt(${state().name})"
}
