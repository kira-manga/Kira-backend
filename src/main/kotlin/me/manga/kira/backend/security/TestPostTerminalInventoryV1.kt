package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.domain.terminal.requireTestTerminal
import java.security.MessageDigest
import java.time.Instant

/** Post-terminal fixed metadata only. Deliberately NOT TestTerminalInventoryEntryV1/preTerminalInventory. */
internal data class TestPostTerminalInventoryEntryV1(
    val writerGeneration: String,
    val kind: TestTerminalCodecKindV1,
    val epochStartInclusive: Long,
    val epochEndInclusive: Long,
    val eventId: String?,
    val objectRef: TestTerminalObjectRefV1,
    val ciphertextByteCount: Long,
    val lastModified: Instant,
    val requestedRetainUntil: Instant,
    val retainUntil: Instant,
) {
    init {
        TestTerminalSyntaxV1.uuid(writerGeneration)
        requireTestTerminal(epochStartInclusive > 0 && epochEndInclusive >= epochStartInclusive && ciphertextByteCount > 0)
        requireTestTerminal(lastModified.epochSecond in 0..253_402_300_799L && lastModified.nano == 0 &&
            requestedRetainUntil.epochSecond in 1..253_402_300_799L && requestedRetainUntil.nano == 0 &&
            retainUntil.epochSecond in 1..253_402_300_799L && retainUntil.nano == 0 &&
            requestedRetainUntil > lastModified && retainUntil >= requestedRetainUntil)
        if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) requireTestTerminal(eventId == null)
        else {
            requireTestTerminal(epochStartInclusive == epochEndInclusive && eventId != null)
            TestTerminalSyntaxV1.opaque(checkNotNull(eventId))
        }
    }

    internal fun fields(): List<String> = listOf(writerGeneration, kind.name, epochStartInclusive.toString(),
        epochEndInclusive.toString(), eventId.orEmpty(), objectRef.objectKey, objectRef.objectVersion,
        objectRef.ciphertextSha256, objectRef.canonicalSha256, ciphertextByteCount.toString(),
        lastModified.toString(), requestedRetainUntil.toString(), retainUntil.toString(), "COMPLIANCE")

    internal fun framedBytes(): Long = TestTerminalFramesV1.bytes(fields()).let { try { it.size.toLong() } finally { it.fill(0) } }
    override fun toString(): String = "TestPostTerminalInventoryEntryV1(comparison-only,redacted)"
}

/**
 * Deterministic post-terminal fold, not listing/completeness authority. The connected owner supplies
 * every exact expected object from fresh fenced reads and compares BOTH actual complete native sets.
 * This new domain does not change the purge's immutable pre-terminal root or ordinary scan framing.
 */
internal class TestPostTerminalInventoryFoldV1(
    private val journal: TestOwnerDeleteJournalConfigurationV1,
    context: TestTerminalRunContextV1,
    private val terminalEpoch: Long,
    private val expectedCount: Long,
) {
    private val digest = MessageDigest.getInstance("SHA-256")
    private val maximumVersions = journal.declaration().limits.capacity.maximumRetainedVersions
    private val maximumFramedBytes = journal.declaration().limits.capacity.maximumScanStagingBytes
    private val maximumCiphertextBytes = Math.multiplyExact(maximumVersions, journal.declaration().limits.decoder.maximumEnvelopeBytes.toLong())
    private var ended = false
    private var count = 0L
    private var ciphertextBytes = 0L
    private var previous: TestPostTerminalInventoryEntryV1? = null
    private var framedBytes = 0L
    val prefixBytes: Long

    init {
        requireTestTerminal(context.dataScopeId == journal.scope.id.toString() && terminalEpoch > 1 && expectedCount in 1..maximumVersions)
        framedBytes = TestTerminalFramesV1.update(digest, listOf("kira-test-postterminal-inventory-v1", context.dataScopeId,
            context.activationCatalogGeneration.toString(), context.activationCatalogSha256, context.configurationSha256,
            context.terminalEncodingSha256, journal.declaration().writer.generationId, journal.sealTerminalPrefix,
            "1", terminalEpoch.toString(), expectedCount.toString()))
        requireTestTerminal(framedBytes <= maximumFramedBytes, TestTerminalFailureV1.LIMIT_EXCEEDED)
        prefixBytes = framedBytes
    }

    fun entry(value: TestPostTerminalInventoryEntryV1): Unit = checked {
        requireTestTerminal(count < expectedCount && value.writerGeneration == journal.declaration().writer.generationId)
        requireTestTerminal(value.epochEndInclusive <= terminalEpoch && value.objectRef.objectKey.startsWith(journal.sealTerminalPrefix))
        requireTestTerminal(value.kind == TestTerminalCodecKindV1.EPOCH_SEAL || value.epochEndInclusive == terminalEpoch)
        requireTestTerminal(value.ciphertextByteCount <= journal.declaration().limits.decoder.maximumEnvelopeBytes)
        previous?.let { prior ->
            val keyOrder = prior.objectRef.objectKey.compareTo(value.objectRef.objectKey)
            requireTestTerminal(keyOrder < 0 || keyOrder == 0 &&
                TestTerminalFramesV1.compareUtf8(prior.objectRef.objectVersion, value.objectRef.objectVersion) < 0)
        }
        framedBytes = Math.addExact(framedBytes, TestTerminalFramesV1.update(digest, value.fields()))
        ciphertextBytes = Math.addExact(ciphertextBytes, value.ciphertextByteCount)
        requireTestTerminal(framedBytes <= maximumFramedBytes && ciphertextBytes <= maximumCiphertextBytes, TestTerminalFailureV1.LIMIT_EXCEEDED)
        count++
        previous = value
    }

    fun finish(): Summary = checked {
        requireTestTerminal(count == expectedCount)
        ended = true
        Summary(count, ciphertextBytes, framedBytes, framedBytes - prefixBytes, TestTerminalFramesV1.finish(digest))
    }

    private fun <T> checked(work: () -> T): T = try {
        requireTestTerminal(!ended)
        work()
    } catch (failure: Exception) {
        ended = true
        digest.reset()
        throw if (failure is TestTerminalExceptionV1) failure else TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
    }

    data class Summary(val versionCount: Long, val ciphertextByteCount: Long, val framedByteCount: Long,
        val entryFramedByteCount: Long, val sha256: String)

    override fun toString(): String = "TestPostTerminalInventoryFoldV1(local-fold-only,no-provider-or-denial-authority)"
}
