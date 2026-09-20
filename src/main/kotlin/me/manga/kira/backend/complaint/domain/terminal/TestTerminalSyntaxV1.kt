package me.manga.kira.backend.complaint.domain.terminal

import kotlinx.serialization.builtins.ListSerializer
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.util.Base64

/** Local grammar and internal consistency only; never authenticates inventory, fencing or provenance. */
internal object TestTerminalSyntaxV1 {
    fun uuid(value: String) = requireTestTerminal(OfflineBootstrapGrammar.uuidV4(value))
    fun hash(value: String) = requireTestTerminal(OfflineBootstrapGrammar.sha256(value))
    fun referenceId(value: String) = requireTestTerminal(OfflineBootstrapGrammar.referenceId(value))
    fun opaque(value: String) = base64(value, 32, 43)

    /** Checks strict UTF-16/UTF-8 without first allocating an encoding of an unbounded caller string. */
    fun utf8(value: String, maximumBytes: Int) {
        requireTestTerminal(maximumBytes > 0 && value.length <= maximumBytes, TestTerminalFailureV1.LIMIT_EXCEEDED)
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val char = value[index++]
            bytes += when {
                char.code < 0x80 -> 1
                char.code < 0x800 -> 2
                char.isHighSurrogate() -> {
                    requireTestTerminal(index < value.length && value[index++].isLowSurrogate())
                    4
                }
                else -> {
                    requireTestTerminal(!char.isLowSurrogate())
                    3
                }
            }
            requireTestTerminal(bytes <= maximumBytes, TestTerminalFailureV1.LIMIT_EXCEEDED)
        }
    }

    fun add(left: Long, right: Long): Long {
        requireTestTerminal(left >= 0 && right >= 0 && right <= Long.MAX_VALUE - left, TestTerminalFailureV1.LIMIT_EXCEEDED)
        return left + right
    }

    fun nextEpoch(epoch: Long): Long {
        requireTestTerminal(epoch > 0)
        return add(epoch, 1)
    }

    fun chunkCount(installationCount: Long): Int {
        requireTestTerminal(installationCount in 0..TestTerminalProfileV1.MAX_INSTALLATIONS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        val perChunk = TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK
        return (installationCount / perChunk + if (installationCount % perChunk == 0L) 0 else 1).toInt()
    }

    fun ordinaryPrefix(writer: String, scope: String): String = "complaints/journal/v1/$writer/test/$scope/ordinary/"
    fun sealTerminalPrefix(writer: String, scope: String): String = "complaints/journal/v1/$writer/test/$scope/seal-terminal/"

    fun run(value: TestTerminalRunContextV1) {
        catalogScope(1, value.dataScopeId, value.activationCatalogGeneration, value.activationCatalogSha256)
        hash(value.configurationSha256)
        requireTestTerminal(value.terminalEncodingSha256 == TestTerminalProfileV1.encodingSha256)
    }

    fun event(value: TestTerminalEventContextV1) {
        opaque(value.eventId)
        uuid(value.writerGeneration)
        requireTestTerminal(value.publicationEpoch > 0)
    }

    fun installationManifest(value: TestTerminalInstallationManifestV1) {
        requireTestTerminal(value.schemaVersion == 1 && value.dataScopeKind == "TEST")
        requireTestTerminal(value.eventKind == TestTerminalProfileV1.INSTALLATION_MANIFEST)
        value.context()
        requireTestTerminal(value.chunkCount in 1..TestTerminalProfileV1.MAX_MANIFEST_CHUNKS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(value.chunkIndex in 0 until value.chunkCount)
        val entries = value.entries()
        requireTestTerminal(entries.size in 1..TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(value.installationCount == entries.size.toLong())
        requireTestTerminal(value.retiredCount == entries.count { it.disposition == TestTerminalDispositionV1.RETIRED }.toLong())
        requireTestTerminal(value.deletedCount == entries.size.toLong() - value.retiredCount)
        requireTestTerminal(value.chunkIndex == value.chunkCount - 1 || entries.size == TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK)
        entries.zipWithNext().forEach { (left, right) -> requireTestTerminal(left.installationId < right.installationId) }
        hash(value.installationsSha256)
        requireTestTerminal(value.entriesSha256 == entriesSha256(entries))
    }

    fun entriesSha256(entries: List<TestTerminalInstallationEntryV1>): String {
        val bounded = snapshot(entries, TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK)
        return Sha256.hexUtf8(CanonicalJson.canonicalize(ListSerializer(TestTerminalInstallationEntryV1.serializer()), bounded))
    }

    fun objectRef(value: TestTerminalObjectRefV1) {
        objectKey(value.objectKey)
        utf8(value.objectVersion, 1024)
        requireTestTerminal(value.objectVersion.isNotEmpty() && value.objectVersion != "null")
        requireTestTerminal(value.objectVersion.none { Character.isISOControl(it) })
        hash(value.ciphertextSha256)
        hash(value.canonicalSha256)
    }

    fun sealRef(value: TestTerminalSealRefV1) {
        uuid(value.writerGeneration)
        opaque(value.sealId)
        sealRange(value.epochStartInclusive, value.epochEndInclusive, value.precedingSealSha256)
        if (value.role == TestTerminalSealRoleV1.TERMINAL) {
            requireTestTerminal(value.epochStartInclusive == value.epochEndInclusive && value.epochStartInclusive > 1)
        }
    }

    fun manifestSummary(value: TestTerminalManifestSummaryV1) {
        requireTestTerminal(add(value.retiredCount, value.deletedCount) == value.installationCount)
        requireTestTerminal(value.chunkCount == chunkCount(value.installationCount))
        hash(value.installationsSha256)
        hash(value.chunksSha256)
    }

    fun purge(value: TestTerminalPurgeV1) {
        requireTestTerminal(value.schemaVersion == 1 && value.dataScopeKind == "TEST" && value.eventKind == TestTerminalProfileV1.TEST_RUN_PURGE)
        value.context()
        requireTestTerminal(value.publicationEpoch == nextEpoch(value.finalOrdinaryEpoch))
        val seal = value.finalOrdinarySeal
        requireTestTerminal(seal.role == TestTerminalSealRoleV1.ORDINARY && seal.writerGeneration == value.writerGeneration)
        requireTestTerminal(seal.epochEndInclusive == value.finalOrdinaryEpoch)
        terminalKey(seal.objectRef.objectKey, seal.writerGeneration, value.dataScopeId, seal.epochEndInclusive, "epoch-seal")
        requireTestTerminal(value.preTerminalSeals.count in 1..TestTerminalProfileV1.MAX_PRE_TERMINAL_SEALS.toLong())
        requireTestTerminal(value.preTerminalInventory.count >= value.preTerminalSeals.count)
    }

    fun sealSet(value: TestTerminalSealSetV1) {
        catalogScope(value.schemaVersion, value.dataScopeId, value.activationCatalogGeneration, value.activationCatalogSha256)
        val records = value.records()
        requireTestTerminal(records.size in 1..TestTerminalProfileV1.MAX_SEALS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(records.count { it.role == TestTerminalSealRoleV1.ORDINARY } in 1..TestTerminalProfileV1.MAX_PRE_TERMINAL_SEALS)
        val writers = HashSet<String>()
        var previous: TestTerminalSealRefV1? = null
        records.forEachIndexed { index, record ->
            terminalKey(record.objectRef.objectKey, record.writerGeneration, value.dataScopeId, record.epochEndInclusive, "epoch-seal")
            val prior = previous
            if (prior != null && prior.writerGeneration == record.writerGeneration) {
                requireTestTerminal(record.epochStartInclusive > prior.epochEndInclusive)
                requireTestTerminal(record.precedingSealSha256 == prior.objectRef.canonicalSha256)
            } else {
                requireTestTerminal(writers.add(record.writerGeneration) && record.epochStartInclusive == 1L)
            }
            if (record.role == TestTerminalSealRoleV1.TERMINAL) {
                requireTestTerminal(index == records.lastIndex && prior != null && prior.writerGeneration == record.writerGeneration)
                requireTestTerminal(record.epochStartInclusive == nextEpoch(checkNotNull(prior).epochEndInclusive))
            }
            previous = record
        }
    }

    fun inventoryWitness(value: TestTerminalInventoryWitnessV1) {
        requireTestTerminal(value.startedAtEpochSecond >= 0 && value.completedAtEpochSecond >= value.startedAtEpochSecond)
        requireTestTerminal(value.versionCount >= 0 && value.byteCount >= 0)
        hash(value.sha256)
    }

    fun denialCut(value: TestTerminalDenialCutV1) {
        referenceId(value.roleId)
        requireTestTerminal(value.denialEffectiveAtEpochSecond >= 0 && value.lastSessionExpiryEpochSecond >= 0)
        requireTestTerminal(value.acceptedRequestBoundSeconds > 0)
        val first = value.firstInventory
        val second = value.secondInventory
        requireTestTerminal(first.startedAtEpochSecond >= maxOf(value.denialEffectiveAtEpochSecond, value.lastSessionExpiryEpochSecond))
        requireTestTerminal(second.startedAtEpochSecond >= add(first.completedAtEpochSecond, value.acceptedRequestBoundSeconds))
        requireTestTerminal(first.versionCount == second.versionCount && first.byteCount == second.byteCount && first.sha256 == second.sha256)
    }

    fun denialSet(value: TestTerminalDenialSetV1) {
        catalogScope(value.schemaVersion, value.dataScopeId, value.activationCatalogGeneration, value.activationCatalogSha256)
        val ranges = value.ranges()
        requireTestTerminal(ranges.size in 1..TestTerminalProfileV1.MAX_DENIAL_RANGES, TestTerminalFailureV1.LIMIT_EXCEEDED)
        val writers = HashSet<String>()
        ranges.forEach { range ->
            requireTestTerminal(writers.add(range.writerGeneration))
            requireTestTerminal(range.ordinaryPrefix == ordinaryPrefix(range.writerGeneration, value.dataScopeId))
            requireTestTerminal(range.sealTerminalPrefix == sealTerminalPrefix(range.writerGeneration, value.dataScopeId))
        }
    }

    fun epochSeal(value: TestTerminalEpochSealV1) {
        requireTestTerminal(value.schemaVersion == 1 && value.objectKind == TestTerminalProfileV1.EPOCH_SEAL && value.dataScopeKind == "TEST")
        uuid(value.dataScopeId)
        uuid(value.writerGeneration)
        opaque(value.sealId)
        sealRange(value.epochStartInclusive, value.epochEndInclusive, value.precedingSealSha256)
        requireTestTerminal(value.eventCount >= 0 && value.preparingFencingToken > 0)
        hash(value.eventManifestSha256)
    }

    fun eventHeader(value: TestTerminalEventHeaderV1) {
        headerIdentity(
            value.envelopeSchemaVersion, value.payloadSchemaVersion, value.canonicalizerId, value.encryptionAlgorithm, value.dataKeyMode,
            value.dataScopeKind, value.writerGeneration, value.dataScopeId, value.kmsKeyId, value.kmsKeyArn, value.bucket, value.nonce,
        )
        requireTestTerminal(value.objectKind == TestTerminalProfileV1.INSTALLATION_MANIFEST || value.objectKind == TestTerminalProfileV1.TEST_RUN_PURGE)
        requireTestTerminal(value.publicationEpoch > 0)
        opaque(value.eventId)
        requireTestTerminal(value.sealTerminalPrefix == sealTerminalPrefix(value.writerGeneration, value.dataScopeId))
        val kind = if (value.objectKind == TestTerminalProfileV1.INSTALLATION_MANIFEST) "installation-manifest" else "test-run-purge"
        requireTestTerminal(terminalKey(value.objectKey, value.writerGeneration, value.dataScopeId, value.publicationEpoch, kind) == value.routingKeyId)
    }

    fun sealHeader(value: TestTerminalSealHeaderV1) {
        headerIdentity(
            value.envelopeSchemaVersion, value.payloadSchemaVersion, value.canonicalizerId, value.encryptionAlgorithm, value.dataKeyMode,
            value.dataScopeKind, value.writerGeneration, value.dataScopeId, value.kmsKeyId, value.kmsKeyArn, value.bucket, value.nonce,
        )
        requireTestTerminal(value.objectKind == TestTerminalProfileV1.EPOCH_SEAL)
        requireTestTerminal(value.epochStartInclusive > 0 && value.epochEndInclusive >= value.epochStartInclusive)
        opaque(value.sealId)
        requireTestTerminal(value.sealTerminalPrefix == sealTerminalPrefix(value.writerGeneration, value.dataScopeId))
        requireTestTerminal(
            terminalKey(value.objectKey, value.writerGeneration, value.dataScopeId, value.epochEndInclusive, "epoch-seal") == value.routingKeyId,
        )
    }

    /** Returns only the syntactically named routing ID; it is not proof that a retained key exists. */
    fun terminalKey(key: String, writer: String, scope: String, epoch: Long, kindPath: String): String {
        objectKey(key)
        uuid(writer)
        uuid(scope)
        requireTestTerminal(epoch > 0)
        requireTestTerminal(kindPath in setOf("installation-manifest", "test-run-purge", "epoch-seal"))
        val prefix = sealTerminalPrefix(writer, scope)
        requireTestTerminal(key.startsWith(prefix))
        val parts = key.removePrefix(prefix).split('/', limit = 5)
        requireTestTerminal(parts.size == 4 && parts[0] == epoch.toString() && parts[2] == kindPath)
        referenceId(parts[1])
        requireTestTerminal(parts[3].endsWith(".kjev"))
        opaque(parts[3].removeSuffix(".kjev"))
        return parts[1]
    }

    /** Bounded copy even for a List whose iterator disagrees with its advertised size. */
    @Suppress("TooGenericExceptionCaught")
    fun <T> snapshot(input: List<T>, maximum: Int): List<T> = try {
        val count = input.size
        requireTestTerminal(count in 0..maximum, TestTerminalFailureV1.LIMIT_EXCEEDED)
        val iterator = input.iterator()
        val result = ArrayList<T>(count)
        repeat(count) {
            requireTestTerminal(iterator.hasNext())
            val item = iterator.next()
            requireTestTerminal(item != null)
            result.add(item)
        }
        requireTestTerminal(!iterator.hasNext())
        result
    } catch (failure: TestTerminalExceptionV1) {
        throw failure
    } catch (_: Exception) {
        throw TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
    }

    private fun objectKey(value: String) {
        requireTestTerminal(value.length in 1..1024, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it in "._-/" })
    }

    private fun base64(value: String, bytes: Int, characters: Int) {
        requireTestTerminal(value.length == characters && value.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' })
        val decoded = Base64.getUrlDecoder().decode(value)
        try {
            requireTestTerminal(decoded.size == bytes && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == value)
        } finally {
            decoded.fill(0)
        }
    }

    private fun catalogScope(schemaVersion: Int, scope: String, generation: Long, sha256: String) {
        requireTestTerminal(schemaVersion == 1)
        uuid(scope)
        requireTestTerminal(generation in 1..TestTerminalProfileV1.MAX_CATALOG_GENERATION)
        hash(sha256)
    }

    private fun sealRange(start: Long, end: Long, preceding: String) {
        requireTestTerminal(start > 0 && end >= start)
        if (start == 1L) requireTestTerminal(preceding.isEmpty()) else hash(preceding)
    }

    private fun headerIdentity(
        envelopeVersion: Int, payloadVersion: Int, canonicalizer: String, algorithm: String, mode: String,
        scopeKind: String, writer: String, scope: String, keyId: String, keyArn: String, bucket: String, nonce: String,
    ) {
        requireTestTerminal(envelopeVersion == 1 && payloadVersion == 1 && canonicalizer == "kcj-1")
        requireTestTerminal(algorithm == "AES-256-GCM" && mode == "FRESH_PER_OBJECT_KMS_WRAPPED" && scopeKind == "TEST")
        uuid(writer)
        uuid(scope)
        referenceId(keyId)
        requireTestTerminal(OfflineBootstrapGrammar.bucket(bucket))
        utf8(keyArn, 1024)
        val arn = keyArn.split(':', limit = 6)
        requireTestTerminal(arn.size == 6 && arn[0] == "arn" && arn[1] == "aws" && arn[2] == "kms")
        requireTestTerminal(OfflineBootstrapGrammar.region(arn[3]) && OfflineBootstrapGrammar.account(arn[4]))
        requireTestTerminal(arn[5].startsWith("key/"))
        uuid(arn[5].removePrefix("key/"))
        base64(nonce, 12, 16)
    }
}
