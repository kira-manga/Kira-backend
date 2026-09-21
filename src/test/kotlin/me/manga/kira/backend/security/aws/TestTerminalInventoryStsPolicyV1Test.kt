package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Fixed request policy only; real recovery identity/cleanup is exercised separately by the connected native fixture. */
internal class TestTerminalInventoryStsPolicyV1Test {
    @Test
    fun `terminal recovery permits only whole registered prefix version reads and decrypt with explicit denial of writes`() {
        val journal = fullTestJournal(); val declaration = journal.declaration()
        val policy = EpochSealStsPolicy.forTestTerminalInventory(journal)
        val parsed = ObjectMapper().readTree(policy); val statements = parsed["Statement"]
        val bucket = "arn:aws:s3:::${declaration.journalLocation.bucket}"
        val resources = listOf("$bucket/${journal.sealTerminalPrefix}*", declaration.encryption.keyArn)
        val reads = listOf("s3:GetObjectVersion", "s3:GetObjectRetention", "kms:Decrypt")
        assertTrue(policy.length <= 2048)
        assertEquals("2012-10-17", parsed["Version"].textValue())
        assertEquals(listOf("Deny", "Deny", "Deny", "Allow", "Allow"), statements.map { it["Effect"].textValue() })
        assertEquals(reads + listOf("s3:ListBucketVersions", "sts:GetCallerIdentity"), statements[0]["NotAction"].map { it.textValue() })
        assertEquals(listOf(bucket) + resources, statements[1]["NotResource"].map { it.textValue() })
        assertEquals(resources, statements[3]["Resource"].map { it.textValue() }); assertEquals(reads, statements[3]["Action"].map { it.textValue() })
        assertEquals(journal.sealTerminalPrefix, statements[2]["Condition"]["StringNotEqualsIfExists"]["s3:prefix"].textValue())
        assertEquals(journal.sealTerminalPrefix, statements[4]["Condition"]["StringEquals"]["s3:prefix"].textValue())
        assertEquals(bucket, statements[4]["Resource"].textValue())
        for (forbidden in listOf("PutObject", "PutObjectRetention", "GenerateDataKey", "DeleteObject", "kms:EncryptionContext:", journal.ordinaryPrefix))
            assertFalse(policy.contains(forbidden), forbidden)
        val key = "${journal.sealTerminalPrefix}2/${declaration.routing.activeKeyId}/test-run-purge/${"A".repeat(43)}.kjev"
        val publisher = ObjectMapper().readTree(EpochSealStsPolicy.forTestRunPurge(journal, key, 2))["Statement"][3]
        assertTrue(publisher["Action"].any { it.textValue() == "s3:PutObject" })
        assertEquals("$bucket/$key", publisher["Resource"][0].textValue(), "The shared publisher remains exact-key, not a widened inventory publisher.")
    }
}
