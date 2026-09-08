package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicReference

/** Test-only identity evidence; it neither authorizes an allocation nor proves native disposal. */
internal class PgPrimaryIdentityWitness {
    private val captured = AtomicReference<Snapshot?>()

    fun capture(entry: PersistenceTransportEntry<*>) {
        check(entry.record.role === PersistenceTransportRole.PRIMARY)
        val extent = checkNotNull(entry.extent)
        check(extent.role === PersistenceTransportRole.PRIMARY)
        val snapshot = Snapshot(entry, entry.record, extent, checkNotNull(entry.raw.get()))
        check(captured.compareAndSet(null, snapshot)) { "Synthetic original PRIMARY was captured more than once." }
    }

    fun requireUnchanged(current: PersistenceTransportEntry<*>) {
        val original = checkNotNull(captured.get()) { "Synthetic original PRIMARY was not captured." }
        check(current === original.entry && current.record === original.record && current.extent === original.extent)
        check(current.raw.get() === original.raw) { "Synthetic original PRIMARY raw identity changed." }
    }

    private class Snapshot(
        val entry: PersistenceTransportEntry<*>,
        val record: PersistenceTransportRecord,
        val extent: PersistenceTransportExtent,
        val raw: AutoCloseable,
    )
}

/** Fixed own-project slot inspection. Call only after measured I/O, with no F/G or raw operation under T. */
internal fun withPgCurrentPrimary(owner: PersistenceTransportOwner<*>, action: (PersistenceTransportEntry<*>) -> Unit) {
    val lock = transportTestLock(owner)
    check(!lock.isHeldByCurrentThread)
    val field = PersistenceTransportOwner::class.java.getDeclaredField("entries")
    check(field.trySetAccessible())
    awaitPgFixtureFact {
        if (!lock.tryLock()) {
            false
        } else {
            try {
                val entries = field.get(owner) as Array<*>
                val current = entries[PersistenceTransportRole.PRIMARY.ordinal] as? PersistenceTransportEntry<*>
                action(checkNotNull(current))
                true
            } finally {
                lock.unlock()
            }
        }
    }
}
