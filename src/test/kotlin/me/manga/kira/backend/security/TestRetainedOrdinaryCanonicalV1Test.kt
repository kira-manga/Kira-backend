package me.manga.kira.backend.security

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.ownerDeleteLiteralResource
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Local closed-content comparisons only. No fake KMS port, SQL source, native membership or purge authority. */
class TestRetainedOrdinaryCanonicalV1Test {
    private val journal = TestOwnerDeleteJournalConfigurationV1.registeredAdminErasure(ownerDeleteTestJournal().declaration())
    private val routing = ownerDeleteTestRouting(journal)
    private val actor = id(1)
    private val operation = id(2)
    private val target = id(3)
    private val grant = id(4)
    private val owner = id(5)

    @Test
    fun allFourOwnerAliasesMatchIndependentLiteralCanonicalHashesRatherThanThePrimaryHash() {
        val literals = ownerDeleteLiteralResource("journal-vectors.json").getValue("vectors").jsonArray.map { it.jsonObject }
            .filter { it.text("id") in setOf("ordinary_k1", "ordinary_k2", "ordinary_k3", "ordinary_k4") }
        assertEquals(4, literals.size)
        val first = literals.first()
        val primary = TestOwnerDeleteJournalCodecV1.restoreCanonical(routing,
            first.getValue("payload").jsonObject.text("canonical_utf8").toByteArray(), first.text("routing_key_id"))
        val original = primary.canonicalBytes()
        val hashes = literals.map { literal ->
            val selected = routing.derive(primary.comparison).candidates().single { it.routingKeyId == literal.text("routing_key_id") }
            TestRetainedOrdinaryCanonicalV1.sha256(routing, primary, selected).also {
                assertEquals(literal.getValue("payload").jsonObject.text("sha256"), it)
                if (selected != primary.route) assertNotEquals(primary.semanticSha256, it)
            }
        }
        assertEquals(4, hashes.toSet().size)
        assertArrayEquals(original, primary.canonicalBytes())
    }

    @Test
    fun emptyOneAndHundredTargetAllAliasesPreserveExactOrderedPayloadAndNeverMutateThePrimary() {
        for (count in listOf(0, 1, 100)) {
            val tuple = TestOwnerDeleteJournalTupleV1(17, actor, 9, operation, ByteArray(32) { 7 }, journal.scope,
                ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
            val targets = (1..count).map { id(100 + it) }
            assertAliases(tuple, targets)
            val active = routing.derive(tuple).active
            if (count > 1) assertThrows<RuntimeException> {
                TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, payload(tuple, targets.reversed(), active), active.routingKeyId)
            }
            if (count > 0) assertThrows<RuntimeException> {
                TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, payload(tuple, listOf(targets.first(), targets.first()), active), active.routingKeyId)
            }
        }
    }

    @Test
    fun adminGrantAndResolvedOwnerStayInEveryAliasHashEvenThoughNeitherChangesRouting() {
        val first = admin(grant, owner)
        val changed = listOf(admin(id(6), owner), admin(grant, id(7)))
        val primary = assertAliases(first, listOf(target))
        for (tuple in changed) {
            assertEquals(primary.route, routing.derive(tuple).active)
            val other = assertAliases(tuple, listOf(target))
            for (route in routing.derive(tuple).candidates()) assertNotEquals(
                TestRetainedOrdinaryCanonicalV1.sha256(routing, primary, route),
                TestRetainedOrdinaryCanonicalV1.sha256(routing, other, route),
            )
        }
    }

    @Test
    fun foreignRoutingOwnerAndAnotherOperationCandidateCannotBecomeAnAlias() {
        val tuple = TestOwnerDeleteJournalTupleV1(17, actor, 9, operation, ByteArray(32) { 7 }, journal.scope)
        val primary = assertAliases(tuple, listOf(target))
        assertThrows<RuntimeException> { TestRetainedOrdinaryCanonicalV1.sha256(ownerDeleteTestRouting(journal), primary, primary.route) }
        val other = TestOwnerDeleteJournalTupleV1(18, actor, 9, operation, ByteArray(32) { 7 }, journal.scope)
        assertThrows<RuntimeException> { TestRetainedOrdinaryCanonicalV1.sha256(routing, primary, routing.derive(other).active) }
    }

    private fun assertAliases(tuple: TestDeletionJournalTupleV1, targets: List<UUID>): TestOwnerDeleteJournalEventV1 {
        val routes = routing.derive(tuple)
        val bytes = payload(tuple, targets, routes.active)
        fun restore(value: ByteArray, key: String) = when (tuple) {
            is TestOwnerDeleteJournalTupleV1 -> TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, value, key)
            is TestAdminDeleteJournalTupleV1 -> TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, value, key)
        }
        val primary = restore(bytes, routes.active.routingKeyId)
        val hashes = routes.candidates().map { route ->
            val expected = payload(tuple, targets, route)
            val projected = TestRetainedOrdinaryCanonicalV1.sha256(routing, primary, route)
            assertEquals(terminalHash(expected), projected)
            assertEquals(restore(expected, route.routingKeyId).semanticSha256, projected)
            assertEquals(targets, primary.complaintIds())
            if (route != primary.route) assertNotEquals(primary.semanticSha256, projected)
            projected
        }
        assertEquals(4, hashes.toSet().size)
        assertArrayEquals(bytes, primary.canonicalBytes())
        return primary
    }

    /** Independent JSON object + test canonicalizer, not the closed MAIN payload encoder. */
    private fun payload(tuple: TestDeletionJournalTupleV1, targets: List<UUID>, route: TestOwnerDeleteRoutingCandidateV1): ByteArray {
        val fields = mutableMapOf<String, JsonElement>(
            "schemaVersion" to JsonPrimitive(1), "eventKind" to JsonPrimitive(tuple.eventKind.name), "eventId" to JsonPrimitive(route.eventId),
            "publicationEpoch" to JsonPrimitive(tuple.epoch), "writerGeneration" to JsonPrimitive(journal.declaration().writer.generationId),
            "actorKind" to JsonPrimitive(tuple.actorKind.name), "actorId" to JsonPrimitive(tuple.actorId.toString()),
            "operationKey" to JsonPrimitive(tuple.operationKey.toString()), "requestFingerprint" to JsonPrimitive(tuple.encodedFingerprint()),
            "dataScopeKind" to JsonPrimitive("TEST"), "dataScopeId" to JsonPrimitive(tuple.scope.id.toString()),
        )
        val installation = when (tuple) {
            is TestOwnerDeleteJournalTupleV1 -> tuple.actorId.also { fields["credentialVersion"] = JsonPrimitive(tuple.credentialVersion) }
            is TestAdminDeleteJournalTupleV1 -> tuple.ownerInstallationId.also { fields["consumedGrantId"] = JsonPrimitive(tuple.consumedGrantId.toString()) }
        }
        fields["ownerInstallationIds"] = JsonArray(listOf(JsonPrimitive(installation.toString())))
        fields["complaintIds"] = JsonArray(targets.map { JsonPrimitive(it.toString()) })
        return terminalCanonical(JsonObject(fields))
    }
    private fun admin(grant: UUID, owner: UUID) = TestAdminDeleteJournalTupleV1(17, actor, operation, ByteArray(32) { 7 }, journal.scope, grant, owner)
    private fun id(index: Int): UUID = UUID.fromString("00000000-0000-4000-8000-${index.toString().padStart(12, '0')}")
    private fun JsonObject.text(name: String): String = getValue(name).jsonPrimitive.content
}
