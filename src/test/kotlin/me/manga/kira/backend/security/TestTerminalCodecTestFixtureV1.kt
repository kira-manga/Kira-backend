package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.domain.JournalDecoderLimitsV1
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom
import java.util.Base64
import java.util.HexFormat
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Synthetic local ownership only. No activation, durable intent, provider or readback capability. */
internal class TestTerminalCodecTestFixtureV1(val source: TestTerminalTestFixture = TestTerminalTestFixture()) {
    var nanos = 0L
    val nonces = Nonces()
    val journal: TestOwnerDeleteJournalConfigurationV1 = source.journal
    val codec = TestTerminalCodecV1.fromAcquired(journal, source.acquired(), nonces) { nanos }
    val bucket: String = journal.declaration().journalLocation.bucket

    fun attempt(kind: TestTerminalCodecKindV1, parent: PersistenceTimeBudget? = null): TestTerminalAttemptV1 =
        codec.startAttempt(kind, parent)

    fun content(kind: TestTerminalCodecKindV1, attempt: TestTerminalAttemptV1, selected: String? = null): TestTerminalContentV1 = when (kind) {
        TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> codec.canonicalizeInstallationManifest(source.manifest, attempt, selected)
        TestTerminalCodecKindV1.TEST_RUN_PURGE -> codec.canonicalizePurge(source.purge, attempt, selected)
        TestTerminalCodecKindV1.EPOCH_SEAL -> codec.canonicalizeEpochSeal(source.ordinarySeal, attempt, selected)
    }

    fun limited(decoder: JournalDecoderLimitsV1): TestTerminalCodecTestFixtureV1 {
        val d = journal.declaration()
        return TestTerminalCodecTestFixtureV1(TestTerminalTestFixture(TestOwnerDeleteJournalConfigurationV1.of(d.copy(limits = d.limits.copy(decoder = decoder)))))
    }

    internal class Nonces : SecureRandom() {
        private var serial = 0
        val destinations = mutableListOf<ByteArray>()
        var afterWrite: () -> Unit = {}

        override fun nextBytes(bytes: ByteArray) {
            destinations.add(bytes)
            bytes.indices.forEach { bytes[it] = (serial + it).toByte() }
            serial += 17
            afterWrite()
        }
    }
}

/** Deliberately permissive unwrap: only the actual GCM/AAD check can reject altered nonce/wrapped/tag. */
internal class TestTerminalCodecKeysV1(override val attempt: TestTerminalAttemptV1) : TestTerminalDataKeyPortV1, AutoCloseable {
    var generations = 0
    var unwraps = 0
    var leaseCloses = 0
    var ownerCloses = 0
    var keyReads = 0
    var wrappedReads = 0
    var keyWidth = 32
    var wrappedWidth = 64
    var wrongArn = false
    var generateFailure: Throwable? = null
    var arnFailure: Throwable? = null
    var closeFailure: Throwable? = null
    var onGenerate: () -> Unit = {}
    var onUnwrap: () -> Unit = {}
    val requests = mutableListOf<JournalDataKeyRequestV1>()
    val transferred = mutableListOf<ByteArray>()
    val unwrapInputs = mutableListOf<ByteArray>()

    override fun generate(request: JournalDataKeyRequestV1): JournalGeneratedDataKeyV1 {
        generations++
        requests.add(request)
        generateFailure?.let { throw it }
        val key = TestTerminalCryptoReferenceV1.key().copyOf(keyWidth)
        val wrapped = TestTerminalCryptoReferenceV1.wrapped().copyOf(wrappedWidth)
        transferred.add(key)
        transferred.add(wrapped)
        onGenerate()
        return object : JournalGeneratedDataKeyV1 {
            override val keyArn: String get() = arn(request)
            override val plaintextKey: ByteArray get() = key.also { keyReads++ }
            override val wrappedKey: ByteArray get() = wrapped.also { wrappedReads++ }
            override fun close() = closeLease(key, wrapped)
        }
    }

    override fun unwrap(request: JournalDataKeyRequestV1, wrappedKey: ByteArray): JournalPlaintextDataKeyV1 {
        unwraps++
        requests.add(request)
        unwrapInputs.add(wrappedKey)
        val key = TestTerminalCryptoReferenceV1.key().copyOf(keyWidth)
        transferred.add(key)
        onUnwrap()
        return object : JournalPlaintextDataKeyV1 {
            override val keyArn: String get() = arn(request)
            override val plaintextKey: ByteArray get() = key.also { keyReads++ }
            override fun close() = closeLease(key, null)
        }
    }

    private fun arn(request: JournalDataKeyRequestV1): String {
        arnFailure?.let { throw it }
        return if (wrongArn) "alias/not-the-requested-key" else request.keyArn
    }

    private fun closeLease(key: ByteArray, wrapped: ByteArray?) {
        leaseCloses++
        terminalCodecZero(key)
        wrapped?.let(::terminalCodecZero)
        closeFailure?.let { throw it }
    }

    override fun close() { ownerCloses++ }

    fun assertCleared() {
        transferred.forEach(::terminalCodecZero)
        unwrapInputs.forEach(::terminalCodecZero)
        assertEquals(0, ownerCloses, "codec only borrows the port")
    }
}

/** Literal vectors were authored independently with Python AESGCM; this second reference uses JCE. */
internal object TestTerminalCryptoReferenceV1 {
    const val CONTEXT_KEY = "kira-complaint-journal-context-v1"
    const val PRIVATE_TEXT = "synthetic-private-terminal-provider-text"
    private val literals = checkNotNull(javaClass.getResourceAsStream("/test-terminal-codec-v1/crypto-goldens.json")).use {
        Json.parseToJsonElement(it.readBytes().toString(Charsets.UTF_8)).jsonObject
    }
    private val commonOrder = listOf(
        "envelopeSchemaVersion", "payloadSchemaVersion", "canonicalizerId", "objectKind", "encryptionAlgorithm", "dataKeyMode",
        "kmsKeyId", "kmsKeyArn", "bucket", "objectKey", "writerGeneration", "sealTerminalPrefix", "dataScopeKind", "dataScopeId",
    )

    fun order(kind: TestTerminalCodecKindV1): List<String> = commonOrder + if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) {
        listOf("epochStartInclusive", "epochEndInclusive", "routingKeyId", "sealId", "nonce")
    } else {
        listOf("publicationEpoch", "routingKeyId", "eventId", "nonce")
    }

    fun golden(kind: TestTerminalCodecKindV1): JsonObject = literals.getValue("records").jsonObject.getValue(kind.name).jsonObject
    fun key(): ByteArray = hex(literals.terminalText("synthetic_key_hex"))
    fun wrapped(): ByteArray = hex(literals.terminalText("synthetic_wrapped_hex"))
    fun nonce(serial: Int = 0): ByteArray = ByteArray(12) { (serial + it).toByte() }
    fun wire(kind: TestTerminalCodecKindV1): ByteArray = hex(golden(kind).terminalText("wire_hex"))
    fun header(kind: TestTerminalCodecKindV1): JsonObject = Json.parseToJsonElement(golden(kind).terminalText("header_utf8")).jsonObject

    fun header(kind: TestTerminalCodecKindV1, retainedRoute: JsonObject, serial: Int = 0): JsonObject = JsonObject(header(kind) + mapOf(
        "routingKeyId" to JsonPrimitive(retainedRoute.terminalText("routing_key_id")),
        "objectKey" to JsonPrimitive(retainedRoute.terminalText("object_key")),
        (if (kind == TestTerminalCodecKindV1.EPOCH_SEAL) "sealId" else "eventId") to JsonPrimitive(retainedRoute.terminalText("journal_id")),
        "nonce" to JsonPrimitive(url(nonce(serial))),
    ))

    fun context(kind: TestTerminalCodecKindV1, header: JsonObject = header(kind)): Map<String, String> =
        mapOf(CONTEXT_KEY to url(terminalFrame(listOf("kira-complaint-journal-kms-context-v1", "1") + values(kind, header))))

    fun aad(kind: TestTerminalCodecKindV1, header: JsonObject, wrapped: ByteArray, encryptedBytes: Int): ByteArray = terminalFrame(
        listOf("kira-complaint-journal-aad-v1", "1", "KJEV", "1", terminalCanonical(header).size.toString()) + values(kind, header) +
            listOf(wrapped.size.toString(), url(wrapped), encryptedBytes.toString()),
    )

    fun encrypt(kind: TestTerminalCodecKindV1, header: JsonObject, plaintext: ByteArray, wrapped: ByteArray = wrapped()): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key(), "AES"), GCMParameterSpec(128, unurl(header.terminalText("nonce"))))
        cipher.updateAAD(aad(kind, header, wrapped, plaintext.size + 16))
        return pack(terminalCanonical(header), wrapped, cipher.doFinal(plaintext))
    }

    fun pack(header: ByteArray, wrapped: ByteArray, encrypted: ByteArray): ByteArray = ByteArrayOutputStream().let { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeInt(0x4b4a4556)
            out.writeInt(1)
            listOf(header, wrapped, encrypted).forEach { out.writeInt(it.size); out.write(it) }
        }
        bytes.toByteArray()
    }

    fun parts(wire: ByteArray): Parts {
        val input = ByteBuffer.wrap(wire)
        assertEquals(0x4b4a4556, input.int)
        assertEquals(1, input.int)
        fun section(): ByteArray = ByteArray(input.int).also { input.get(it) }
        val parts = Parts(section(), section(), section())
        assertFalse(input.hasRemaining())
        return parts
    }

    fun values(kind: TestTerminalCodecKindV1, header: JsonObject): List<String> = order(kind).map(header::terminalText)
    fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    fun url(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    fun unurl(value: String): ByteArray = Base64.getUrlDecoder().decode(value)
    internal class Parts(val header: ByteArray, val wrapped: ByteArray, val encrypted: ByteArray)
}

internal fun TestTerminalCodecKindV1.documentName(): String = when (this) {
    TestTerminalCodecKindV1.INSTALLATION_MANIFEST -> "installationManifest"
    TestTerminalCodecKindV1.TEST_RUN_PURGE -> "purge"
    TestTerminalCodecKindV1.EPOCH_SEAL -> "epochSeal"
}

internal fun terminalCodecRejected(code: TestTerminalCodecFailureV1? = null, action: () -> Unit): TestTerminalCodecExceptionV1 {
    val failure = assertThrows<TestTerminalCodecExceptionV1> { action() }
    code?.let { assertEquals(it, failure.code) }
    assertNull(failure.cause)
    assertTrue(failure.suppressed.isEmpty() && failure.stackTrace.isEmpty())
    assertFalse(failure.toString().contains(TestTerminalCryptoReferenceV1.PRIVATE_TEXT))
    return failure
}

internal fun terminalCodecZero(bytes: ByteArray) { assertArrayEquals(ByteArray(bytes.size), bytes) }
