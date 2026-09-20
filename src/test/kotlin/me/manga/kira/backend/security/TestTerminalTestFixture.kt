package me.manga.kira.backend.security

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCountHashV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialCutV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDispositionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEpochSealV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEventHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalEvidenceDigestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationEntryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInstallationManifestV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalInventoryWitnessV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalManifestSummaryV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalObjectRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPolicyRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalPurgeV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalRunContextV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealHeaderV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRefV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealRoleV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalSealSetV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalWriterDenialV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.util.Base64
import java.util.HexFormat
import java.util.UUID

/** Synthetic declarations only. Expected bytes/roots/routes were constructed offline, never by MAIN encoders. */
internal class TestTerminalTestFixture(
    val journal: TestOwnerDeleteJournalConfigurationV1 = ownerDeleteTestJournal(scope = ComplaintDataScope.of(UUID.fromString(SCOPE))),
) {
    private val documents = resource("canonical-goldens.json").getValue("documents").jsonObject
    val vectors = resource("roots-routing-goldens.json")
    val json = TestTerminalJsonV1(journal)
    val run = TestTerminalRunContextV1(SCOPE, 4, "a".repeat(64), "b".repeat(64), literal("encoding").terminalText("sha256"))
    val entries = listOf(
        TestTerminalInstallationEntryV1("00000000-0000-4000-8000-000000000002", TestTerminalDispositionV1.RETIRED),
        TestTerminalInstallationEntryV1("00000000-0000-4000-8000-000000000003", TestTerminalDispositionV1.DELETED),
    )
    val ordinarySeal = TestTerminalEpochSealV1(1, "EPOCH_SEAL", route("EPOCH_SEAL").terminalText("journal_id"), WRITER,
        "TEST", SCOPE, 1, 2, 2, "d".repeat(64), "", 17)
    val sealRef = TestTerminalSealRefV1(TestTerminalSealRoleV1.ORDINARY, WRITER, 1, 2, ordinarySeal.sealId, "",
        TestTerminalObjectRefV1(route("EPOCH_SEAL").terminalText("object_key"), "version-1", "c".repeat(64), literal("epochSeal").terminalText("sha256")))
    val manifest = manifest(context = context(id = route("INSTALLATION_MANIFEST").terminalText("journal_id")))
    val manifestRef = objectRef(vectors.getValue("manifest_ref").jsonObject)
    val summary = TestTerminalManifestSummaryV1(2, 1, 1, 1, rootHash("installations"), rootHash("chunks"))
    val inventory = vectors.getValue("inventory").jsonArray.map { element ->
        val item = element.jsonObject
        TestTerminalInventoryEntryV1(item.terminalText("writerGeneration"), item.terminalText("objectKind"),
            item.terminalText("epochStartInclusive").toLong(), item.terminalText("epochEndInclusive").toLong(), objectRef(item.getValue("object").jsonObject))
    }
    val purge = purge()

    fun literal(name: String): JsonObject = documents.getValue(name).jsonObject
    fun bytes(name: String): ByteArray = literal(name).terminalText("canonical_utf8").toByteArray(Charsets.UTF_8)
    fun value(name: String): JsonObject = Json.parseToJsonElement(literal(name).terminalText("canonical_utf8")).jsonObject
    fun root(name: String): JsonObject = vectors.getValue("roots").jsonObject.getValue(name).jsonObject
    fun rootHash(name: String): String = root(name).terminalText("sha256")
    fun countHash(name: String): TestTerminalCountHashV1 = TestTerminalCountHashV1(root(name).terminalText("count").toLong(), rootHash(name))
    fun route(kind: String, index: Int = 0): JsonObject = vectors.getValue("routes").jsonObject.getValue(kind).jsonArray[index].jsonObject
    fun mutate(name: String, vararg fields: Pair<String, JsonElement>): ByteArray = terminalCanonical(JsonObject(value(name) + fields))

    fun context(run: TestTerminalRunContextV1 = this.run, id: String = OPAQUE, epoch: Long = 3, writer: String = WRITER): TestTerminalEventContextV1 =
        TestTerminalEventContextV1(run, id, epoch, writer)

    fun manifest(
        context: TestTerminalEventContextV1 = context(), entries: List<TestTerminalInstallationEntryV1> = this.entries,
        index: Int = 0, count: Int = 1, root: String = rootHash("installations"),
    ): TestTerminalInstallationManifestV1 = TestTerminalInstallationManifestV1.create(context, index, count, root, entries)

    fun purge(
        context: TestTerminalEventContextV1 = context(id = route("TEST_RUN_PURGE").terminalText("journal_id")),
        seal: TestTerminalSealRefV1 = sealRef, finalEpoch: Long = 2,
        seals: TestTerminalCountHashV1 = countHash("preTerminalSeals"), inventory: TestTerminalCountHashV1 = countHash("preTerminalInventory"),
        summary: TestTerminalManifestSummaryV1 = this.summary,
    ): TestTerminalPurgeV1 = TestTerminalPurgeV1.create(context, finalEpoch, seal, seals, inventory, summary)

    fun seals(records: List<TestTerminalSealRefV1> = listOf(sealRef)): TestTerminalSealSetV1 =
        TestTerminalSealSetV1.create(SCOPE, 4, run.activationCatalogSha256, records)

    fun cut(role: String): TestTerminalDenialCutV1 {
        val first = TestTerminalInventoryWitnessV1(100, 101, 6, 4096, rootHash("preTerminalInventory"))
        return TestTerminalDenialCutV1("$role-role", TestTerminalPolicyRefV1("$role-policy", 1, "d".repeat(64)), 90, 95, 5,
            TestTerminalEvidenceDigestV1("e".repeat(64), 64), TestTerminalEvidenceDigestV1("f".repeat(64), 32),
            first, first.copy(startedAtEpochSecond = 106, completedAtEpochSecond = 107))
    }

    fun denial(writer: String = WRITER): TestTerminalWriterDenialV1 = TestTerminalWriterDenialV1(
        writer, ordinaryPrefix(writer), terminalPrefix(writer), cut("ordinary"), cut("terminal"),
    )

    fun denials(ranges: List<TestTerminalWriterDenialV1> = listOf(denial())): TestTerminalDenialSetV1 =
        TestTerminalDenialSetV1.create(SCOPE, 4, run.activationCatalogSha256, ranges)

    fun eventHeader(kind: String = "INSTALLATION_MANIFEST"): TestTerminalEventHeaderV1 {
        val selected = route(kind)
        val d = journal.declaration()
        return TestTerminalEventHeaderV1(1, 1, "kcj-1", kind, "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED", d.encryption.keyId,
            d.encryption.keyArn, d.journalLocation.bucket, selected.terminalText("object_key"), WRITER, terminalPrefix(), "TEST", SCOPE,
            3, selected.terminalText("routing_key_id"), selected.terminalText("journal_id"), "A".repeat(16))
    }

    fun sealHeader(): TestTerminalSealHeaderV1 {
        val d = journal.declaration()
        return TestTerminalSealHeaderV1(1, 1, "kcj-1", "EPOCH_SEAL", "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED", d.encryption.keyId,
            d.encryption.keyArn, d.journalLocation.bucket, sealRef.objectRef.objectKey, WRITER, terminalPrefix(), "TEST", SCOPE,
            1, 2, "test-route-01", ordinarySeal.sealId, "A".repeat(16))
    }

    fun installationRoot(owner: TestTerminalRootsV1, entries: List<TestTerminalInstallationEntryV1> = this.entries): TestTerminalInstallationRootV1 {
        val fold = owner.installations(run)
        entries.forEach(fold::firstPass)
        fold.beginSecondPass()
        entries.forEach(fold::secondPass)
        return fold.finish()
    }

    fun inventoryRoot(
        owner: TestTerminalRootsV1, entries: List<TestTerminalInventoryEntryV1> = inventory, seals: TestTerminalSealSetV1 = seals(),
    ): TestTerminalCountHashV1 {
        val fold = owner.preTerminalInventory(run, seals)
        entries.forEach(fold::firstPass)
        fold.beginSecondPass()
        entries.forEach(fold::secondPass)
        return fold.finish()
    }

    fun acquired(
        material: (Int) -> ByteArray = { index -> ByteArray(32) { n -> (1 + index * 37 + n).toByte() } },
        binding: (VersionedSecretBinding) -> VersionedSecretBinding = { it },
    ): List<AcquiredVersionedSecret> = journal.declaration().routing.keys.mapIndexed { index, key ->
        val descriptor = binding(VersionedSecretBinding.of(SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING, SecretMaterialPurpose.HMAC_SHA256, key.keyId, key.secret))
        AcquiredVersionedSecret.acquire(descriptor) { SecretVersionSnapshot(descriptor.version, material(index)) }
    }

    companion object {
        const val SCOPE = "00000000-0000-4000-8000-000000000001"
        const val WRITER = "c3333333-3333-4333-8333-333333333333"
        val OPAQUE: String = "A".repeat(43)
        fun uuid(index: Int): String = "10000000-0000-4000-8000-${index.toString().padStart(12, '0')}"
        fun opaque(index: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { it[31] = index.toByte() })
        fun ordinaryPrefix(writer: String = WRITER, scope: String = SCOPE): String = "complaints/journal/v1/$writer/test/$scope/ordinary/"
        fun terminalPrefix(writer: String = WRITER, scope: String = SCOPE): String = "complaints/journal/v1/$writer/test/$scope/seal-terminal/"
        fun key(epoch: Long, kind: String, id: String = OPAQUE, writer: String = WRITER, routing: String = "test-route-01"): String =
            "${terminalPrefix(writer)}$epoch/$routing/$kind/$id.kjev"

        private fun resource(name: String): JsonObject {
            require(name in setOf("canonical-goldens.json", "roots-routing-goldens.json"))
            val bytes = checkNotNull(TestTerminalTestFixture::class.java.getResourceAsStream("/test-terminal-v1/$name")).use { it.readBytes() }
            return Json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        }

        private fun objectRef(value: JsonObject): TestTerminalObjectRefV1 = TestTerminalObjectRefV1(
            value.terminalText("objectKey"), value.terminalText("objectVersion"), value.terminalText("ciphertextSha256"), value.terminalText("canonicalSha256"),
        )
    }
}

internal fun JsonObject.terminalText(name: String): String = getValue(name).jsonPrimitive.content

/** Independent mutation/reference writer for ASCII field names; never calls the production canonicalizer. */
internal fun terminalCanonical(value: JsonElement): ByteArray {
    fun render(element: JsonElement): String = when (element) {
        is JsonObject -> element.entries.sortedBy { it.key }.joinToString(",", "{", "}") { "${JsonPrimitive(it.key)}:${render(it.value)}" }
        is JsonArray -> element.joinToString(",", "[", "]", transform = ::render)
        else -> element.toString()
    }
    return render(value).toByteArray(Charsets.UTF_8)
}

/** Independent LP32BE writer: Java I/O lengths and UTF8, not TestTerminalFramesV1. */
internal fun terminalFrame(fields: List<String>): ByteArray = ByteArrayOutputStream().let { bytes ->
    DataOutputStream(bytes).use { data -> fields.forEach { field ->
        val encoded = field.toByteArray(Charsets.UTF_8)
        data.writeInt(encoded.size)
        data.write(encoded)
    } }
    bytes.toByteArray()
}

internal fun terminalHash(vararg bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").let { digest ->
    bytes.forEach(digest::update)
    HexFormat.of().formatHex(digest.digest())
}

internal fun terminalInstallationHash(context: TestTerminalRunContextV1, entries: List<TestTerminalInstallationEntryV1>): String {
    val retired = entries.count { it.disposition == TestTerminalDispositionV1.RETIRED }
    val fields = listOf("kira-test-installations-v1", context.dataScopeId, context.activationCatalogGeneration.toString(),
        context.activationCatalogSha256, context.configurationSha256, context.terminalEncodingSha256,
        entries.size.toString(), retired.toString(), (entries.size - retired).toString())
    return terminalHash(terminalFrame(fields), *entries.map { terminalFrame(listOf(it.installationId, it.disposition.name)) }.toTypedArray())
}

internal fun terminalRejected(label: String = "closed TEST syntax", action: () -> Unit): TestTerminalExceptionV1 {
    val failure = assertThrows<TestTerminalExceptionV1>(label) { action() }
    assertEquals("TEST terminal syntax rejected input: ${failure.code.name}", failure.message, label)
    assertNull(failure.cause, label)
    assertTrue(failure.suppressed.isEmpty() && failure.stackTrace.isEmpty(), label)
    return failure
}
