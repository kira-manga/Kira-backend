package me.manga.kira.backend.database

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishV1
import me.manga.kira.backend.complaint.infrastructure.catalog.preferCatalogFreezeCleanup
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class ComplaintCatalogGenesisPublishMainTest {
    @Test
    fun publisherGrammarAndModeSessionsStayClosed() {
        for (recover in listOf(false, true)) {
            val valid = arguments(recover)
            val parsed = ComplaintCatalogGenesisPublishMain.parseArguments(valid)
            assertEquals(recover, parsed.recover)
            assertEquals(Path.of(valid[2]), parsed.manifest)
            assertEquals(Path.of(valid[4]), parsed.targetDeployment)
            assertEquals(Path.of(valid[6]), parsed.pin)
            assertEquals("CatalogPublisherCommandV1(redacted,no-authority)", parsed.toString())
            listOf(
                emptyArray(),
                valid.dropLast(2).toTypedArray(),
                valid + "--force",
                valid.copyOf().apply { this[0] = "resume" },
                valid.copyOf().apply { this[0] = "PUBLISH" },
                valid.copyOf().apply { this[1] = "--genesis-pin" },
                valid.copyOf().apply { this[3] = "--target-D" },
                valid.copyOf().apply { this[5] = "--manifest" },
            ).forEach { refused { ComplaintCatalogGenesisPublishMain.parseArguments(it) } }
            for (index in listOf(2, 4, 6)) {
                for (path in listOf("", "relative-$CANARY", "/tmp/../$CANARY", "/$CANARY\u0000", "/" + "x".repeat(4096))) {
                    refused { ComplaintCatalogGenesisPublishMain.parseArguments(valid.copyOf().apply { this[index] = path }) }
                }
            }
        }
        CatalogGenesisPublishV1.begin().use { owner -> assertSessions(owner) }
        assertFixedModes()
        for (type in listOf(ComplaintCatalogGenesisPublishMain::class.java, ComplaintCatalogGenesisPublishWorkerMain::class.java)) {
            assertFalse(ApplicationRunner::class.java.isAssignableFrom(type) || CommandLineRunner::class.java.isAssignableFrom(type))
            assertTrue(
                (type.annotations.toList() + type.declaredMethods.flatMap { it.annotations.toList() })
                    .none { it.annotationClass.java.name.startsWith("org.springframework.") },
            )
        }
    }

    private fun assertSessions(owner: CatalogGenesisPublishV1) {
        val complete = environment()
        val parsed = ComplaintCatalogGenesisPublishWorkerMain.sessions(complete, owner, recover = false)
        assertEquals("CatalogPublisherSessionsV1(redacted,no-authority)", parsed.toString())
        val actual = listOf(parsed.secrets, parsed.primary, parsed.replica, checkNotNull(parsed.sealer), checkNotNull(parsed.put))
        FAMILIES.zip(actual).forEach { (family, session) ->
            val prefix = "KIRA_CATALOG_PUBLISH_$family"
            assertEquals(complete["${prefix}_ACCESS_KEY_ID"], session.accessKeyId())
            assertEquals(complete["${prefix}_SECRET_ACCESS_KEY"], session.secretAccessKey())
            assertEquals(complete["${prefix}_SESSION_TOKEN"], session.sessionToken())
        }
        for (recover in listOf(false, true)) {
            val allowed = complete.filterKeys { !recover || !it.startsWith(PUT_PREFIX) }
            val required = allowed.filterKeys { !it.startsWith("KIRA_CATALOG_PUBLISH_SEALER_") }
            assertNull(ComplaintCatalogGenesisPublishWorkerMain.sessions(required, owner, recover).sealer)
            for (name in required.keys) refused { ComplaintCatalogGenesisPublishWorkerMain.sessions(required - name, owner, recover) }
            for (name in allowed.keys - required.keys) {
                refused { ComplaintCatalogGenesisPublishWorkerMain.sessions(required + (name to allowed.getValue(name)), owner, recover) }
                refused { ComplaintCatalogGenesisPublishWorkerMain.sessions(allowed - name, owner, recover) }
            }
        }
        val readsOnly = complete.filterKeys { !it.startsWith(PUT_PREFIX) }
        assertNull(ComplaintCatalogGenesisPublishWorkerMain.sessions(readsOnly, owner, recover = true).put)
        // Even the typed test entry must not read/validate a supplied PUT value on recovery; production strips these before child start.
        val foreignPut = complete.mapValues { (name, value) -> if (name.startsWith(PUT_PREFIX)) "\u0000$CANARY" else value }
        assertNull(ComplaintCatalogGenesisPublishWorkerMain.sessions(foreignPut, owner, recover = true).put)
        for (family in FAMILIES) {
            for ((suffix, maximum) in BOUNDS) {
                val name = "KIRA_CATALOG_PUBLISH_${family}_$suffix"
                ComplaintCatalogGenesisPublishWorkerMain.sessions(complete + (name to "!".repeat(maximum)), owner, recover = false)
                for (value in listOf("", "x y", "x\n", "é", "x".repeat(maximum + 1))) {
                    refused { ComplaintCatalogGenesisPublishWorkerMain.sessions(complete + (name to value), owner, recover = false) }
                }
            }
        }
        for (foreign in listOf(
            emptyMap(),
            complete.mapKeys { it.key.replace("_PUBLISH_", "_AUTHOR_") },
            complete.mapKeys { it.key.replace("_PUBLISH_", "_TARGET_") },
            mapOf("AWS_ACCESS_KEY_ID" to CANARY, "AWS_SECRET_ACCESS_KEY" to CANARY, "AWS_SESSION_TOKEN" to CANARY, "AWS_PROFILE" to CANARY),
        )) {
            refused { ComplaintCatalogGenesisPublishWorkerMain.sessions(foreign, owner, recover = true) }
        }
    }

    private fun assertFixedModes() {
        val type = CatalogGenesisProcessV1::class.java.declaredClasses.single { it.simpleName == "Stage" }
        assertTrue(type.isEnum && Modifier.isPrivate(type.modifiers))
        val stages = type.enumConstants.associateBy { (it as Enum<*>).name }
        assertEquals(setOf("AUTHOR", "TARGET_FINALIZE", "PUBLISH", "PUBLISH_RECOVER"), stages.keys)
        val parser = type.getDeclaredMethod("parseArguments", Array<String>::class.java).also { it.isAccessible = true }
        for (recover in listOf(false, true)) {
            val stage = stages.getValue(if (recover) "PUBLISH_RECOVER" else "PUBLISH")
            assertSame(ComplaintCatalogGenesisPublishWorkerMain::class.java, poolTestField<Class<*>>(stage, "worker"))
            assertEquals(
                environment().keys.filter { !recover || !it.startsWith(PUT_PREFIX) }.toSet(),
                poolTestField<Set<String>>(stage, "credentialNames"),
            )
            val label = if (recover) "catalog-publish-recovery-child-retirement" else "catalog-publish-child-retirement"
            assertEquals(label, poolTestField<String>(stage, "retirementThread"))
            parser.invoke(stage, arguments(recover) as Any)
            val mismatch = assertThrows<InvocationTargetException> { parser.invoke(stage, arguments(!recover) as Any) }
            assertEquals(CatalogGenesisExitV1.INPUT_REFUSED, catalogPublisherFailureExit(checkNotNull(mismatch.cause)))
        }
        val successes = listOf(
            CatalogGenesisExitV1.FROZEN,
            CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE,
            CatalogGenesisExitV1.PROJECTED,
            CatalogGenesisExitV1.AWAIT_REPLICATION,
            CatalogGenesisExitV1.DUAL_COPY_OBSERVED,
        )
        assertEquals(listOf(0, 10, 11, 12, 13), successes.map { it.code })
    }

    @Test
    fun originalBudgetSignalsAndHistoricalReportingStaySticky(@TempDir parent: Path) {
        var now = 0L
        val owner = CatalogGenesisPublishV1.begin(PersistenceNanoClock { now })
        val originalBudget = owner.budget
        val missing = parent.resolve("must-not-open-$CANARY")
        val args = arguments().apply { this[2] = missing.toString() }
        now = 60_000_000_000L
        val expired = assertThrows<PersistenceBoundaryException> { originalBudget.remainingMillis(1) }
        assertEquals(CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED, catalogPublisherFailureExit(expired))
        // The unchanged core cannot certify cleanup within an expired budget; its sticky cleanup refusal conservatively wins.
        assertEquals(CatalogGenesisExitV1.CLEANUP_UNPROVEN, ComplaintCatalogGenesisPublishWorkerMain.execute(args, owner, emptyMap()))
        assertSame(originalBudget, owner.budget)
        assertFalse(Files.exists(missing) || poolTestField<Boolean>(owner, "entered"))
        assertTrue(poolTestField<Boolean>(owner, "closed"))
        for (field in listOf("release", "attempt", "readerBudget", "resolver", "acknowledgement")) assertNull(ownedCutField(owner, field))
        assertEquals(CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN, assertThrows<CatalogGenesisPublishExceptionV1> { owner.close() }.code)
        assertEquals(CatalogGenesisExitV1.CLEANUP_UNPROVEN, ComplaintCatalogGenesisPublishWorkerMain.execute(args, owner, environment()))
        assertSame(originalBudget, owner.budget)
        assertFalse(poolTestField<Boolean>(owner, "entered"))
        assertEquals(CatalogGenesisExitV1.INPUT_REFUSED, ComplaintCatalogGenesisPublishWorkerMain.execute(arrayOf(CANARY)))
        assertSignals()
        assertReporting()
    }

    private fun assertSignals() {
        val prior = Thread.interrupted()
        try {
            for (code in CatalogGenesisPublishFailureV1.entries) {
                assertEquals(expectedFailure(code.name), catalogPublisherFailureExit(CatalogGenesisPublishExceptionV1(code)))
                assertEquals(code === CatalogGenesisPublishFailureV1.INTERRUPTED, Thread.currentThread().isInterrupted)
                Thread.interrupted()
            }
            for (code in CatalogGenesisFreezeFailureV1.entries) {
                assertEquals(expectedFailure(code.name), catalogPublisherFailureExit(CatalogGenesisFreezeExceptionV1(code)))
                Thread.interrupted()
            }
            val fatal = AssertionError(CANARY)
            val cleanup = CatalogGenesisPublishExceptionV1(CatalogGenesisPublishFailureV1.CLEANUP_UNPROVEN)
            assertSame(fatal, catalogPublisherSignal(fatal))
            assertEquals(CatalogGenesisExitV1.FATAL, catalogPublisherFailureExit(preferCatalogFreezeCleanup(fatal, cleanup)))
            assertEquals(
                CatalogGenesisExitV1.CANCELLED,
                catalogPublisherFailureExit(preferCatalogFreezeCleanup(CancellationException(CANARY), cleanup)),
            )
            assertEquals(CatalogGenesisExitV1.FAILED, catalogPublisherFailureExit(IllegalStateException(CANARY)))
            val interrupted = catalogPublisherSignal(CatalogGenesisPublishExceptionV1(CatalogGenesisPublishFailureV1.INTERRUPTED))
            assertEquals(CatalogGenesisExitV1.FATAL, catalogPublisherFailureExit(preferCatalogFreezeCleanup(fatal, interrupted)))
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertEquals(CatalogGenesisExitV1.INTERRUPTED, catalogPublisherFailureExit(InterruptedIOException(CANARY)))
            for (recover in listOf(false, true)) {
                val notStarted = CatalogGenesisProcessV1.launchPublisher(arguments(recover))
                assertEquals(CatalogGenesisExitV1.INTERRUPTED, notStarted.exit)
                assertTrue(notStarted.retirementConfirmed && Thread.currentThread().isInterrupted)
            }
        } finally {
            Thread.interrupted()
            if (prior) Thread.currentThread().interrupt()
        }
    }

    private fun assertReporting() {
        val previousOut = System.out
        val previousErr = System.err
        val output = ByteArrayOutputStream()
        val error = ByteArrayOutputStream()
        val out = PrintStream(output, true, Charsets.UTF_8)
        val err = PrintStream(error, true, Charsets.UTF_8)
        try {
            System.setOut(out)
            System.setErr(err)
            for (retired in listOf(false, true)) {
                for (exit in CatalogGenesisExitV1.entries) assertReportedStatus(exit, retired, output, error)
            }
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            out.close()
            err.close()
        }
    }

    private fun assertReportedStatus(exit: CatalogGenesisExitV1, retired: Boolean, output: ByteArrayOutputStream, error: ByteArrayOutputStream) {
        output.reset()
        error.reset()
        val actual = ComplaintCatalogGenesisPublishMain.report(CatalogGenesisProcessObservationV1(exit, retired))
        val expected = when {
            !retired && exit !in setOf(CatalogGenesisExitV1.FATAL, CatalogGenesisExitV1.CANCELLED, CatalogGenesisExitV1.INTERRUPTED) ->
                CatalogGenesisExitV1.RETIREMENT_UNCONFIRMED

            exit in setOf(
                CatalogGenesisExitV1.FROZEN,
                CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE,
                CatalogGenesisExitV1.PROJECTED,
            ) -> CatalogGenesisExitV1.FAILED

            else -> exit
        }
        if (expected in setOf(CatalogGenesisExitV1.AWAIT_REPLICATION, CatalogGenesisExitV1.DUAL_COPY_OBSERVED)) {
            assertEquals(0, actual)
            assertEquals("catalog-genesis-publish ${expected.name}; historical-only\n", output.toString(Charsets.UTF_8))
            assertEquals("", error.toString(Charsets.UTF_8))
        } else {
            assertEquals(expected.code, actual)
            assertTrue(actual != 0)
            assertEquals("", output.toString(Charsets.UTF_8))
            val retirement = if (retired) "" else "; retirement=UNCONFIRMED"
            assertEquals("catalog-genesis-publish refused: ${expected.name}$retirement\n", error.toString(Charsets.UTF_8))
        }
        assertFalse(output.toString(Charsets.UTF_8).contains(CANARY) || error.toString(Charsets.UTF_8).contains(CANARY))
    }

    private fun refused(action: () -> Unit) {
        val failure = assertThrows<RuntimeException> { action() }
        assertTrue(failure is CatalogGenesisFreezeExceptionV1 || failure is CatalogGenesisPublishExceptionV1)
        assertEquals(CatalogGenesisExitV1.INPUT_REFUSED, catalogPublisherFailureExit(failure))
        assertFalse(failure.message.orEmpty().contains(CANARY))
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }

    private fun expectedFailure(name: String): CatalogGenesisExitV1 = when (name) {
        "INPUT_REFUSED" -> CatalogGenesisExitV1.INPUT_REFUSED
        "CLEANUP_UNPROVEN" -> CatalogGenesisExitV1.CLEANUP_UNPROVEN
        "TIME_BUDGET_EXHAUSTED" -> CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED
        "INTERRUPTED" -> CatalogGenesisExitV1.INTERRUPTED
        else -> CatalogGenesisExitV1.FAILED
    }

    private fun arguments(recover: Boolean = false): Array<String> = arrayOf(
        if (recover) "recover" else "publish",
        "--manifest",
        "/not-read/$CANARY-author.json",
        "--target-deployment",
        "/not-read/$CANARY-target.json",
        "--genesis-pin",
        "/not-read/$CANARY-independent.pin",
    )

    private fun environment(): Map<String, String> = FAMILIES.flatMap { family ->
        BOUNDS.keys.map { suffix -> "KIRA_CATALOG_PUBLISH_${family}_$suffix" to "synthetic-$family-$suffix" }
    }.toMap()

    private companion object {
        const val CANARY = "private-publisher-cli-canary"
        const val PUT_PREFIX = "KIRA_CATALOG_PUBLISH_PRIMARY_PUT_"
        val FAMILIES = listOf("SECRETS", "PRIMARY_READ", "REPLICA_READ", "SEALER", "PRIMARY_PUT")
        val BOUNDS = mapOf("ACCESS_KEY_ID" to 128, "SECRET_ACCESS_KEY" to 256, "SESSION_TOKEN" to 16_384)
    }
}
