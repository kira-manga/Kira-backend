package me.manga.kira.backend.complaint.infrastructure.reconciliation

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointDocumentV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinarySealRetentionDeclarationV1
import me.manga.kira.backend.complaint.infrastructure.terminal.VersionBoundTestOrdinarySealV1
import me.manga.kira.backend.complaint.infrastructure.terminal.testOrdinarySealVerificationBytesV1
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant

/** Bounded persisted comparison parsing. Never a registration, native proof, health value or eligibility result. */
internal object TestInitialCheckpointCurrentCodecV1 {
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION).streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(3).maxStringLength(1024).maxNameLength(64).maxNumberLength(19).build(),
        ).build()

    fun checkpoint(bytes: ByteArray): TestActiveInitialCheckpointDocumentV1 {
        val value = boundedObject(bytes)
        val passes = value.getValue("passes").jsonArray
        check(passes.size == 2)
        fun pass(index: Int) = passes[index].jsonObject.let {
            TestActiveInitialCheckpointDocumentV1.Pass(instant(it.string("startedAt")), instant(it.string("completedAt")))
        }
        val document = TestActiveInitialCheckpointDocumentV1(value.string("dataScopeId"), value.number("desiredGeneration"),
            value.number("fencingToken"), value.string("configurationSha256"), value.string("journalConfigurationSha256"),
            value.string("databaseIdentity"), value.string("restoreIdentity"), value.number("catalogGeneration"), value.string("catalogSha256"),
            value.string("trustBundleSha256"), value.string("catalogWriterGeneration"), value.string("writerGeneration"),
            value.string("sealOperationToken"), value.string("sealObjectKey"), value.string("sealObjectVersion"),
            value.string("sealCanonicalSha256"), value.string("sealCiphertextSha256"), value.string("manifestSha256"),
            value.number("manifestFramedBytes"), pass(0), pass(1))
        // Closed constructor re-emits every field, including constants, scalar dates and both pass
        // numbers/counts/hashes. Unknown keys, nulls, aliases and alternative spellings cannot survive.
        val canonical = document.canonicalBytes()
        try { check(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
        return document
    }

    fun requireSealVerification(bytes: ByteArray, row: TestTerminalDurableRowV1, control: TestActiveInitialCheckpointRowsV1.Control,
        sampledAt: Instant, retention: TestOrdinarySealRetentionDeclarationV1) {
        val value = boundedObject(bytes)
        val modified = instant(value.string("lastModified"))
        check(modified.nano == 0 && !modified.isBefore(checkNotNull(row.frozenAt)) && !modified.isAfter(control.verifiedAt) &&
            checkNotNull(row.retainUntil).isAfter(modified) && !control.verifiedAt.isAfter(sampledAt))
        val minimum = maxOf(checkNotNull(row.retainUntil), VersionBoundTestOrdinarySealV1.tenYears(modified),
            retention.lastPreRunRestoreHorizon.plusSeconds(31 * 86_400L))
        check(control.retainUntil.nano == 0 && !control.retainUntil.isBefore(minimum) &&
            control.retainUntil.isAfter(sampledAt.plusMillis(retention.utcUncertainty.maximumMillis)))
        val canonical = testOrdinarySealVerificationBytesV1(row, control.version, modified, control.retainUntil, control.verifiedAt)
        try { check(canonical.contentEquals(bytes)) } finally { canonical.fill(0) }
    }

    private fun boundedObject(bytes: ByteArray): JsonObject {
        check(bytes.size in 1..TestActiveInitialCheckpointDocumentV1.MAX_BYTES)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        factory.createParser(text).use { parser ->
            check(parser.nextToken() == JsonToken.START_OBJECT)
            var depth = 1
            var tokens = 1
            while (depth > 0) {
                val token = parser.nextToken()
                check(token != null && ++tokens <= 256 && token != JsonToken.VALUE_NUMBER_FLOAT)
                when (token) {
                    JsonToken.START_OBJECT, JsonToken.START_ARRAY -> depth++
                    JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                    else -> Unit
                }
            }
            check(parser.nextToken() == null)
        }
        return CanonicalJson.json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.string(name: String): String = getValue(name).jsonPrimitive.also { check(it.isString) }.content
    private fun JsonObject.number(name: String): Long = getValue(name).jsonPrimitive.also { check(!it.isString) }.long
    private fun instant(value: String): Instant = Instant.parse(value).also {
        check(it.toString() == value && TestActiveInitialCheckpointDocumentV1.time(it))
    }
}
