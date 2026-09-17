package me.manga.kira.backend.security.aws

import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.security.OwnerDeleteAllJournalException
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.CONTEXT_KEY
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.frame
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.keyBytes
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.request
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.url
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture.Companion.wrappedBytes
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Actual SDK with the existing raw HTTP fixture. No genuine role-policy, retention or seal authority. */
class AwsEpochSealDataKeyAdapterTest {
    @Test
    fun `fixed seal profile generates and unwraps through actual SDK with exact signed context and cleared leases`() {
        val fixture = AwsJournalKmsFixture()
        val context = sealContext(sealFields(fixture.journal))
        val requested = request(fixture.journal, context = context)
        fixture.sealAdapter().use { adapter ->
            val generated = adapter.generate(requested)
            val rawKey = generated.plaintextKey
            val rawWrapped = generated.wrappedKey
            assertArrayEquals(keyBytes(), rawKey)
            assertArrayEquals(wrappedBytes(), rawWrapped)
            val copiedWrapped = rawWrapped.copyOf()
            generated.close()
            assertTrue(rawKey.all { it == 0.toByte() } && rawWrapped.all { it == 0.toByte() })
            try {
                val decrypted = adapter.unwrap(requested, copiedWrapped)
                val plain = decrypted.plaintextKey
                assertArrayEquals(keyBytes(), plain)
                decrypted.close()
                assertTrue(plain.all { it == 0.toByte() })
            } finally {
                copiedWrapped.fill(0)
            }
            assertEquals(listOf(AwsJournalKmsFixture.GENERATE_TARGET, AwsJournalKmsFixture.DECRYPT_TARGET), fixture.requests.map { it.target() })
            fixture.requests.forEach { observed ->
                assertEquals(context.getValue(CONTEXT_KEY), observed.fields()["EncryptionContext"][CONTEXT_KEY].textValue())
                assertTrue(observed.http.firstMatchingHeader("Authorization").orElseThrow().startsWith("AWS4-HMAC-SHA256 "))
            }
        }
        assertEquals(1, fixture.createdClients)
        assertEquals(1, fixture.closedClients)
        fixture.replies.forEach { assertEquals(1, it.closes) }
    }

    @Test
    fun `seal and ordinary contexts are disjoint and malformed seal ranges identities and framing never dispatch`() {
        val fixture = AwsJournalKmsFixture()
        val initial = sealFields(fixture.journal)
        val malformed = listOf(
            5 to "OWNER_DELETE_ALL", 8 to "foreign-key", 9 to "alias/key", 10 to "foreign-bucket",
            11 to "wrong/key", 12 to "64000000-0000-4000-8000-000000000099", 13 to "ordinary/", 14 to "TEST",
            15 to "65000000-0000-4000-8000-000000000001", 16 to "0", 16 to "01", 16 to "43",
            17 to "-1", 17 to "042", 17 to "9223372036854775808", 18 to "foreign-routing-key",
            19 to "not-an-id", 20 to "f".repeat(64), 21 to url(ByteArray(11)),
        ).map { (index, value) -> initial.toMutableList().also { it[index] = value }.toList() } + listOf(
            initial.dropLast(1), initial + "extra", initial.toMutableList().also { it[16] = "2" },
            initial.toMutableList().also { it[16] = "2"; it[20] = "F".repeat(64) },
        )
        fixture.sealAdapter().use { adapter ->
            assertThrows(OwnerDeleteAllJournalException::class.java) { adapter.generate(request(fixture.journal)) }
            malformed.forEach { fields ->
                assertThrows(OwnerDeleteAllJournalException::class.java) { adapter.generate(request(fixture.journal, context = sealContext(fields))) }
            }
            assertTrue(fixture.requests.isEmpty())
            // A later-range context is a supported grammar, not evidence that its predecessor exists.
            val later = initial.toMutableList().also { it[16] = "2"; it[20] = "f".repeat(64) }
            adapter.generate(request(fixture.journal, context = sealContext(later))).close()
            assertEquals(1, fixture.requests.size)
        }
        val ordinary = AwsJournalKmsFixture()
        ordinary.adapter().use { adapter ->
            assertThrows(OwnerDeleteAllJournalException::class.java) { adapter.generate(request(ordinary.journal, context = sealContext(initial))) }
            assertTrue(ordinary.requests.isEmpty())
        }
        assertEquals(fixture.createdClients, fixture.closedClients)
        assertEquals(ordinary.createdClients, ordinary.closedClients)
    }

    private fun AwsJournalKmsFixture.sealAdapter(): AwsJournalDataKeyAdapter =
        AwsJournalDataKeyAdapter.withEpochSealHttpFixture(journal, AwsJournalKmsFixture.CREDENTIALS, ::httpClient) { now }
}

/** Independent literal LP order: neither production seal codec nor profile creates expected values. */
private fun sealFields(journal: ComplaintJournalConfigurationV1): List<String> {
    val d = journal.declaration()
    val writer = d.writer.generationId
    val prefix = "complaints/journal/v1/$writer/live/00000000-0000-0000-0000-000000000000/seal-terminal/"
    val routingId = d.routing.activeKeyId
    return listOf(
        "kira-complaint-journal-kms-context-v1", "1", "1", "1", "kcj-1", "EPOCH_SEAL", "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED",
        d.encryption.keyId, d.encryption.keyArn, d.journalLocation.bucket,
        "${prefix}writer/$writer/epoch/0000000000000000042/$routingId/${url(wrappedBytes(32))}",
        writer, prefix, "LIVE", "00000000-0000-0000-0000-000000000000", "1", "42", routingId,
        url(keyBytes()), "", url(wrappedBytes(12)),
    )
}

private fun sealContext(fields: List<String>): Map<String, String> = mapOf(CONTEXT_KEY to url(frame(fields)))
