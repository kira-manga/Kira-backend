package me.manga.kira.backend.security

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCountHashV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalManifestSummaryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.domain.terminal.requireTestTerminal
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.HexFormat

/**
 * Local folds only. A later sealed-barrier/provider producer must supply complete, authenticated passes.
 * The explicit chunk allowance is a stricter caller ceiling, NOT proof that the complete catalog fits.
 */
internal class TestTerminalRootsV1(
    journal: TestOwnerDeleteJournalConfigurationV1,
    internal val installationLimit: Long,
    internal val maximumManifestChunks: Int,
) {
    internal val json = TestTerminalJsonV1(journal)
    internal val scope = journal.scope.id.toString()
    internal val writer = journal.declaration().writer.generationId
    internal val maximumVersions = journal.declaration().limits.capacity.maximumRetainedVersions
    private val maximumFramedBytes = journal.declaration().limits.capacity.maximumScanStagingBytes

    init {
        requireTestTerminal(maximumManifestChunks in 1..TestTerminalProfileV1.MAX_MANIFEST_CHUNKS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(installationLimit in 1..maximumManifestChunks.toLong() * TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK)
    }

    fun installations(context: TestTerminalRunContextV1): TestTerminalInstallationRootV1.Builder {
        requireContext(context)
        return TestTerminalInstallationRootV1.Builder(this, context)
    }

    fun chunks(installations: TestTerminalInstallationRootV1, publicationEpoch: Long): TestTerminalChunkFoldV1 {
        requireTestTerminal(installations.belongsTo(this) && publicationEpoch > 0)
        return TestTerminalChunkFoldV1(this, installations, publicationEpoch)
    }

    fun preTerminalSeals(seals: TestTerminalSealSetV1): TestTerminalCountHashV1 {
        json.encodeSealSet(seals).fill(0)
        val records = seals.records()
        requireTestTerminal(records.size <= TestTerminalProfileV1.MAX_PRE_TERMINAL_SEALS && records.all { it.role == TestTerminalSealRoleV1.ORDINARY })
        val canonical = CanonicalJson.canonicalize(ListSerializer(TestTerminalSealRefV1.serializer()), records)
        return TestTerminalCountHashV1(records.size.toLong(), Sha256.hexUtf8(canonical))
    }

    fun preTerminalInventory(context: TestTerminalRunContextV1, seals: TestTerminalSealSetV1): TestTerminalInventoryFoldV1 {
        requireContext(context)
        requireTestTerminal(context.activationCatalogGeneration == seals.activationCatalogGeneration)
        requireTestTerminal(context.activationCatalogSha256 == seals.activationCatalogSha256 && context.dataScopeId == seals.dataScopeId)
        preTerminalSeals(seals)
        return TestTerminalInventoryFoldV1(this, context, seals.records())
    }

    internal fun requireContext(context: TestTerminalRunContextV1) = requireTestTerminal(context.dataScopeId == scope)

    internal fun boundedBytes(prior: Long, addition: Long): Long {
        val total = TestTerminalSyntaxV1.add(prior, addition)
        requireTestTerminal(total <= maximumFramedBytes, TestTerminalFailureV1.LIMIT_EXCEEDED)
        return total
    }

    override fun toString(): String = "TestTerminalRootsV1(local-folds,no-completeness-or-provider-authority)"
}

/** Two equal locally supplied passes, not an installation inventory or sealed-run barrier proof. */
internal class TestTerminalInstallationRootV1 private constructor(
    private val owner: TestTerminalRootsV1,
    val context: TestTerminalRunContextV1,
    val installationCount: Long,
    val retiredCount: Long,
    val deletedCount: Long,
    val sha256: String,
) {
    internal fun belongsTo(expected: TestTerminalRootsV1): Boolean = owner === expected
    override fun toString(): String = "TestTerminalInstallationRootV1(local-two-pass-digest,no-inventory-proof)"

    /** O(1) retained entries; a bad phase, ordering error or differing pass permanently poisons the fold. */
    internal class Builder internal constructor(private val owner: TestTerminalRootsV1, private val context: TestTerminalRunContextV1) {
        private var phase = LocalFoldPhase.COUNT
        private val first = MessageDigest.getInstance("SHA-256")
        private val second = MessageDigest.getInstance("SHA-256")
        private val root = MessageDigest.getInstance("SHA-256")
        private var fingerprint: ByteArray? = null
        private var count = 0L
        private var retired = 0L
        private var repeated = 0L
        private var firstBytes = 0L
        private var secondBytes = 0L
        private var previousId: String? = null

        init { owner.requireContext(context) }

        fun firstPass(entry: TestTerminalInstallationEntryV1): Unit = step(LocalFoldPhase.COUNT) {
            requireTestTerminal(count < owner.installationLimit, TestTerminalFailureV1.LIMIT_EXCEEDED)
            firstBytes = owner.boundedBytes(firstBytes, TestTerminalFramesV1.update(first, entryFields(entry)))
            count = TestTerminalSyntaxV1.add(count, 1)
            if (entry.disposition == TestTerminalDispositionV1.RETIRED) retired = TestTerminalSyntaxV1.add(retired, 1)
        }

        fun beginSecondPass(): Unit = step(LocalFoldPhase.COUNT) {
            fingerprint = first.digest()
            val prefix = TestTerminalFramesV1.installationPrefix(context, count, retired, count - retired)
            owner.boundedBytes(firstBytes, TestTerminalFramesV1.update(root, prefix))
            previousId = null
            phase = LocalFoldPhase.DIGEST
        }

        fun secondPass(entry: TestTerminalInstallationEntryV1): Unit = step(LocalFoldPhase.DIGEST) {
            requireTestTerminal(repeated < count)
            val fields = entryFields(entry)
            secondBytes = owner.boundedBytes(secondBytes, TestTerminalFramesV1.update(second, fields))
            TestTerminalFramesV1.update(root, fields)
            repeated = TestTerminalSyntaxV1.add(repeated, 1)
        }

        fun finish(): TestTerminalInstallationRootV1 = step(LocalFoldPhase.DIGEST) {
            requireTestTerminal(repeated == count && firstBytes == secondBytes)
            val repeatedFingerprint = second.digest()
            try {
                requireTestTerminal(MessageDigest.isEqual(checkNotNull(fingerprint), repeatedFingerprint))
            } finally {
                repeatedFingerprint.fill(0)
                fingerprint?.fill(0)
            }
            val result = TestTerminalInstallationRootV1(owner, context, count, retired, count - retired, TestTerminalFramesV1.finish(root))
            phase = LocalFoldPhase.DONE
            result
        }

        private fun entryFields(entry: TestTerminalInstallationEntryV1): List<String> {
            requireTestTerminal(previousId?.let { it < entry.installationId } != false)
            previousId = entry.installationId
            return listOf(entry.installationId, entry.disposition.name)
        }

        @Suppress("TooGenericExceptionCaught")
        private fun <T> step(expected: LocalFoldPhase, action: () -> T): T = try {
            requireTestTerminal(phase == expected)
            action()
        } catch (failure: Exception) {
            phase = LocalFoldPhase.FAILED
            first.reset()
            second.reset()
            root.reset()
            fingerprint?.fill(0)
            throw if (failure is TestTerminalExceptionV1) failure else TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
        }

        override fun toString(): String = "TestTerminalInstallationRootV1.Builder(local-only,redacted)"
    }
}

/** References remain caller declarations until the real reader verifies every exact object version. */
internal class TestTerminalChunkFoldV1 internal constructor(
    private val owner: TestTerminalRootsV1,
    private val installations: TestTerminalInstallationRootV1,
    private val publicationEpoch: Long,
) {
    private val expectedChunks = TestTerminalSyntaxV1.chunkCount(installations.installationCount)
    private val root = MessageDigest.getInstance("SHA-256")
    private val entries = MessageDigest.getInstance("SHA-256")
    private val seenKeys = HashSet<String>()
    private val seenIds = HashSet<String>()
    private var finished = false
    private var count = 0
    private var installationCount = 0L
    private var retiredCount = 0L
    private var framedBytes = 0L
    private var previousId: String? = null

    init {
        requireTestTerminal(installations.belongsTo(owner) && publicationEpoch > 0)
        requireTestTerminal(expectedChunks <= owner.maximumManifestChunks && expectedChunks.toLong() < owner.maximumVersions)
        val context = installations.context
        framedBytes = owner.boundedBytes(0, TestTerminalFramesV1.update(root, listOf(
            "kira-test-manifest-chunks-v1", context.dataScopeId, context.activationCatalogGeneration.toString(),
            context.activationCatalogSha256, expectedChunks.toString(),
        )))
        TestTerminalFramesV1.update(entries, TestTerminalFramesV1.installationPrefix(
            context, installations.installationCount, installations.retiredCount, installations.deletedCount,
        ))
    }

    fun add(manifest: TestTerminalInstallationManifestV1, objectRef: TestTerminalObjectRefV1): Unit = step {
        requireTestTerminal(count < expectedChunks && manifest.chunkIndex == count && manifest.chunkCount == expectedChunks)
        requireTestTerminal(manifest.context().run == installations.context && manifest.publicationEpoch == publicationEpoch)
        requireTestTerminal(manifest.installationsSha256 == installations.sha256)
        val canonical = owner.json.encodeInstallationManifest(manifest)
        try {
            requireTestTerminal(Sha256.hex(canonical) == objectRef.canonicalSha256)
        } finally {
            canonical.fill(0)
        }
        TestTerminalSyntaxV1.terminalKey(objectRef.objectKey, owner.writer, owner.scope, publicationEpoch, "installation-manifest")
        requireTestTerminal(seenKeys.add(objectRef.objectKey) && seenIds.add(manifest.eventId))
        manifest.entries().forEach { entry ->
            requireTestTerminal(previousId?.let { it < entry.installationId } != false)
            previousId = entry.installationId
            TestTerminalFramesV1.update(entries, listOf(entry.installationId, entry.disposition.name))
        }
        installationCount = TestTerminalSyntaxV1.add(installationCount, manifest.installationCount)
        retiredCount = TestTerminalSyntaxV1.add(retiredCount, manifest.retiredCount)
        requireTestTerminal(installationCount <= installations.installationCount && retiredCount <= installations.retiredCount)
        framedBytes = owner.boundedBytes(framedBytes, TestTerminalFramesV1.update(root, listOf(
            count.toString(), manifest.eventId, manifest.installationCount.toString(), manifest.retiredCount.toString(),
            manifest.deletedCount.toString(), manifest.entriesSha256, objectRef.objectKey, objectRef.objectVersion,
            objectRef.ciphertextSha256, objectRef.canonicalSha256,
        )))
        count++
    }

    fun finish(): TestTerminalManifestSummaryV1 = step {
        requireTestTerminal(count == expectedChunks && installationCount == installations.installationCount && retiredCount == installations.retiredCount)
        requireTestTerminal(TestTerminalFramesV1.finish(entries) == installations.sha256)
        val summary = TestTerminalManifestSummaryV1(
            installationCount, retiredCount, installationCount - retiredCount, count, installations.sha256, TestTerminalFramesV1.finish(root),
        )
        finished = true
        seenKeys.clear()
        seenIds.clear()
        summary
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> step(action: () -> T): T = try {
        requireTestTerminal(!finished)
        action()
    } catch (failure: Exception) {
        finished = true
        root.reset()
        entries.reset()
        seenKeys.clear()
        seenIds.clear()
        throw if (failure is TestTerminalExceptionV1) failure else TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
    }

    override fun toString(): String = "TestTerminalChunkFoldV1(local-declarations,no-object-verification)"
}

/** Fixed metadata only; no actor, inline deletion targets, marker representation or terminal object kind. */
internal data class TestTerminalInventoryEntryV1(
    val writerGeneration: String, val objectKind: String, val epochStartInclusive: Long, val epochEndInclusive: Long,
    val objectRef: TestTerminalObjectRefV1,
) {
    init {
        TestTerminalSyntaxV1.uuid(writerGeneration)
        requireTestTerminal(objectKind in setOf(
            "OWNER_DELETE", "OWNER_DELETE_ALL", "ADMIN_DELETE", "ADMIN_BATCH_DELETE", "RETENTION", "INSTALLATION_RETIREMENT", "EPOCH_SEAL",
        ))
        requireTestTerminal(epochStartInclusive > 0 && epochEndInclusive >= epochStartInclusive)
        requireTestTerminal(objectKind == TestTerminalProfileV1.EPOCH_SEAL || epochStartInclusive == epochEndInclusive)
    }
    override fun toString(): String = "TestTerminalInventoryEntryV1(redacted,declaration-only)"
}

/**
 * Matching local passes and complete inclusion of DECLARED seals, not a complete provider listing.
 * Exact key/version duplicate tracking is bounded by actual J scan-byte/version ceilings; no storage price is asserted.
 */
internal class TestTerminalInventoryFoldV1 internal constructor(
    private val owner: TestTerminalRootsV1,
    private val context: TestTerminalRunContextV1,
    seals: List<TestTerminalSealRefV1>,
) {
    private val seals = TestTerminalSyntaxV1.snapshot(seals, TestTerminalProfileV1.MAX_PRE_TERMINAL_SEALS)
    private val writers = this.seals.map { it.writerGeneration }.distinct().withIndex().associate { it.value to it.index }
    private val completeSealMask = (1 shl this.seals.size) - 1
    private val first = MessageDigest.getInstance("SHA-256")
    private val second = MessageDigest.getInstance("SHA-256")
    private val root = MessageDigest.getInstance("SHA-256")
    private val seenVersions = HashSet<Pair<String, String>>()
    private var phase = LocalFoldPhase.COUNT
    private var fingerprint: ByteArray? = null
    private var count = 0L
    private var repeated = 0L
    private var firstBytes = 0L
    private var secondBytes = 0L
    private var sealMask = 0
    private var previous: TestTerminalInventoryEntryV1? = null

    init {
        owner.requireContext(context)
        owner.preTerminalSeals(TestTerminalSealSetV1.create(
            context.dataScopeId, context.activationCatalogGeneration, context.activationCatalogSha256, this.seals,
        ))
    }

    fun firstPass(entry: TestTerminalInventoryEntryV1): Unit = step(LocalFoldPhase.COUNT) {
        requireTestTerminal(count < owner.maximumVersions, TestTerminalFailureV1.LIMIT_EXCEEDED)
        firstBytes = owner.boundedBytes(firstBytes, TestTerminalFramesV1.update(first, entryFields(entry)))
        count = TestTerminalSyntaxV1.add(count, 1)
    }

    fun beginSecondPass(): Unit = step(LocalFoldPhase.COUNT) {
        requireTestTerminal(sealMask == completeSealMask)
        fingerprint = first.digest()
        owner.boundedBytes(firstBytes, TestTerminalFramesV1.update(root, listOf(
            "kira-test-preterminal-inventory-v1", context.dataScopeId, context.activationCatalogGeneration.toString(),
            context.activationCatalogSha256, count.toString(),
        )))
        previous = null
        sealMask = 0
        seenVersions.clear()
        phase = LocalFoldPhase.DIGEST
    }

    fun secondPass(entry: TestTerminalInventoryEntryV1): Unit = step(LocalFoldPhase.DIGEST) {
        requireTestTerminal(repeated < count)
        val fields = entryFields(entry)
        secondBytes = owner.boundedBytes(secondBytes, TestTerminalFramesV1.update(second, fields))
        TestTerminalFramesV1.update(root, fields)
        repeated = TestTerminalSyntaxV1.add(repeated, 1)
    }

    fun finish(): TestTerminalCountHashV1 = step(LocalFoldPhase.DIGEST) {
        requireTestTerminal(repeated == count && firstBytes == secondBytes && sealMask == completeSealMask)
        val repeatedFingerprint = second.digest()
        try {
            requireTestTerminal(MessageDigest.isEqual(checkNotNull(fingerprint), repeatedFingerprint))
        } finally {
            repeatedFingerprint.fill(0)
            fingerprint?.fill(0)
        }
        phase = LocalFoldPhase.DONE
        seenVersions.clear()
        TestTerminalCountHashV1(count, TestTerminalFramesV1.finish(root))
    }

    private fun entryFields(entry: TestTerminalInventoryEntryV1): List<String> {
        requireTestTerminal(entry.writerGeneration in writers)
        if (entry.objectKind == TestTerminalProfileV1.EPOCH_SEAL) {
            val index = seals.indexOfFirst {
                it.writerGeneration == entry.writerGeneration && it.epochStartInclusive == entry.epochStartInclusive &&
                    it.epochEndInclusive == entry.epochEndInclusive && it.objectRef.objectKey == entry.objectRef.objectKey
            }
            requireTestTerminal(index >= 0)
            // Include additional versions too, but the exact declared seal version must occur in each pass.
            if (seals[index].objectRef == entry.objectRef) sealMask = sealMask or (1 shl index)
        } else {
            requireTestTerminal(seals.any {
                it.writerGeneration == entry.writerGeneration && entry.epochStartInclusive >= it.epochStartInclusive &&
                    entry.epochEndInclusive <= it.epochEndInclusive
            })
            ordinaryKey(entry)
        }
        requireTestTerminal(previous?.let { compare(it, entry) < 0 } != false)
        requireTestTerminal(seenVersions.add(entry.objectRef.objectKey to entry.objectRef.objectVersion))
        previous = entry
        return listOf(
            entry.writerGeneration, entry.objectKind, entry.epochStartInclusive.toString(), entry.epochEndInclusive.toString(),
            entry.objectRef.objectKey, entry.objectRef.objectVersion, entry.objectRef.ciphertextSha256, entry.objectRef.canonicalSha256,
        )
    }

    private fun ordinaryKey(entry: TestTerminalInventoryEntryV1) {
        val prefix = TestTerminalSyntaxV1.ordinaryPrefix(entry.writerGeneration, owner.scope) + "writer/${entry.writerGeneration}/epoch/"
        requireTestTerminal(entry.objectRef.objectKey.startsWith(prefix))
        val parts = entry.objectRef.objectKey.removePrefix(prefix).split('/', limit = 4)
        requireTestTerminal(parts.size == 3 && parts[0] == entry.epochStartInclusive.toString().padStart(19, '0'))
        TestTerminalSyntaxV1.referenceId(parts[1])
        TestTerminalSyntaxV1.opaque(parts[2])
    }

    private fun compare(left: TestTerminalInventoryEntryV1, right: TestTerminalInventoryEntryV1): Int {
        val order = intArrayOf(
            writers.getValue(left.writerGeneration).compareTo(writers.getValue(right.writerGeneration)),
            left.epochStartInclusive.compareTo(right.epochStartInclusive), left.epochEndInclusive.compareTo(right.epochEndInclusive),
            left.objectKind.compareTo(right.objectKind), left.objectRef.objectKey.compareTo(right.objectRef.objectKey),
        )
        return order.firstOrNull { it != 0 } ?: TestTerminalFramesV1.compareUtf8(left.objectRef.objectVersion, right.objectRef.objectVersion)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun <T> step(expected: LocalFoldPhase, action: () -> T): T = try {
        requireTestTerminal(phase == expected)
        action()
    } catch (failure: Exception) {
        phase = LocalFoldPhase.FAILED
        first.reset()
        second.reset()
        root.reset()
        fingerprint?.fill(0)
        seenVersions.clear()
        throw if (failure is TestTerminalExceptionV1) failure else TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
    }

    override fun toString(): String = "TestTerminalInventoryFoldV1(local-two-pass-fold,no-provider-inventory-proof)"
}

/** LP32BE-UTF8 only; all roots/routing use these exact bounded frames, never caller-selected encoders. */
internal object TestTerminalFramesV1 {
    fun bytes(fields: List<String>): ByteArray {
        val snapshot = TestTerminalSyntaxV1.snapshot(fields, 32)
        requireTestTerminal(snapshot.isNotEmpty())
        val encoded = ArrayList<ByteArray>(snapshot.size)
        try {
            var size = 0L
            snapshot.forEach { field ->
                TestTerminalSyntaxV1.utf8(field, TestTerminalProfileV1.MAX_STRING_BYTES)
                val bytes = field.toByteArray(Charsets.UTF_8).also(encoded::add)
                size = TestTerminalSyntaxV1.add(size, 4L + bytes.size)
                requireTestTerminal(size <= TestTerminalProfileV1.MAX_PLAINTEXT_BYTES, TestTerminalFailureV1.LIMIT_EXCEEDED)
            }
            val output = ByteBuffer.allocate(size.toInt())
            encoded.forEach { output.putInt(it.size).put(it) }
            return output.array()
        } finally {
            encoded.forEach { it.fill(0) }
        }
    }

    fun update(digest: MessageDigest, fields: List<String>): Long {
        val frame = bytes(fields)
        return try {
            digest.update(frame)
            frame.size.toLong()
        } finally {
            frame.fill(0)
        }
    }

    fun finish(digest: MessageDigest): String {
        val result = digest.digest()
        return try {
            HexFormat.of().formatHex(result)
        } finally {
            result.fill(0)
        }
    }

    fun installationPrefix(context: TestTerminalRunContextV1, count: Long, retired: Long, deleted: Long): List<String> = listOf(
        "kira-test-installations-v1", context.dataScopeId, context.activationCatalogGeneration.toString(), context.activationCatalogSha256,
        context.configurationSha256, context.terminalEncodingSha256, count.toString(), retired.toString(), deleted.toString(),
    )

    fun compareUtf8(left: String, right: String): Int {
        var a = 0
        var b = 0
        while (a < left.length && b < right.length) {
            val x = left.codePointAt(a)
            val y = right.codePointAt(b)
            if (x != y) return x.compareTo(y)
            a += Character.charCount(x)
            b += Character.charCount(y)
        }
        return (left.length - a).compareTo(right.length - b)
    }
}

private enum class LocalFoldPhase { COUNT, DIGEST, DONE, FAILED }
