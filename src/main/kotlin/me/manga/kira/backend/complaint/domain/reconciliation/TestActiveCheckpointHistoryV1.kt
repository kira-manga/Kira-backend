package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.util.Collections

/**
 * Immutable cross-lane COMPARISON contract. None of these constructors authenticates storage,
 * catalog authority, a native read, a lease or payment. E must load the named V26/V31 sources.
 *
 * The seal-history root projects ONLY immutable seal/provenance/native-evidence/charge fields.
 * ALL checkpoint documents and checkpoint hashes are deliberately absent from this type and its
 * root. A checkpoint separately binds its predecessor-checkpoint hash; there is no self-reference.
 */
internal class TestActiveCheckpointHistoryV1 private constructor(records: List<Entry>) {
    val entries: List<Entry> = Collections.unmodifiableList(records.toList())
    val first: Entry get() = entries.first()
    val last: Entry get() = entries.last()
    val rootSha256: String

    init {
        require(entries.size in 1..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
        entries.forEachIndexed { index, entry ->
            require(entry.ordinal == index + 1 && entry.identity.same(first.identity))
            if (index == 0) {
                require(entry.source == Source.V26_INITIAL && entry.epochStart == 1L && entry.epochEnd == 1L && entry.precedingSealSha256.isEmpty())
            } else {
                val prior = entries[index - 1]
                require(entry.source == Source.V31_RECURRENT && entry.epochStart == Math.addExact(prior.epochEnd, 1) &&
                    entry.precedingSealSha256 == prior.canonicalSha256 && entry.requestedAt >= prior.verifiedAt)
            }
        }
        require(entries.map { it.operationToken }.distinct().size == entries.size)
        require(entries.map { it.objectKey }.distinct().size == entries.size)
        rootSha256 = Sha256.hex(TestActiveRecurrentJsonV1.bytes(buildJsonObject {
            put("kind", "kira-test-active-seal-history-root"); put("schemaVersion", 1)
            put("entries", buildJsonArray { entries.forEach { add(JsonPrimitive(it.sha256)) } })
        }))
    }

    enum class Source { V26_INITIAL, V31_RECURRENT }

    class Identity(
        val scope: String, val runCreatedAt: Instant, val implementationSchema: Int,
        val desiredGeneration: Long, val configurationSha256: String, val journalConfigurationSha256: String,
        val databaseIdentity: String, val restoreIdentity: String, val writerGeneration: String,
        val activationCatalogGeneration: Long, val activationCatalogSha256: String,
        val catalogGeneration: Long, val catalogSha256: String, val trustBundleSha256: String,
        val catalogWriterGeneration: String,
    ) {
        init {
            require(listOf(scope, databaseIdentity, restoreIdentity, writerGeneration, catalogWriterGeneration).all(OfflineBootstrapGrammar::uuidV4))
            require(TestActiveRecurrentJsonV1.time(runCreatedAt) && implementationSchema == 1 && desiredGeneration > 0)
            require(activationCatalogGeneration in 2..65536 && catalogGeneration in activationCatalogGeneration..65536)
            require(listOf(configurationSha256, journalConfigurationSha256, activationCatalogSha256, catalogSha256, trustBundleSha256)
                .all(TestActiveRecurrentJsonV1::hash))
        }
        fun same(other: Identity): Boolean = json() == other.json()
        fun json(): JsonObject = buildJsonObject {
            put("dataScopeKind", "TEST"); put("dataScopeId", scope); put("runCreatedAt", runCreatedAt.toString())
            put("implementationSchema", implementationSchema); put("desiredGeneration", desiredGeneration)
            put("configurationSha256", configurationSha256); put("journalConfigurationSha256", journalConfigurationSha256)
            put("databaseIdentity", databaseIdentity); put("restoreIdentity", restoreIdentity); put("writerGeneration", writerGeneration)
            put("activationCatalogGeneration", activationCatalogGeneration); put("activationCatalogSha256", activationCatalogSha256)
            put("catalogGeneration", catalogGeneration); put("catalogSha256", catalogSha256)
            put("trustBundleSha256", trustBundleSha256); put("catalogWriterGeneration", catalogWriterGeneration)
        }
        override fun toString(): String = "ActiveCheckpointHistoryIdentity(comparison-only,redacted)"
        companion object {
            fun parse(value: JsonObject): Identity = with(TestActiveRecurrentJsonV1) {
                require(string(value, "dataScopeKind") == "TEST")
                Identity(string(value, "dataScopeId"), instant(value, "runCreatedAt"), integer(value, "implementationSchema"),
                    long(value, "desiredGeneration"), string(value, "configurationSha256"), string(value, "journalConfigurationSha256"),
                    string(value, "databaseIdentity"), string(value, "restoreIdentity"), string(value, "writerGeneration"),
                    long(value, "activationCatalogGeneration"), string(value, "activationCatalogSha256"), long(value, "catalogGeneration"),
                    string(value, "catalogSha256"), string(value, "trustBundleSha256"), string(value, "catalogWriterGeneration"))
                    .also { require(it.json() == value) }
            }
        }
    }

    class Entry(
        val identity: Identity, val source: Source, val ordinal: Int, val operationToken: String,
        val epochStart: Long, val epochEnd: Long, val epochAfter: Long,
        val requestOwner: String, val requestToken: Long, val requestedAt: Instant,
        val captureOwner: String, val captureToken: Long, val capturedAt: Instant,
        val preparingFencingToken: Long, val objectId: String, val objectKey: String, val routingKeyId: String,
        val canonicalSha256: String, val wireSha256: String, val metadataSha256: String, val sealEncodingSha256: String,
        val retentionFloor: Instant, val createdAt: Instant, val frozenRetainUntil: Instant, val frozenAt: Instant,
        val objectVersion: String, val lastModified: Instant, val retainUntil: Instant, val verifiedAt: Instant,
        val verificationSha256: String, val precedingSealSha256: String,
        val eventCount: Long, val manifestSha256: String, val manifestFramedBytes: Long,
        val sealStorageBytes: Long, val historyStorageBytes: Long,
    ) {
        init {
            require(ordinal in 1..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS && (ordinal == 1) == (source == Source.V26_INITIAL))
            require(listOf(operationToken, requestOwner, captureOwner).all(OfflineBootstrapGrammar::uuidV4))
            require(epochStart > 0 && epochEnd in epochStart until Long.MAX_VALUE && epochAfter == epochEnd + 1)
            require(requestToken > 0 && captureToken >= requestToken && preparingFencingToken > captureToken)
            require(listOf(requestedAt, capturedAt, retentionFloor, createdAt, frozenRetainUntil, frozenAt, lastModified, retainUntil, verifiedAt)
                .all(TestActiveRecurrentJsonV1::time))
            require(requestedAt >= identity.runCreatedAt && capturedAt >= requestedAt && createdAt >= capturedAt && frozenAt >= createdAt)
            require(verifiedAt >= lastModified && verifiedAt >= frozenAt && retainUntil > verifiedAt && retainUntil >= frozenRetainUntil && frozenRetainUntil >= retentionFloor)
            require(listOf(canonicalSha256, wireSha256, metadataSha256, sealEncodingSha256, verificationSha256, manifestSha256).all(TestActiveRecurrentJsonV1::hash))
            require(if (ordinal == 1) precedingSealSha256.isEmpty() else TestActiveRecurrentJsonV1.hash(precedingSealSha256))
            require(TestActiveRecurrentJsonV1.ascii(objectKey, 1024) && TestActiveRecurrentJsonV1.opaque(objectVersion))
            require(Regex("[A-Za-z0-9_-]{43}").matches(objectId) && Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,63}").matches(routingKeyId))
            require(eventCount >= 0 && manifestFramedBytes > 0 && sealStorageBytes == TestActiveFirstSealStorageV1.STORAGE_BYTES &&
                historyStorageBytes == TestActiveRecurrentStorageV1.HISTORY_STORAGE_BYTES)
        }
        val sha256: String get() = Sha256.hex(canonicalBytes())
        fun canonicalBytes(): ByteArray = TestActiveRecurrentJsonV1.bytes(json()).also {
            require(it.size in 1..TestActiveRecurrentStorageV1.MAX_HISTORY_ENTRY_BYTES)
        }
        fun json(): JsonObject = buildJsonObject {
            put("kind", "kira-test-active-seal-history-entry"); put("schemaVersion", 1); put("identity", identity.json())
            put("source", source.name); put("ordinal", ordinal); put("rotationSequence", ordinal); put("operationToken", operationToken)
            put("epochStart", epochStart); put("epochEnd", epochEnd); put("epochAfter", epochAfter)
            put("requestOwner", requestOwner); put("requestToken", requestToken); put("requestedAt", requestedAt.toString())
            put("captureOwner", captureOwner); put("captureToken", captureToken); put("capturedAt", capturedAt.toString())
            put("preparingFencingToken", preparingFencingToken); put("objectId", objectId); put("objectKey", objectKey); put("routingKeyId", routingKeyId)
            put("canonicalSha256", canonicalSha256); put("wireSha256", wireSha256); put("metadataSha256", metadataSha256); put("sealEncodingSha256", sealEncodingSha256)
            put("retentionFloor", retentionFloor.toString()); put("createdAt", createdAt.toString())
            put("frozenRetainUntil", frozenRetainUntil.toString()); put("frozenAt", frozenAt.toString())
            put("objectVersion", objectVersion); put("lastModified", lastModified.toString()); put("retainUntil", retainUntil.toString()); put("verifiedAt", verifiedAt.toString())
            put("verificationSha256", verificationSha256); put("precedingSealSha256", precedingSealSha256)
            put("eventCount", eventCount); put("manifestSha256", manifestSha256); put("manifestFramedBytes", manifestFramedBytes)
            put("paymentSource", "ORDINARY_ACTUAL"); put("sealStorageBytes", sealStorageBytes); put("historyStorageBytes", historyStorageBytes)
        }
        override fun toString(): String = "ActiveCheckpointHistoryEntry(immutable-comparison,redacted,no-authority)"
        companion object {
            fun parse(bytes: ByteArray): Entry = with(TestActiveRecurrentJsonV1) {
                val v = objectBytes(bytes, TestActiveRecurrentStorageV1.MAX_HISTORY_ENTRY_BYTES)
                require(string(v, "kind") == "kira-test-active-seal-history-entry" && integer(v, "schemaVersion") == 1 &&
                    string(v, "paymentSource") == "ORDINARY_ACTUAL" && integer(v, "rotationSequence") == integer(v, "ordinal"))
                Entry(Identity.parse(obj(v, "identity")), Source.valueOf(string(v, "source")), integer(v, "ordinal"), string(v, "operationToken"),
                    long(v, "epochStart"), long(v, "epochEnd"), long(v, "epochAfter"), string(v, "requestOwner"), long(v, "requestToken"), instant(v, "requestedAt"),
                    string(v, "captureOwner"), long(v, "captureToken"), instant(v, "capturedAt"), long(v, "preparingFencingToken"),
                    string(v, "objectId"), string(v, "objectKey"), string(v, "routingKeyId"), string(v, "canonicalSha256"), string(v, "wireSha256"),
                    string(v, "metadataSha256"), string(v, "sealEncodingSha256"), instant(v, "retentionFloor"), instant(v, "createdAt"),
                    instant(v, "frozenRetainUntil"), instant(v, "frozenAt"), string(v, "objectVersion"), instant(v, "lastModified"), instant(v, "retainUntil"),
                    instant(v, "verifiedAt"), string(v, "verificationSha256"), string(v, "precedingSealSha256"), long(v, "eventCount"),
                    string(v, "manifestSha256"), long(v, "manifestFramedBytes"), long(v, "sealStorageBytes"), long(v, "historyStorageBytes"))
                    .also { require(it.canonicalBytes().contentEquals(bytes)) }
            }
        }
    }
    override fun toString(): String = "TestActiveCheckpointHistoryV1(bounded-immutable-comparison,no-authority)"
    companion object { fun of(entries: List<Entry>): TestActiveCheckpointHistoryV1 = TestActiveCheckpointHistoryV1(entries) }
}

/** Strict canonical roundtrip catches duplicate/unknown keys and noncanonical numeric/UTF-8 forms. */
internal object TestActiveRecurrentJsonV1 {
    private val HASH = Regex("[0-9a-f]{64}")
    private val NUMBER = Regex("0|[1-9][0-9]*")
    fun hash(value: String): Boolean = HASH.matches(value)
    fun time(value: Instant): Boolean = TestActiveInitialCheckpointDocumentV1.time(value)
    fun ascii(value: String, maximum: Int): Boolean = value.length in 1..maximum && value.all { it in '!'..'~' }
    fun opaque(value: String): Boolean = value != "null" && value.toByteArray(Charsets.UTF_8).size in 1..1024 && value.none { it.code < 32 || it.code == 127 }
    fun bytes(value: JsonObject): ByteArray = CanonicalJson.canonicalize(value).toByteArray(Charsets.UTF_8)
    fun objectBytes(bytes: ByteArray, maximum: Int): JsonObject {
        require(bytes.size in 1..maximum)
        val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
        // Bound nesting BEFORE recursive parsing; canonical equality alone is too late for a
        // hostile nested document. This is only a lexical cap, not a substitute for JSON parsing.
        var quoted = false
        var escaped = false
        var depth = 0
        var containers = 0
        text.forEach { char ->
            if (quoted) {
                if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == '"') quoted = false
            } else when (char) {
                '"' -> quoted = true
                '{', '[' -> { depth++; containers++; require(depth <= 6 && containers <= 128) }
                '}', ']' -> { depth--; require(depth >= 0) }
            }
        }
        require(!quoted && depth == 0)
        return Json.parseToJsonElement(text) as? JsonObject ?: error("Invalid recurrent comparison document")
    }
    fun obj(value: JsonObject, key: String): JsonObject = value[key] as? JsonObject ?: error("Invalid recurrent comparison object")
    fun array(value: JsonObject, key: String): JsonArray = value[key] as? JsonArray ?: error("Invalid recurrent comparison array")
    fun string(value: JsonObject, key: String): String = (value[key] as? JsonPrimitive)?.also { require(it.isString) }?.content
        ?: error("Invalid recurrent comparison text")
    fun long(value: JsonObject, key: String): Long = (value[key] as? JsonPrimitive)?.also { require(!it.isString && NUMBER.matches(it.content)) }?.content?.toLong()
        ?: error("Invalid recurrent comparison number")
    fun integer(value: JsonObject, key: String): Int = Math.toIntExact(long(value, key))
    fun instant(value: JsonObject, key: String): Instant = string(value, key).let { text -> Instant.parse(text).also { require(time(it) && it.toString() == text) } }
}
