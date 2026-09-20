package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256

/** Locally checked, owner-bound canonical bytes only; no accepted activation or persisted publication intent. */
internal class TestTerminalContentV1 internal constructor(
    private val owner: Any,
    val kind: TestTerminalCodecKindV1,
    val route: TestTerminalRouteV1,
    canonical: ByteArray,
) : AutoCloseable {
    val canonicalSha256: String = Sha256.hex(canonical)
    private val storedCanonical = canonical.copyOf()
    private var closed = false
    val byteCount: Int get() = storedCanonical.size

    internal fun belongsTo(candidate: Any): Boolean = owner === candidate

    @Synchronized
    fun canonicalBytes(): ByteArray {
        requireTestTerminalCodec(!closed)
        return storedCanonical.copyOf()
    }

    @Synchronized
    override fun close() {
        closed = true
        storedCanonical.fill(0)
    }

    override fun toString(): String = "TestTerminalContentV1(redacted,no-authority)"
}

/** Fresh randomized candidate, not a wire-freeze commit/release or permission to dispatch to storage. */
internal class TestTerminalEnvelopeV1 internal constructor(val content: TestTerminalContentV1, wire: ByteArray) : AutoCloseable {
    val wireSha256: String = Sha256.hex(wire)
    private val storedWire = wire.copyOf()
    private var closed = false
    val byteCount: Int get() = storedWire.size

    @Synchronized
    fun wireBytes(): ByteArray {
        requireTestTerminalCodec(!closed)
        return storedWire.copyOf()
    }

    @Synchronized
    override fun close() {
        closed = true
        storedWire.fill(0) // The content is borrowed; its separate owner is never closed here.
    }

    override fun toString(): String = "TestTerminalEnvelopeV1(redacted,no-authority)"
}

/** Authenticated equality with borrowed expected content, not version/retention/inventory/provider evidence. */
internal class TestTerminalDecodedV1 internal constructor(val content: TestTerminalContentV1, val wireSha256: String) {
    override fun toString(): String = "TestTerminalDecodedV1(redacted,no-authority)"
}
