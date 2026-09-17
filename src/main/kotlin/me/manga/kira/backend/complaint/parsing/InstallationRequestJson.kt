package me.manga.kira.backend.complaint.parsing

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.complaint.domain.ComplaintIdentifiers
import me.manga.kira.backend.complaint.domain.ComplaintPlatform
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64

/** Shared mechanics for the closed installation bodies; route parsers redact all diagnostics. */
internal object InstallationRequestJson {
    const val MAX_BODY_BYTES = 4096
    private val sessionFields = setOf("installationId", "secret", "expectedDataScopeId")
    private val enrollmentFields = sessionFields + "platform"
    private val deletionFields = setOf("installationId", "secret", "credentialVersion", "dataScopeId")
    private val secretPattern = Regex("[A-Za-z0-9_-]{43}")
    private val decoder = Base64.getUrlDecoder()
    private val encoder = Base64.getUrlEncoder().withoutPadding()
    private val mapper = ObjectMapper(
        JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(
                StreamReadConstraints.builder().maxNestingDepth(2).maxStringLength(64).maxNameLength(64).maxNumberLength(20).build(),
            )
            .build(),
    ).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

    fun session(body: ByteArray): JsonNode = read(body, sessionFields)

    fun enrollment(body: ByteArray): JsonNode = read(body, enrollmentFields)

    fun deletion(body: ByteArray): JsonNode = read(body, deletionFields)

    fun installation(root: JsonNode): ScopedInstallationId = installation(root, "expectedDataScopeId")

    fun deletionInstallation(root: JsonNode): ScopedInstallationId = installation(root, "dataScopeId")

    fun credentialVersion(root: JsonNode): Long {
        val value = root["credentialVersion"]
        if (value == null || !value.isIntegralNumber || !value.canConvertToLong() || value.longValue() <= 0) malformed()
        return value.longValue()
    }

    private fun installation(root: JsonNode, scopeField: String): ScopedInstallationId = ScopedInstallationId(
        ComplaintIdentifiers.installationId(string(root, "installationId")),
        ComplaintIdentifiers.dataScope(string(root, scopeField)),
    )

    fun platform(root: JsonNode): ComplaintPlatform = when (string(root, "platform")) {
        "ANDROID" -> ComplaintPlatform.ANDROID
        "IOS" -> ComplaintPlatform.IOS
        else -> malformed()
    }

    /** Caller owns the returned decoded secret and must erase it in finally after credential preparation. */
    fun secret(root: JsonNode): ByteArray {
        val value = string(root, "secret")
        if (value.length != 43 || !secretPattern.matches(value)) malformed()
        val bytes = decoder.decode(value)
        var transferred = false
        return try {
            if (bytes.size != 32 || encoder.encodeToString(bytes) != value) malformed()
            bytes.also { transferred = true }
        } finally {
            if (!transferred) bytes.fill(0)
        }
    }

    private fun read(body: ByteArray, fields: Set<String>): JsonNode {
        // Each route classifies oversize first; retain the bound even if this helper is used directly.
        if (body.size > MAX_BODY_BYTES) malformed()
        val text = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
        val root = mapper.readTree(text)
        if (root == null || !root.isObject) malformed()
        if (root.size() != fields.size || root.fieldNames().asSequence().toSet() != fields) malformed()
        return root
    }

    private fun string(root: JsonNode, field: String): String {
        val value = root[field]
        if (value == null || !value.isTextual) malformed()
        return value.textValue()
    }

    private fun malformed(): Nothing = throw IllegalArgumentException("Installation request syntax refused.")
}
