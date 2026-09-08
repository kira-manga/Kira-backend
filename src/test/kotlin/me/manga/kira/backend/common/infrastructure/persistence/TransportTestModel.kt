package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertTrue
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/** Its injected close is MODEL evidence, never a Socket/native acknowledgement. */
internal class TransportModelResource(
    private val owner: PersistenceTransportOwner<TransportModelResource>,
    val record: PersistenceTransportRecord,
    private val closeAction: () -> Unit = {},
) : AutoCloseable {
    val closes = AtomicInteger()
    val renders = AtomicInteger()
    val comparisons = AtomicInteger()

    override fun close() {
        val claim = owner.claimClose(record, this) ?: return
        closes.incrementAndGet()
        val result = runCatching(closeAction)
        assertTrue(owner.completeClose(claim, result.isSuccess))
        result.getOrThrow()
    }

    override fun toString(): String {
        renders.incrementAndGet()
        error("Synthetic model resource must not be rendered.")
    }

    override fun equals(other: Any?): Boolean {
        comparisons.incrementAndGet()
        return this === other
    }

    override fun hashCode(): Int {
        comparisons.incrementAndGet()
        return 1
    }
}

internal fun modelTransport(
    owner: PersistenceTransportOwner<TransportModelResource>,
    role: PersistenceTransportRole = PersistenceTransportRole.PRIMARY,
    closeAction: () -> Unit = {},
): PersistenceTransportCreation.Created<TransportModelResource> =
    owner.tryCreate(role) { TransportModelResource(owner, it, closeAction) } as PersistenceTransportCreation.Created<TransportModelResource>

internal class TransportTestFailure : IOException() {
    val renders = AtomicInteger()

    override val message: String
        get() {
            renders.incrementAndGet()
            error("Synthetic failure message must not be read.")
        }

    override fun toString(): String {
        renders.incrementAndGet()
        error("Synthetic failure must not be rendered.")
    }
}
