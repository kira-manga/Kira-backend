package me.manga.kira.backend.complaint.domain.terminal

import me.manga.kira.backend.common.Sha256
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

/** Storage vocabulary only; EPOCH_SEAL is not an ordinary publication or APPLIED event kind. */
internal enum class TestTerminalDurableKindV1(val path: String) {
    INSTALLATION_MANIFEST("installation-manifest"),
    TEST_RUN_PURGE("test-run-purge"),
    EPOCH_SEAL("epoch-seal"),
}

internal enum class TestTerminalDurableStateV1 { CANONICAL, WIRE_FROZEN }

/**
 * Immutable storage declaration. Neither matching identifiers nor a caller-supplied floor proves
 * an accepted run/configuration, current fencing, retention coverage or canonical payload identity.
 */
internal data class TestTerminalDurableBindingV1(
    val operationToken: String,
    val run: TestTerminalRunContextV1,
    val journalConfigurationSha256: String,
    val objectKind: TestTerminalDurableKindV1,
    val objectOrdinal: Int,
    val objectId: String,
    val objectKey: String,
    val routingKeyId: String,
    val writerGeneration: String,
    val epochStartInclusive: Long,
    val epochEndInclusive: Long,
    val preparingFencingToken: Long,
    val retentionFloor: Instant,
    val createdAt: Instant,
) {
    init {
        TestTerminalSyntaxV1.uuid(operationToken)
        TestTerminalSyntaxV1.hash(journalConfigurationSha256)
        TestTerminalSyntaxV1.opaque(objectId)
        TestTerminalSyntaxV1.uuid(writerGeneration)
        TestTerminalSyntaxV1.referenceId(routingKeyId)
        requireTestTerminal(epochStartInclusive > 0 && epochEndInclusive >= epochStartInclusive)
        requireTestTerminal(preparingFencingToken > 0)
        when (objectKind) {
            TestTerminalDurableKindV1.INSTALLATION_MANIFEST -> {
                requireTestTerminal(objectOrdinal in 0 until TestTerminalProfileV1.MAX_MANIFEST_CHUNKS)
                requireTestTerminal(epochStartInclusive == epochEndInclusive)
            }
            TestTerminalDurableKindV1.TEST_RUN_PURGE -> {
                requireTestTerminal(objectOrdinal == 0 && epochStartInclusive == epochEndInclusive)
            }
            TestTerminalDurableKindV1.EPOCH_SEAL -> requireTestTerminal(objectOrdinal in 0 until TestTerminalProfileV1.MAX_SEALS)
        }
        requireTestTerminal(
            TestTerminalSyntaxV1.terminalKey(objectKey, writerGeneration, run.dataScopeId, epochEndInclusive, objectKind.path) ==
                routingKeyId,
        )
        requireDurableInstant(createdAt)
        requireDurableInstant(retentionFloor)
        requireTestTerminal(!retentionFloor.isBefore(createdAt.atOffset(ZoneOffset.UTC).plusYears(10).toInstant()))
    }

    val publicationRef: String? get() = if (objectKind == TestTerminalDurableKindV1.EPOCH_SEAL) null else objectId

    override fun toString(): String = "TestTerminalDurableBindingV1(redacted,declaration-only,no-authority)"
}

/**
 * Independent, wipeable byte snapshot of a V21 row SHAPE. Factories do not write or reload a row,
 * authenticate canonical/KJEV content, select a database winner, or attest commit/holder release.
 * Multiple local frozen candidates are possible; only the future fixed writer may persist one,
 * reload the immutable winner after uncertainty and obtain genuine post-release dispatch custody.
 */
internal class TestTerminalDurableRowV1 private constructor(
    val binding: TestTerminalDurableBindingV1,
    val state: TestTerminalDurableStateV1,
    private val storedCanonical: ByteArray,
    val canonicalSha256: String,
    private val storedWire: ByteArray?,
    val wireSha256: String?,
    val checksumSha256: String?,
    val retainUntil: Instant?,
    private val storedMetadata: ByteArray?,
    val metadataSha256: String?,
    val frozenAt: Instant?,
) : AutoCloseable {
    private var closed = false
    val schemaVersion: Int get() = TestTerminalDurableStorageProfileV1.SCHEMA_VERSION
    val canonicalizer: String get() = TestTerminalDurableStorageProfileV1.CANONICALIZER
    val contentType: String? get() = if (state == TestTerminalDurableStateV1.WIRE_FROZEN) {
        TestTerminalDurableStorageProfileV1.CONTENT_TYPE
    } else {
        null
    }
    val objectLockMode: String? get() = if (state == TestTerminalDurableStateV1.WIRE_FROZEN) {
        TestTerminalDurableStorageProfileV1.OBJECT_LOCK_MODE
    } else {
        null
    }

    @Synchronized
    fun canonicalBytes(): ByteArray {
        requireTestTerminal(!closed)
        return storedCanonical.copyOf()
    }

    @Synchronized
    fun wireBytes(): ByteArray? {
        requireTestTerminal(!closed)
        return storedWire?.copyOf()
    }

    @Synchronized
    fun metadataBytes(): ByteArray? {
        requireTestTerminal(!closed)
        return storedMetadata?.copyOf()
    }

    /** Fresh four-key map; even a caller mutating its copy cannot alter retained bytes or later maps. */
    @Synchronized
    fun metadata(): Map<String, String>? {
        requireTestTerminal(!closed)
        if (state == TestTerminalDurableStateV1.CANONICAL) return null
        return mapOf(
            "kira-journal-ciphertext-sha256" to checkNotNull(wireSha256),
            "kira-journal-event-id" to binding.objectId,
            "kira-journal-retain-until" to checkNotNull(retainUntil).toString(),
            "kira-journal-schema" to "1",
        )
    }

    @Synchronized
    private fun copyCanonicalForFreeze(): ByteArray {
        requireTestTerminal(!closed && state == TestTerminalDurableStateV1.CANONICAL)
        return storedCanonical.copyOf()
    }

    @Synchronized
    override fun close() {
        closed = true
        storedCanonical.fill(0)
        storedWire?.fill(0)
        storedMetadata?.fill(0)
    }

    override fun toString(): String = "TestTerminalDurableRowV1(redacted,shape-only,no-authority)"

    companion object {
        fun canonical(binding: TestTerminalDurableBindingV1, canonicalBytes: ByteArray): TestTerminalDurableRowV1 {
            requireTestTerminal(
                canonicalBytes.size in 1..TestTerminalDurableStorageProfileV1.MAX_CANONICAL_BYTES,
                TestTerminalFailureV1.LIMIT_EXCEEDED,
            )
            val snapshot = canonicalBytes.copyOf()
            return TestTerminalDurableRowV1(
                binding, TestTerminalDurableStateV1.CANONICAL, snapshot, Sha256.hex(snapshot),
                null, null, null, null, null, null, null,
            )
        }

        /**
         * Copies rather than borrows the canonical owner, and never mutates it. The supplied wire
         * is an opaque bounded candidate, not proof it encrypts this canonical content or binding.
         */
        fun frozen(
            canonicalRow: TestTerminalDurableRowV1,
            wireBytes: ByteArray,
            retainUntil: Instant,
            frozenAt: Instant,
        ): TestTerminalDurableRowV1 {
            requireTestTerminal(
                wireBytes.size in 1..TestTerminalDurableStorageProfileV1.MAX_WIRE_BYTES,
                TestTerminalFailureV1.LIMIT_EXCEEDED,
            )
            requireDurableInstant(retainUntil)
            requireDurableInstant(frozenAt)
            requireTestTerminal(
                !frozenAt.isBefore(canonicalRow.binding.createdAt) && retainUntil.isAfter(frozenAt) &&
                    !retainUntil.isBefore(canonicalRow.binding.retentionFloor),
            )
            val canonical = canonicalRow.copyCanonicalForFreeze()
            val wire = wireBytes.copyOf()
            val wireSha256 = Sha256.hex(wire)
            val checksum = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(wire))
            val metadata = canonicalMetadata(canonicalRow.binding.objectId, wireSha256, retainUntil)
            return TestTerminalDurableRowV1(
                canonicalRow.binding, TestTerminalDurableStateV1.WIRE_FROZEN, canonical, Sha256.hex(canonical),
                wire, wireSha256, checksum, retainUntil, metadata, Sha256.hex(metadata), frozenAt,
            )
        }

        // All interpolated values have fixed ASCII grammars. This is the exact sorted-key kcj-1
        // metadata spelling, not serialization/authentication of the opaque canonical payload.
        private fun canonicalMetadata(objectId: String, wireSha256: String, retainUntil: Instant): ByteArray = (
            "{\"kira-journal-ciphertext-sha256\":\"$wireSha256\"," +
                "\"kira-journal-event-id\":\"$objectId\"," +
                "\"kira-journal-retain-until\":\"$retainUntil\"," +
                "\"kira-journal-schema\":\"1\"}"
            ).toByteArray(Charsets.UTF_8).also {
            requireTestTerminal(it.size in 1..TestTerminalDurableStorageProfileV1.MAX_METADATA_BYTES)
        }
    }
}

private fun requireDurableInstant(value: Instant) {
    requireTestTerminal(value.epochSecond in 0..TestTerminalDurableStorageProfileV1.LAST_EPOCH_SECOND && value.nano == 0)
}
