package me.manga.kira.backend.security.aws

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.security.fullTestJournal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

/** Syntax-only exact-family confinement. These keys/policies are not SQL originals or dispatch authority. */
class TestInstallationManifestStsPolicyTest {
    @Test
    fun manifestPolicyKeepsExactObjectPrefixKmsAndOriginalDenyStatements() {
        val journal = fullTestJournal()
        val declaration = journal.declaration()
        val key = "${journal.sealTerminalPrefix}12/${declaration.routing.keys.first().keyId}/installation-manifest/${"A".repeat(43)}.kjev"
        val policy = EpochSealStsPolicy.forInstallationManifest(journal, key, 12)
        val parsed = ObjectMapper().readTree(policy)
        assertTrue(policy.length <= 2048)
        assertEquals("2012-10-17", parsed["Version"].textValue())
        val statements = parsed["Statement"]
        assertEquals(5, statements.size())
        assertEquals(listOf("Deny", "Deny", "Deny", "Allow", "Allow"), statements.map { it["Effect"].textValue() })
        val bucket = "arn:aws:s3:::${declaration.journalLocation.bucket}"
        val resources = listOf("$bucket/$key", declaration.encryption.keyArn)
        assertEquals(resources, statements[3]["Resource"].map { it.textValue() })
        assertEquals(listOf(bucket) + resources, statements[1]["NotResource"].map { it.textValue() })
        assertEquals(key, statements[2]["Condition"]["StringNotEqualsIfExists"]["s3:prefix"].textValue())
        assertEquals(key, statements[4]["Condition"]["StringEquals"]["s3:prefix"].textValue())
        assertEquals(bucket, statements[4]["Resource"].textValue())
        assertEquals(setOf("s3:PutObject", "s3:PutObjectRetention", "s3:GetObjectVersion", "s3:GetObjectRetention", "kms:GenerateDataKey", "kms:Decrypt"),
            statements[3]["Action"].map { it.textValue() }.toSet())
        assertFalse(policy.contains("kms:EncryptionContext:"), "Opaque context is not reinterpreted into fictitious IAM conditions.")
        assertFalse(statements[3]["Resource"].any { '*' in it.textValue() })
    }

    @Test
    fun manifestPolicyCannotAcceptSealPurgeForeignScopeEpochWriterOrUnretainedKey() {
        val journal = fullTestJournal()
        val declaration = journal.declaration()
        val routing = declaration.routing.keys.first().keyId
        val key = "${journal.sealTerminalPrefix}12/$routing/installation-manifest/${"A".repeat(43)}.kjev"
        val bad = listOf(
            key.replace("/installation-manifest/", "/epoch-seal/"),
            key.replace("/installation-manifest/", "/test-run-purge/"),
            key.replace(journal.scope.id.toString(), UUID.randomUUID().toString()),
            key.replace(declaration.writer.generationId, UUID.randomUUID().toString()),
            key.replace("/12/", "/13/"),
            key.replace("/$routing/", "/unretained-key/"),
            "$key/extra", key.replace(".kjev", "*"),
        )
        bad.forEach { assertThrows<RuntimeException> { EpochSealStsPolicy.forInstallationManifest(journal, it, 12) } }
        assertThrows<RuntimeException> { EpochSealStsPolicy.forKey(journal, key, 12) }
    }
}
