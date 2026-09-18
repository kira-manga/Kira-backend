package me.manga.kira.backend.database

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.complaint.catalog.OfflineCatalogRotationFixture
import me.manga.kira.backend.complaint.catalog.OfflineTrustBundleFixture
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.CancellationException

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class ComplaintCatalogAuthorMainTest {
    @Test
    fun `closed grammar and strict public manifest map only to accepted author acquisition inputs`() {
        val manifest = "/not-read/author.json"
        val freeze = ComplaintCatalogAuthorMain.parseArguments(arrayOf("freeze", "--manifest", manifest))
        assertFalse(freeze.resume)
        assertNull(freeze.pin)
        assertEquals("CatalogAuthorCommandV1(redacted,no-authority)", freeze.toString())
        val pin = Path.of("/not-read/independent.pin")
        val resumed = ComplaintCatalogAuthorMain.parseArguments(arrayOf("resume", "--manifest", manifest, "--genesis-pin", pin.toString()))
        assertTrue(resumed.resume)
        val source = document()
        val parsed = CatalogAuthorManifestV1.parse(bytes(source), resumed.pin)
        assertEquals(pin, parsed.independentPin)
        assertEquals(source.database.name, parsed.database.database)
        assertEquals(source.database.password.resourceArn, parsed.database.authenticationPassword.version.resourceArn)
        assertEquals(source.signing.keyArn, parsed.signingKey.keyArn)
        assertEquals(source.trust.currentWriterGenerationIds, parsed.chainPolicy.currentWriterGenerationIds)
        assertArrayEquals(ByteArray(32) { 7 }, parsed.capacityDigest())
        assertEquals(Path.of(source.files.approvedIntent), parsed.files.approvedIntent)
        assertNull(CatalogAuthorManifestV1.parse(bytes(source), null).independentPin)

        val invalid = listOf(
            arrayOf("FREEZE", "--manifest", manifest),
            arrayOf("freeze", "--manifest", manifest, "--genesis-pin", pin.toString()),
            arrayOf("resume", "--target-D", "private-secret-canary"),
            arrayOf("resume", "--manifest", "relative.json"),
            arrayOf("resume", "--manifest", manifest, "--genesis-pin", "/tmp/../pin"),
        )
        invalid.forEach { refused { ComplaintCatalogAuthorMain.parseArguments(it) } }
        val text = bytes(source).decodeToString()
        listOf(
            text.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1"),
            text.dropLast(1) + ",\"authorUsername\":\"private-secret-canary\"}",
            text.dropLast(1) + ",\"independentPin\":\"private-secret-canary\"}",
            text + "{}",
            text.replaceFirst("\"port\":5432", "\"port\":5432.0"),
            text.replaceFirst("\"schemaVersion\":1", "\"schemaVersion\":null"),
        ).forEach { rejected -> refused { CatalogAuthorManifestV1.parse(rejected.toByteArray(), null) } }
        refused { CatalogAuthorManifestV1.parse(byteArrayOf(0xc3.toByte(), 0x28), null) }
        refused { CatalogAuthorManifestV1.parse(ByteArray(CatalogAuthorManifestV1.MAX_BYTES + 1), null) }
        refused { CatalogAuthorManifestV1.parse(bytes(source.copy(capacityPolicySha256 = "F".repeat(64))), null) }
    }

    @Test
    fun `one manifest descriptor obeys original pre-read budget and refuses links and overread`(@TempDir parent: Path) {
        var now = 0L
        val budget = PersistenceTimeBudget.start(60_000, PersistenceNanoClock { now })
        val expired = CatalogAuthorManifestFileV1(budget)
        now = 60_000_000_000L
        val failure = assertThrows<PersistenceBoundaryException> { expired.read(parent.resolve("must-not-be-opened")) }
        assertEquals(PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED, failure.code)
        expired.close()

        val input = Files.write(parent.resolve("manifest.json"), "{}".toByteArray())
        CatalogAuthorManifestFileV1(PersistenceTimeBudget.start(5_000)).use { file ->
            assertArrayEquals("{}".toByteArray(), file.read(input))
            assertThrows<CatalogGenesisFreezeExceptionV1> { file.read(input) }
        }
        val link = Files.createSymbolicLink(parent.resolve("link.json"), input)
        CatalogAuthorManifestFileV1(PersistenceTimeBudget.start(5_000)).use { file -> refused { file.read(link) } }
        Files.write(input, ByteArray(CatalogAuthorManifestV1.MAX_BYTES + 1))
        CatalogAuthorManifestFileV1(PersistenceTimeBudget.start(5_000)).use { file -> refused { file.read(input) } }
    }

    @Test
    fun `worker rejects before acquisition and process boundary preserves fatal cancellation and interruption`() {
        assertEquals(CatalogGenesisExitV1.INPUT_REFUSED, ComplaintCatalogAuthorWorkerMain.execute(arrayOf("--private-secret-canary")))
        for (type in listOf(ComplaintCatalogAuthorMain::class.java, ComplaintCatalogAuthorWorkerMain::class.java)) {
            assertFalse(ApplicationRunner::class.java.isAssignableFrom(type))
            assertFalse(CommandLineRunner::class.java.isAssignableFrom(type))
            assertTrue(
                (type.annotations.toList() + type.declaredMethods.flatMap { it.annotations.toList() })
                    .none { it.annotationClass.java.name.startsWith("org.springframework.") },
            )
        }
        val prior = Thread.interrupted()
        try {
            val cleanup = CatalogGenesisFreezeExceptionV1(CatalogGenesisFreezeFailureV1.CLEANUP_UNPROVEN)
            assertEquals(
                CatalogGenesisExitV1.FATAL,
                catalogAuthorFailureExit(preferCatalogFreezeCleanup(AssertionError("private-canary"), cleanup)),
            )
            assertEquals(
                CatalogGenesisExitV1.CANCELLED,
                catalogAuthorFailureExit(preferCatalogFreezeCleanup(CancellationException("private-canary"), cleanup)),
            )
            assertFalse(Thread.currentThread().isInterrupted)
            assertEquals(
                CatalogGenesisExitV1.INTERRUPTED,
                catalogAuthorFailureExit(preferCatalogFreezeCleanup(InterruptedException("private-canary"), cleanup)),
            )
            assertTrue(Thread.currentThread().isInterrupted)
            val notStarted = CatalogGenesisProcessV1.launchAuthor(arrayOf("freeze", "--manifest", "/not-read/author.json"))
            assertEquals(CatalogGenesisExitV1.INTERRUPTED, notStarted.exit)
            assertTrue(notStarted.retirementConfirmed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (prior) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `closed reporting never turns unconfirmed retirement into success or erases stronger signals`() {
        assertEquals(CatalogGenesisExitV1.FATAL, preferCatalogGenesisExit(CatalogGenesisExitV1.FATAL, CatalogGenesisExitV1.INTERRUPTED))
        assertEquals(CatalogGenesisExitV1.FATAL, preferCatalogGenesisExit(CatalogGenesisExitV1.CANCELLED, CatalogGenesisExitV1.FATAL))
        assertEquals(CatalogGenesisExitV1.CANCELLED, preferCatalogGenesisExit(CatalogGenesisExitV1.CANCELLED, CatalogGenesisExitV1.INTERRUPTED))
        assertEquals(
            CatalogGenesisExitV1.INTERRUPTED,
            preferCatalogGenesisExit(CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED, CatalogGenesisExitV1.INTERRUPTED),
        )
        assertEquals(
            CatalogGenesisExitV1.CLEANUP_UNPROVEN,
            preferCatalogGenesisExit(CatalogGenesisExitV1.CLEANUP_UNPROVEN, CatalogGenesisExitV1.FAILED),
        )
        val previousOut = System.out
        val previousErr = System.err
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val out = PrintStream(output, true, Charsets.UTF_8)
        val err = PrintStream(error, true, Charsets.UTF_8)
        try {
            System.setOut(out)
            System.setErr(err)
            for (exit in CatalogGenesisExitV1.entries) {
                output.reset()
                error.reset()
                val observed = catalogGenesisProcessObservation(exit, false)
                val expected = if (exit in setOf(CatalogGenesisExitV1.FATAL, CatalogGenesisExitV1.CANCELLED, CatalogGenesisExitV1.INTERRUPTED)) {
                    exit
                } else {
                    CatalogGenesisExitV1.RETIREMENT_UNCONFIRMED
                }
                assertFalse(observed.retirementConfirmed)
                assertEquals(expected, observed.exit)
                // The reporting boundary must also reject an unnormalized internal observation.
                assertNotEquals(0, ComplaintCatalogAuthorMain.report(CatalogGenesisProcessObservationV1(exit, false)))
                assertEquals("", output.toString(Charsets.UTF_8))
                assertEquals("catalog-author refused: ${expected.name}; retirement=UNCONFIRMED\n", error.toString(Charsets.UTF_8))
            }
            for (exit in listOf(CatalogGenesisExitV1.FROZEN, CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE)) {
                output.reset()
                error.reset()
                assertEquals(0, ComplaintCatalogAuthorMain.report(catalogGenesisProcessObservation(exit, true)))
                assertEquals("catalog-author ${exit.name}; historical-only\n", output.toString(Charsets.UTF_8))
                assertEquals("", error.toString(Charsets.UTF_8))
            }
            output.reset()
            error.reset()
            assertEquals(
                CatalogGenesisExitV1.FAILED.code,
                ComplaintCatalogAuthorMain.report(CatalogGenesisProcessObservationV1(CatalogGenesisExitV1.PROJECTED, true)),
            )
            assertEquals("", output.toString(Charsets.UTF_8))
            assertEquals("catalog-author refused: FAILED\n", error.toString(Charsets.UTF_8))
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            out.close()
            err.close()
        }
    }

    private fun refused(action: () -> Unit) {
        val failure = assertThrows<CatalogGenesisFreezeExceptionV1> { action() }
        assertEquals(CatalogGenesisFreezeFailureV1.INPUT_REFUSED, failure.code)
        assertEquals("Catalog genesis freeze refused: INPUT_REFUSED.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun bytes(document: CatalogAuthorDocumentV1): ByteArray = Json.encodeToString(CatalogAuthorDocumentV1.serializer(), document).toByteArray()

    private fun document(): CatalogAuthorDocumentV1 {
        val fixture = OfflineTrustBundleFixture
        val policy = OfflineCatalogRotationFixture.policy()
        val trust = policy.trustBundlePolicy
        val limits = policy.limits
        val spki = fixture.firstSigner.public.encoded
        return CatalogAuthorDocumentV1(
            1,
            CatalogAuthorDatabaseDocumentV1(
                "database.example",
                5432,
                "kira",
                CatalogAuthorSecretDocumentV1(
                    "author-password",
                    "arn:aws:secretsmanager:eu-west-1:123456789012:secret:fixture-catalog-author-Ab12Cd",
                    "650e8400-e29b-41d4-a716-446655440001",
                ),
                "/not-read/ca.pem",
                "/not-read/protected-ca",
            ),
            CatalogAuthorFilesDocumentV1("/not-read/intent", "/not-read/T0", "/not-read/Tn", "/not-read/approvals"),
            CatalogAuthorTrustDocumentV1(
                Base64.getEncoder().encodeToString(trust.rootPublicKeySpki), trust.rootPublicKeySha256, trust.rootKeyId, trust.rootAlgorithmId,
                trust.expectedEnvironment, trust.expectedCatalogLocations, trust.minimumBundleVersion, policy.currentWriterGenerationIds,
                policy.currentApproverIds,
                CatalogAuthorChainLimitsV1(
                    limits.maximumEnvelopeBytes,
                    limits.maximumManifestRecords,
                    limits.maximumGenerations,
                    limits.maximumEncodedBytes,
                ),
            ),
            "07".repeat(32),
            CatalogAuthorSigningDocumentV1(
                "catalog-old",
                "arn:aws:kms:us-east-1:111111111111:key/11111111-1111-4111-8111-111111111111",
                fixture.ALGORITHM,
                Base64.getEncoder().encodeToString(spki),
                Sha256.hex(spki),
            ),
            "/not-read/release",
        )
    }
}
