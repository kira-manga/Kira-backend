package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.security.aws.AwsJournalDataKeyAdapter
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources

/** Actual codec + signed KmsClient/raw HTTP SPI; synthetic credentials and rows confer no role, retention or publication authority. */
@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EpochSealAwsCodecV1Test {
    @Test
    fun `actual seal codec generates and authenticates with exact twenty two field KMS profile on one shrinking original total`() {
        val fixture = AwsJournalKmsFixture()
        val d = fixture.journal.declaration()
        val owner = EpochSealTestFixtureV1.owner(fixture.journal)
        val total = PersistenceTimeBudget.start(d.limits.deadlines.epochSealMillis.toLong(), PersistenceNanoClock { fixture.now })
        // Depends on the separately owned additive fixed seal SDK profile; ordinary open/withHttpFixture remain unchanged.
        AwsJournalDataKeyAdapter.withEpochSealHttpFixture(
            fixture.journal, AwsJournalKmsFixture.CREDENTIALS, fixture::httpClient, enclosingBudget = total,
        ) { fixture.now }.use { adapter ->
            val codec = EpochSealCodecV1(owner, adapter, EpochSealTestFixtureV1.Nonces()) { fixture.now }
            val attempt = codec.startAttempt(total)
            val builder = EpochSealManifestV1.start(owner, 1, 42, "", attempt)
            val eventKey = owner.derive(EpochSealTestFixtureV1.tuple()).active.objectKey
            builder.firstPass(eventKey, "synthetic-version", "c".repeat(64))
            builder.beginSecondPass()
            builder.secondPass(eventKey, "synthetic-version", "c".repeat(64))
            val content = codec.canonicalize(builder.finish(), 11, attempt)
            val encoded = codec.seal(content, attempt)
            val writer = d.writer.generationId
            val prefix = "complaints/journal/v1/$writer/live/00000000-0000-0000-0000-000000000000/seal-terminal/"
            val fields = listOf(
                "kira-complaint-journal-kms-context-v1", "1", "1", "1", "kcj-1", "EPOCH_SEAL", "AES-256-GCM", "FRESH_PER_OBJECT_KMS_WRAPPED",
                d.encryption.keyId, d.encryption.keyArn, d.journalLocation.bucket, content.route.objectKey, writer, prefix,
                "LIVE", "00000000-0000-0000-0000-000000000000", "1", "42", content.route.routingKeyId, content.route.sealId,
                "", AwsJournalKmsFixture.url(ByteArray(12) { it.toByte() }),
            )
            assertEquals(22, fields.size)
            val context = AwsJournalKmsFixture.url(AwsJournalKmsFixture.frame(fields))
            fixture.now = 29_800_000_000L
            val opened = codec.open(d.journalLocation.bucket, content.route.objectKey, content, encoded.wireBytes(), attempt)
            assertArrayEquals(content.canonicalBytes(), opened.content.canonicalBytes())
            assertEquals(encoded.wireSha256, opened.wireSha256)
            assertEquals(listOf(AwsJournalKmsFixture.GENERATE_TARGET, AwsJournalKmsFixture.DECRYPT_TARGET), fixture.requests.map { it.target() })
            fixture.requests.forEach { observed ->
                assertEquals(context, observed.fields()["EncryptionContext"][AwsJournalKmsFixture.CONTEXT_KEY].textValue())
                assertTrue(observed.http.firstMatchingHeader("Authorization").orElseThrow().startsWith("AWS4-HMAC-SHA256 "))
            }
            fixture.now = 30_000_000_000L
            assertEquals(
                EpochSealFailureV1.DEADLINE_EXHAUSTED,
                assertThrows(EpochSealExceptionV1::class.java) {
                    codec.open(d.journalLocation.bucket, content.route.objectKey, content, encoded.wireBytes(), attempt)
                }.code,
            )
            assertEquals(2, fixture.requests.size)
        }
        assertEquals(1, fixture.createdClients)
        assertEquals(1, fixture.closedClients)
        fixture.replies.forEach { assertEquals(1, it.closes) }
    }
}
