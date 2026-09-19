package me.manga.kira.backend.security

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.exc.StreamConstraintsException
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEncodingV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalFailureV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.requireTestTerminal
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Exact canonical TEST syntax bound to a declaration, not an accepted J/D, authenticated wire or provider. */
internal class TestTerminalJsonV1(journal: TestOwnerDeleteJournalConfigurationV1) {
    private val declaration = journal.declaration()
    private val scope = journal.scope.id.toString()
    private val writer = declaration.writer.generationId
    private val routingIds = declaration.routing.keys.map { it.keyId }.toSet()
    private val shape = TestTerminalJsonShapeV1(declaration.limits.decoder)

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    fun encoding(bytes: ByteArray): TestTerminalEncodingV1 = decode(bytes, TestTerminalDocumentV1.ENCODING, TestTerminalEncodingV1.serializer())
    fun encodeEncoding(value: TestTerminalEncodingV1): ByteArray = encode(value, TestTerminalDocumentV1.ENCODING, TestTerminalEncodingV1.serializer())

    fun installationManifest(bytes: ByteArray): TestTerminalInstallationManifestV1 =
        decode(bytes, TestTerminalDocumentV1.INSTALLATION_MANIFEST, TestTerminalInstallationManifestV1.serializer()).also { bind(it.context()) }

    fun encodeInstallationManifest(value: TestTerminalInstallationManifestV1): ByteArray {
        bind(value.context())
        return encode(value, TestTerminalDocumentV1.INSTALLATION_MANIFEST, TestTerminalInstallationManifestV1.serializer())
    }

    fun purge(bytes: ByteArray): TestTerminalPurgeV1 = decode(bytes, TestTerminalDocumentV1.PURGE, TestTerminalPurgeV1.serializer()).also { bindPurge(it) }

    fun encodePurge(value: TestTerminalPurgeV1): ByteArray {
        bindPurge(value)
        return encode(value, TestTerminalDocumentV1.PURGE, TestTerminalPurgeV1.serializer())
    }

    fun epochSeal(bytes: ByteArray): TestTerminalEpochSealV1 =
        decode(bytes, TestTerminalDocumentV1.EPOCH_SEAL, TestTerminalEpochSealV1.serializer()).also { bindSeal(it) }

    fun encodeEpochSeal(value: TestTerminalEpochSealV1): ByteArray {
        bindSeal(value)
        return encode(value, TestTerminalDocumentV1.EPOCH_SEAL, TestTerminalEpochSealV1.serializer())
    }

    fun sealSet(bytes: ByteArray): TestTerminalSealSetV1 =
        decode(bytes, TestTerminalDocumentV1.SEAL_SET, TestTerminalSealSetV1.serializer()).also { requireTestTerminal(it.dataScopeId == scope) }

    fun encodeSealSet(value: TestTerminalSealSetV1): ByteArray {
        requireTestTerminal(value.dataScopeId == scope)
        return encode(value, TestTerminalDocumentV1.SEAL_SET, TestTerminalSealSetV1.serializer())
    }

    fun denialSet(bytes: ByteArray): TestTerminalDenialSetV1 =
        decode(bytes, TestTerminalDocumentV1.DENIAL_SET, TestTerminalDenialSetV1.serializer()).also { requireTestTerminal(it.dataScopeId == scope) }

    fun encodeDenialSet(value: TestTerminalDenialSetV1): ByteArray {
        requireTestTerminal(value.dataScopeId == scope)
        return encode(value, TestTerminalDocumentV1.DENIAL_SET, TestTerminalDenialSetV1.serializer())
    }

    fun eventHeader(bytes: ByteArray): TestTerminalEventHeaderV1 =
        decode(bytes, TestTerminalDocumentV1.EVENT_HEADER, TestTerminalEventHeaderV1.serializer()).also { bindHeader(it) }

    fun encodeEventHeader(value: TestTerminalEventHeaderV1): ByteArray {
        bindHeader(value)
        return encode(value, TestTerminalDocumentV1.EVENT_HEADER, TestTerminalEventHeaderV1.serializer())
    }

    fun sealHeader(bytes: ByteArray): TestTerminalSealHeaderV1 =
        decode(bytes, TestTerminalDocumentV1.SEAL_HEADER, TestTerminalSealHeaderV1.serializer()).also { bindSealHeader(it) }

    fun encodeSealHeader(value: TestTerminalSealHeaderV1): ByteArray {
        bindSealHeader(value)
        return encode(value, TestTerminalDocumentV1.SEAL_HEADER, TestTerminalSealHeaderV1.serializer())
    }

    internal fun installationDescriptorSha256(value: TestTerminalInstallationManifestV1): String =
        descriptor(encodeInstallationManifest(value), "eventId")

    internal fun purgeDescriptorSha256(value: TestTerminalPurgeV1): String = descriptor(encodePurge(value), "eventId")
    internal fun sealDescriptorSha256(value: TestTerminalEpochSealV1): String = descriptor(encodeEpochSeal(value), "sealId")

    private fun bind(context: TestTerminalEventContextV1) {
        requireTestTerminal(context.run.dataScopeId == scope && context.writerGeneration == writer)
    }

    private fun bindPurge(value: TestTerminalPurgeV1) {
        bind(value.context())
        requireTestTerminal(
            value.preTerminalInventory.count <= declaration.limits.capacity.maximumRetainedVersions,
            TestTerminalFailureV1.LIMIT_EXCEEDED,
        )
        requireTestTerminal(
            value.installationManifest.chunkCount.toLong() < declaration.limits.capacity.maximumRetainedVersions,
            TestTerminalFailureV1.LIMIT_EXCEEDED,
        )
    }

    private fun bindSeal(value: TestTerminalEpochSealV1) {
        requireTestTerminal(value.dataScopeId == scope && value.writerGeneration == writer)
        requireTestTerminal(value.eventCount <= declaration.limits.capacity.maximumRetainedVersions, TestTerminalFailureV1.LIMIT_EXCEEDED)
    }

    private fun bindHeader(value: TestTerminalEventHeaderV1) {
        requireTestTerminal(value.dataScopeId == scope && value.writerGeneration == writer && value.routingKeyId in routingIds)
        requireTestTerminal(value.kmsKeyId == declaration.encryption.keyId && value.kmsKeyArn == declaration.encryption.keyArn)
        requireTestTerminal(value.bucket == declaration.journalLocation.bucket)
    }

    private fun bindSealHeader(value: TestTerminalSealHeaderV1) {
        requireTestTerminal(value.dataScopeId == scope && value.writerGeneration == writer && value.routingKeyId in routingIds)
        requireTestTerminal(value.kmsKeyId == declaration.encryption.keyId && value.kmsKeyArn == declaration.encryption.keyArn)
        requireTestTerminal(value.bucket == declaration.journalLocation.bucket)
    }

    private fun descriptor(bytes: ByteArray, omittedId: String): String = try {
        val record = json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        requireTestTerminal(omittedId in record)
        Sha256.hexUtf8(CanonicalJson.canonicalize(JsonObject(record.filterKeys { it != omittedId })))
    } finally {
        bytes.fill(0)
    }

    private fun <T> encode(value: T, kind: TestTerminalDocumentV1, serializer: KSerializer<T>): ByteArray {
        val bytes = CanonicalJson.canonicalize(serializer, value).toByteArray(Charsets.UTF_8)
        var retained = false
        try {
            decode(bytes, kind, serializer)
            retained = true
            return bytes
        } finally {
            if (!retained) bytes.fill(0)
        }
    }

    private fun <T> decode(bytes: ByteArray, kind: TestTerminalDocumentV1, serializer: KSerializer<T>): T {
        requireTestTerminal(bytes.size in 1..shape.maximumBytes(kind), TestTerminalFailureV1.LIMIT_EXCEEDED)
        val owned = bytes.copyOf()
        try {
            val characters = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(owned))
            val text = try {
                characters.toString()
            } finally {
                if (characters.hasArray()) characters.array().fill('\u0000')
            }
            shape.check(text, kind)
            val value = json.decodeFromString(serializer, text)
            val canonical = CanonicalJson.canonicalize(serializer, value).toByteArray(Charsets.UTF_8)
            try {
                requireTestTerminal(canonical.contentEquals(owned))
            } finally {
                canonical.fill(0)
            }
            return value
        } catch (_: CharacterCodingException) {
            throw TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
        } catch (_: StreamConstraintsException) {
            throw TestTerminalExceptionV1(TestTerminalFailureV1.LIMIT_EXCEEDED)
        } catch (_: JsonProcessingException) {
            throw TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
        } catch (_: SerializationException) {
            throw TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
        } catch (_: IllegalArgumentException) {
            throw TestTerminalExceptionV1(TestTerminalFailureV1.INVALID_INPUT)
        } finally {
            owned.fill(0)
        }
    }

    override fun toString(): String = "TestTerminalJsonV1(TEST,declaration-bound,syntax-only,no-authority)"
}
