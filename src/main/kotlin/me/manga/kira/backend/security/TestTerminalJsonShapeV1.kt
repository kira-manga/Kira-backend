package me.manga.kira.backend.security

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProfileV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalProgressV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSyntaxV1
import me.manga.kira.backend.complaint.domain.terminal.requireTestTerminal

/** Closed internal dispatch, not a caller-defined schema/serializer registry. */
internal enum class TestTerminalDocumentV1 {
    ENCODING, INSTALLATION_MANIFEST, PURGE, SEAL_SET, DENIAL_SET, EPOCH_SEAL, EVENT_HEADER, SEAL_HEADER,
    ENTRY, OBJECT_REF, SEAL_REF, COUNT_HASH, MANIFEST_SUMMARY, DENIAL_RANGE, DENIAL_CUT, POLICY, EVIDENCE, INVENTORY_WITNESS,
    PROGRESS, COMPLETED_CUT, INSTALLATION_READ, SOURCE_HIGH_WATER,
}

/** Fixed bounded preflight for this one profile. The ordinary parser and its accepted language are unchanged. */
internal class TestTerminalJsonShapeV1(limits: JournalDecoderLimitsV1) {
    private val maximumPlaintextBytes = minOf(limits.maximumPlaintextBytes, TestTerminalProfileV1.MAX_PLAINTEXT_BYTES)
    private val maximumDepth = minOf(limits.maximumJsonDepth, TestTerminalProfileV1.MAX_JSON_DEPTH)
    private val maximumTokens = minOf(limits.maximumJsonTokens, TestTerminalProfileV1.MAX_JSON_TOKENS)
    private val maximumFields = minOf(limits.maximumObjectFields, TestTerminalProfileV1.MAX_OBJECT_FIELDS)
    private val maximumStringBytes = minOf(limits.maximumStringUtf8Bytes, TestTerminalProfileV1.MAX_STRING_BYTES)
    private val factory = JsonFactory.builder()
        .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder()
                .maxNestingDepth(maximumDepth)
                .maxStringLength(maximumStringBytes)
                .maxNameLength(maximumStringBytes)
                .maxNumberLength(19)
                .build(),
        ).build()

    fun maximumBytes(kind: TestTerminalDocumentV1): Int = when (kind) {
        TestTerminalDocumentV1.EVENT_HEADER, TestTerminalDocumentV1.SEAL_HEADER -> minOf(maximumPlaintextBytes, TestTerminalProfileV1.MAX_HEADER_BYTES)
        TestTerminalDocumentV1.PROGRESS -> minOf(maximumPlaintextBytes, TestTerminalProgressV1.MAX_CANONICAL_BYTES)
        else -> maximumPlaintextBytes
    }

    fun check(text: String, kind: TestTerminalDocumentV1) {
        factory.createParser(text).use { parser ->
            val cursor = Cursor(parser)
            requireTestTerminal(cursor.next() == JsonToken.START_OBJECT)
            record(kind, cursor)
            requireTestTerminal(cursor.next() == null)
        }
    }

    private fun record(kind: TestTerminalDocumentV1, cursor: Cursor) {
        requireTestTerminal(cursor.parser.currentToken() == JsonToken.START_OBJECT)
        val fields = fields(kind)
        val seen = HashSet<String>()
        while (cursor.next() != JsonToken.END_OBJECT) {
            requireTestTerminal(cursor.parser.currentToken() == JsonToken.FIELD_NAME)
            requireTestTerminal(seen.size < maximumFields, TestTerminalFailureV1.LIMIT_EXCEEDED)
            val name = cursor.parser.text
            TestTerminalSyntaxV1.utf8(name, maximumStringBytes)
            requireTestTerminal(name in fields && seen.add(name))
            value(name, cursor)
        }
        requireTestTerminal(seen == fields)
    }

    private fun value(name: String, cursor: Cursor) {
        val token = cursor.next()
        val child = CHILDREN[name]
        val array = ARRAYS[name]
        when {
            child != null -> {
                requireTestTerminal(token == JsonToken.START_OBJECT)
                record(child, cursor)
            }
            array != null -> {
                requireTestTerminal(token == JsonToken.START_ARRAY)
                var count = 0
                while (cursor.next() != JsonToken.END_ARRAY) {
                    requireTestTerminal(++count <= array.maximum, TestTerminalFailureV1.LIMIT_EXCEEDED)
                    record(array.kind, cursor)
                }
            }
            name in NUMBERS -> {
                requireTestTerminal(token == JsonToken.VALUE_NUMBER_INT)
                val number = cursor.parser.text
                requireTestTerminal(DECIMAL.matches(number) && number.toLongOrNull() != null)
            }
            else -> {
                requireTestTerminal(token == JsonToken.VALUE_STRING)
                TestTerminalSyntaxV1.utf8(cursor.parser.text, maximumStringBytes)
            }
        }
    }

    private inner class Cursor(val parser: JsonParser) {
        private var tokens = 0
        fun next(): JsonToken? = parser.nextToken().also {
            if (it != null) requireTestTerminal(++tokens <= maximumTokens, TestTerminalFailureV1.LIMIT_EXCEEDED)
        }
    }

    private data class ArrayField(val kind: TestTerminalDocumentV1, val maximum: Int)

    private companion object {
        val DECIMAL = Regex("0|[1-9][0-9]{0,18}")
        val COMMON = names(
            "schemaVersion eventId publicationEpoch writerGeneration dataScopeKind dataScopeId activationCatalogGeneration " +
                "activationCatalogSha256 configurationSha256 terminalEncodingSha256",
        )
        val SET = names("schemaVersion dataScopeId activationCatalogGeneration activationCatalogSha256")
        val HEADER = names(
            "envelopeSchemaVersion payloadSchemaVersion canonicalizerId objectKind encryptionAlgorithm dataKeyMode kmsKeyId kmsKeyArn " +
                "bucket objectKey writerGeneration sealTerminalPrefix dataScopeKind dataScopeId routingKeyId nonce",
        )
        val NUMBERS = names(
            "schemaVersion publicationEpoch activationCatalogGeneration chunkIndex chunkCount installationCount retiredCount deletedCount " +
                "finalOrdinaryEpoch count epochStartInclusive epochEndInclusive version byteCount startedAtEpochSecond completedAtEpochSecond " +
                "versionCount denialEffectiveAtEpochSecond lastSessionExpiryEpochSecond acceptedRequestBoundSeconds eventCount preparingFencingToken " +
                "envelopeSchemaVersion payloadSchemaVersion desiredGeneration fencingToken framedByteCount enrolledCount reservationCount " +
                "installationsFramedBytes chunkSetFramedBytes",
        )
        val CHILDREN = mapOf(
            "object" to TestTerminalDocumentV1.OBJECT_REF,
            "finalOrdinarySeal" to TestTerminalDocumentV1.SEAL_REF,
            "preTerminalSeals" to TestTerminalDocumentV1.COUNT_HASH,
            "preTerminalInventory" to TestTerminalDocumentV1.COUNT_HASH,
            "installationManifest" to TestTerminalDocumentV1.MANIFEST_SUMMARY,
            "ordinary" to TestTerminalDocumentV1.DENIAL_CUT,
            "terminal" to TestTerminalDocumentV1.DENIAL_CUT,
            "policy" to TestTerminalDocumentV1.POLICY,
            "policyEvidence" to TestTerminalDocumentV1.EVIDENCE,
            "boundEvidence" to TestTerminalDocumentV1.EVIDENCE,
            "firstInventory" to TestTerminalDocumentV1.INVENTORY_WITNESS,
            "secondInventory" to TestTerminalDocumentV1.INVENTORY_WITNESS,
            "denial" to TestTerminalDocumentV1.DENIAL_CUT,
            "sourceHighWater" to TestTerminalDocumentV1.SOURCE_HIGH_WATER,
        )
        val ARRAYS = mapOf(
            "entries" to ArrayField(TestTerminalDocumentV1.ENTRY, TestTerminalProfileV1.MAX_ENTRIES_PER_CHUNK),
            "records" to ArrayField(TestTerminalDocumentV1.SEAL_REF, TestTerminalProfileV1.MAX_SEALS),
            "ranges" to ArrayField(TestTerminalDocumentV1.DENIAL_RANGE, TestTerminalProfileV1.MAX_DENIAL_RANGES),
            "completedCuts" to ArrayField(TestTerminalDocumentV1.COMPLETED_CUT, TestTerminalProgressV1.MAX_COMPLETED_CUTS),
            "installationReads" to ArrayField(TestTerminalDocumentV1.INSTALLATION_READ, TestTerminalProgressV1.MAX_INSTALLATION_READS),
        )

        fun fields(kind: TestTerminalDocumentV1): Set<String> = when (kind) {
            TestTerminalDocumentV1.ENCODING -> names("profile schemaVersion")
            TestTerminalDocumentV1.INSTALLATION_MANIFEST -> COMMON + names(
                "eventKind chunkIndex chunkCount installationCount retiredCount deletedCount installationsSha256 entriesSha256 entries",
            )
            TestTerminalDocumentV1.PURGE -> COMMON + names(
                "eventKind finalOrdinaryEpoch finalOrdinarySeal preTerminalSeals preTerminalInventory installationManifest",
            )
            TestTerminalDocumentV1.SEAL_SET -> SET + "records"
            TestTerminalDocumentV1.DENIAL_SET -> SET + "ranges"
            TestTerminalDocumentV1.EPOCH_SEAL -> names(
                "schemaVersion objectKind sealId writerGeneration dataScopeKind dataScopeId epochStartInclusive epochEndInclusive " +
                    "eventCount eventManifestSha256 precedingSealSha256 preparingFencingToken",
            )
            TestTerminalDocumentV1.EVENT_HEADER -> HEADER + names("publicationEpoch eventId")
            TestTerminalDocumentV1.SEAL_HEADER -> HEADER + names("epochStartInclusive epochEndInclusive sealId")
            TestTerminalDocumentV1.ENTRY -> names("installationId disposition")
            TestTerminalDocumentV1.OBJECT_REF -> names("objectKey objectVersion ciphertextSha256 canonicalSha256")
            TestTerminalDocumentV1.SEAL_REF -> names("role writerGeneration epochStartInclusive epochEndInclusive sealId precedingSealSha256 object")
            TestTerminalDocumentV1.COUNT_HASH -> names("count sha256")
            TestTerminalDocumentV1.MANIFEST_SUMMARY -> names(
                "installationCount retiredCount deletedCount chunkCount installationsSha256 chunksSha256",
            )
            TestTerminalDocumentV1.DENIAL_RANGE -> names("writerGeneration ordinaryPrefix sealTerminalPrefix ordinary terminal")
            TestTerminalDocumentV1.DENIAL_CUT -> names(
                "roleId policy denialEffectiveAtEpochSecond lastSessionExpiryEpochSecond acceptedRequestBoundSeconds " +
                    "policyEvidence boundEvidence firstInventory secondInventory",
            )
            TestTerminalDocumentV1.POLICY -> names("policyId version sha256")
            TestTerminalDocumentV1.EVIDENCE -> names("sha256 byteCount")
            TestTerminalDocumentV1.INVENTORY_WITNESS -> names("startedAtEpochSecond completedAtEpochSecond versionCount byteCount sha256")
            TestTerminalDocumentV1.PROGRESS -> SET + names(
                "documentKind configurationSha256 terminalEncodingSha256 completedCuts installationReads",
            )
            TestTerminalDocumentV1.COMPLETED_CUT -> names(
                "writerGeneration prefixKind epochStartInclusive epochEndInclusive scanId databaseIdentity restoreIdentity " +
                    "desiredGeneration fencingToken framedByteCount denial",
            )
            TestTerminalDocumentV1.INSTALLATION_READ -> names(
                "databaseIdentity restoreIdentity desiredGeneration fencingToken startedAtEpochSecond completedAtEpochSecond sourceHighWater " +
                    "installationCount retiredCount deletedCount chunkCount installationsSha256 installationsFramedBytes chunkSetSha256 chunkSetFramedBytes",
            )
            TestTerminalDocumentV1.SOURCE_HIGH_WATER -> names("enrolledCount reservationCount greatestReservationId sourceSha256 framedByteCount")
        }

        fun names(value: String): Set<String> = value.split(' ').toSet()
    }
}
