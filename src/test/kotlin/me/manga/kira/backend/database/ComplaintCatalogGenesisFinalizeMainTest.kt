package me.manga.kira.backend.database

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeFailureV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFinalizeV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeExceptionV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeFailureV1
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
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CancellationException

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class ComplaintCatalogGenesisFinalizeMainTest {
    @Test
    fun targetGrammarAndSessionsStayClosed() {
        val valid = arguments()
        val command = ComplaintCatalogGenesisFinalizeMain.parseArguments(valid)
        assertEquals(Path.of(valid[2]), command.manifest)
        assertEquals(Path.of(valid[4]), command.targetDeployment)
        assertEquals(Path.of(valid[6]), command.pin)
        assertEquals("CatalogTargetFinalizeCommandV1(redacted,no-authority)", command.toString())
        val invalid = listOf(
            emptyArray(),
            valid.dropLast(2).toTypedArray(),
            valid + "--force",
            valid.copyOf().apply { this[0] = "resume" },
            valid.copyOf().apply { this[0] = "FINALIZE" },
            valid.copyOf().apply { this[1] = "--genesis-pin" },
            valid.copyOf().apply { this[3] = "--target-D" },
            valid.copyOf().apply { this[5] = "--manifest" },
        )
        invalid.forEach { refused { ComplaintCatalogGenesisFinalizeMain.parseArguments(it) } }
        for (index in listOf(2, 4, 6)) {
            for (path in listOf("", "relative-$CANARY", "/tmp/../$CANARY", "/$CANARY\u0000", "/" + "x".repeat(4096))) {
                refused { ComplaintCatalogGenesisFinalizeMain.parseArguments(valid.copyOf().apply { this[index] = path }) }
            }
        }
        CatalogGenesisFinalizeV1.begin().use { owner ->
            val complete = environment()
            val parsed = ComplaintCatalogGenesisFinalizeWorkerMain.sessions(complete, owner)
            assertEquals("CatalogTargetFinalizeSessionsV1(redacted,no-authority)", parsed.toString())
            val actual = listOf(parsed.secrets, parsed.primary, parsed.replica, checkNotNull(parsed.sealer))
            FAMILIES.zip(actual).forEach { (family, credentials) ->
                val prefix = "KIRA_CATALOG_TARGET_$family"
                assertEquals(complete["${prefix}_ACCESS_KEY_ID"], credentials.accessKeyId())
                assertEquals(complete["${prefix}_SECRET_ACCESS_KEY"], credentials.secretAccessKey())
                assertEquals(complete["${prefix}_SESSION_TOKEN"], credentials.sessionToken())
            }
            val required = complete.filterKeys { !it.startsWith("KIRA_CATALOG_TARGET_SEALER_") }
            assertNull(ComplaintCatalogGenesisFinalizeWorkerMain.sessions(required, owner).sealer)
            for (name in required.keys) refused { ComplaintCatalogGenesisFinalizeWorkerMain.sessions(required - name, owner) }
            val optional = complete.keys - required.keys
            for (name in optional) {
                refused { ComplaintCatalogGenesisFinalizeWorkerMain.sessions(required + (name to complete.getValue(name)), owner) }
                refused { ComplaintCatalogGenesisFinalizeWorkerMain.sessions(complete - name, owner) }
            }
            for (family in FAMILIES) {
                for ((suffix, maximum) in BOUNDS) {
                    val name = "KIRA_CATALOG_TARGET_${family}_$suffix"
                    ComplaintCatalogGenesisFinalizeWorkerMain.sessions(complete + (name to "!".repeat(maximum)), owner)
                    for (value in listOf("", " ", "x y", "x\n", "\t", "\u007f", "é", "x".repeat(maximum + 1))) {
                        refused { ComplaintCatalogGenesisFinalizeWorkerMain.sessions(complete + (name to value), owner) }
                    }
                }
            }
            refused { ComplaintCatalogGenesisFinalizeWorkerMain.sessions(emptyMap(), owner) }
            refused {
                ComplaintCatalogGenesisFinalizeWorkerMain.sessions(complete.mapKeys { it.key.replace("_TARGET_", "_AUTHOR_") }, owner)
            }
            refused {
                ComplaintCatalogGenesisFinalizeWorkerMain.sessions(
                    mapOf("AWS_ACCESS_KEY_ID" to CANARY, "AWS_SECRET_ACCESS_KEY" to CANARY, "AWS_SESSION_TOKEN" to CANARY, "AWS_PROFILE" to CANARY),
                    owner,
                )
            }
            owner.requireRunning()
        }
        assertFixedStageWiring()
        for (type in listOf(ComplaintCatalogGenesisFinalizeMain::class.java, ComplaintCatalogGenesisFinalizeWorkerMain::class.java)) {
            assertFalse(ApplicationRunner::class.java.isAssignableFrom(type))
            assertFalse(CommandLineRunner::class.java.isAssignableFrom(type))
            assertTrue(
                (type.annotations.toList() + type.declaredMethods.flatMap { it.annotations.toList() })
                    .none { it.annotationClass.java.name.startsWith("org.springframework.") },
            )
        }
    }

    @Test
    fun originalBudgetAndFailureReportingStaySticky(@TempDir parent: Path) {
        var now = 0L
        val owner = CatalogGenesisFinalizeV1.begin(PersistenceNanoClock { now })
        val originalBudget = owner.budget
        val missing = parent.resolve("must-not-open-$CANARY")
        val args = arguments().apply { this[2] = missing.toString() }
        now = 60_000_000_000L
        val expired = assertThrows<PersistenceBoundaryException> { originalBudget.remainingMillis(1) }
        assertEquals(CatalogGenesisExitV1.TIME_BUDGET_EXHAUSTED, catalogTargetFinalizeFailureExit(expired))
        // The unchanged core cannot certify cleanup inside an expired budget; that later sticky cleanup refusal takes priority.
        assertEquals(CatalogGenesisExitV1.CLEANUP_UNPROVEN, ComplaintCatalogGenesisFinalizeWorkerMain.execute(args, owner, emptyMap()))
        assertSame(originalBudget, owner.budget)
        assertFalse(Files.exists(missing))
        assertFalse(poolTestField<Boolean>(owner, "entered"))
        assertTrue(poolTestField<Boolean>(owner, "closed"))
        for (field in listOf("release", "attempt", "refresh", "resolver", "targetChannel")) assertNull(ownedCutField(owner, field))
        assertEquals(CatalogGenesisExitV1.CLEANUP_UNPROVEN, ComplaintCatalogGenesisFinalizeWorkerMain.execute(args, owner, environment()))
        assertSame(originalBudget, owner.budget)
        assertFalse(poolTestField<Boolean>(owner, "entered"))
        assertEquals(CatalogGenesisExitV1.INPUT_REFUSED, ComplaintCatalogGenesisFinalizeWorkerMain.execute(arrayOf(CANARY)))
        val prior = Thread.interrupted()
        try {
            for (code in CatalogGenesisFinalizeFailureV1.entries) {
                assertEquals(expectedFailure(code.name), catalogTargetFinalizeFailureExit(CatalogGenesisFinalizeExceptionV1(code)))
                assertEquals(code === CatalogGenesisFinalizeFailureV1.INTERRUPTED, Thread.currentThread().isInterrupted)
                Thread.interrupted()
            }
            for (code in CatalogGenesisFreezeFailureV1.entries) {
                assertEquals(expectedFailure(code.name), catalogTargetFinalizeFailureExit(CatalogGenesisFreezeExceptionV1(code)))
                assertEquals(code === CatalogGenesisFreezeFailureV1.INTERRUPTED, Thread.currentThread().isInterrupted)
                Thread.interrupted()
            }
            val cleanup = CatalogGenesisFinalizeExceptionV1(CatalogGenesisFinalizeFailureV1.CLEANUP_UNPROVEN)
            val fatal = AssertionError(CANARY)
            assertSame(fatal, catalogTargetFinalizeSignal(fatal))
            assertEquals(CatalogGenesisExitV1.FATAL, catalogTargetFinalizeFailureExit(preferCatalogFreezeCleanup(fatal, cleanup)))
            assertEquals(
                CatalogGenesisExitV1.CANCELLED,
                catalogTargetFinalizeFailureExit(preferCatalogFreezeCleanup(CancellationException(CANARY), cleanup)),
            )
            assertEquals(CatalogGenesisExitV1.FAILED, catalogTargetFinalizeFailureExit(IllegalStateException(CANARY)))
            val interrupted = catalogTargetFinalizeSignal(CatalogGenesisFinalizeExceptionV1(CatalogGenesisFinalizeFailureV1.INTERRUPTED))
            assertEquals(CatalogGenesisExitV1.FATAL, catalogTargetFinalizeFailureExit(preferCatalogFreezeCleanup(fatal, interrupted)))
            assertTrue(Thread.currentThread().isInterrupted)
            Thread.interrupted()
            assertEquals(CatalogGenesisExitV1.INTERRUPTED, catalogTargetFinalizeFailureExit(InterruptedIOException(CANARY)))
            assertTrue(Thread.currentThread().isInterrupted)
            val notStarted = CatalogGenesisProcessV1.launchTargetFinalize(arguments())
            assertEquals(CatalogGenesisExitV1.INTERRUPTED, notStarted.exit)
            assertTrue(notStarted.retirementConfirmed)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (prior) Thread.currentThread().interrupt()
        }
        assertReporting()
    }

    private fun assertFixedStageWiring() {
        val type = CatalogGenesisProcessV1::class.java.declaredClasses.single { it.simpleName == "Stage" }
        assertTrue(type.isEnum && Modifier.isPrivate(type.modifiers))
        val stages = type.enumConstants.associateBy { (it as Enum<*>).name }
        assertEquals(setOf("AUTHOR", "TARGET_FINALIZE"), stages.keys)
        val author = stages.getValue("AUTHOR")
        val target = stages.getValue("TARGET_FINALIZE")
        assertSame(ComplaintCatalogAuthorWorkerMain::class.java, poolTestField<Class<*>>(author, "worker"))
        assertSame(ComplaintCatalogGenesisFinalizeWorkerMain::class.java, poolTestField<Class<*>>(target, "worker"))
        assertEquals(environment().keys, poolTestField<Set<String>>(target, "credentialNames"))
        val authorNames = listOf("SECRETS", "SIGN", "PRIMARY_READ", "REPLICA_READ").flatMap { family ->
            BOUNDS.keys.map { "KIRA_CATALOG_AUTHOR_${family}_$it" }
        }.toSet()
        assertEquals(authorNames, poolTestField<Set<String>>(author, "credentialNames"))
        assertEquals("catalog-author-child-retirement", poolTestField<String>(author, "retirementThread"))
        assertEquals("catalog-target-finalize-child-retirement", poolTestField<String>(target, "retirementThread"))
        assertEquals(0, CatalogGenesisExitV1.FROZEN.code)
        assertEquals(10, CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE.code)
        assertEquals(11, CatalogGenesisExitV1.PROJECTED.code)
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
                for (exit in CatalogGenesisExitV1.entries) {
                    output.reset()
                    error.reset()
                    val actual = ComplaintCatalogGenesisFinalizeMain.report(CatalogGenesisProcessObservationV1(exit, retired))
                    val expected = when {
                        !retired && exit !in setOf(CatalogGenesisExitV1.FATAL, CatalogGenesisExitV1.CANCELLED, CatalogGenesisExitV1.INTERRUPTED) ->
                            CatalogGenesisExitV1.RETIREMENT_UNCONFIRMED

                        exit in setOf(CatalogGenesisExitV1.FROZEN, CatalogGenesisExitV1.SIGNED_AWAITING_RELEASE) -> CatalogGenesisExitV1.FAILED
                        else -> exit
                    }
                    if (expected === CatalogGenesisExitV1.PROJECTED) {
                        assertEquals(0, actual)
                        assertEquals("catalog-target-finalize PROJECTED; historical-only\n", output.toString(Charsets.UTF_8))
                        assertEquals("", error.toString(Charsets.UTF_8))
                    } else {
                        assertEquals(expected.code, actual)
                        assertTrue(actual != 0)
                        assertEquals("", output.toString(Charsets.UTF_8))
                        val retirement = if (retired) "" else "; retirement=UNCONFIRMED"
                        assertEquals("catalog-target-finalize refused: ${expected.name}$retirement\n", error.toString(Charsets.UTF_8))
                    }
                    assertFalse(output.toString(Charsets.UTF_8).contains(CANARY) || error.toString(Charsets.UTF_8).contains(CANARY))
                }
            }
        } finally {
            System.setOut(previousOut)
            System.setErr(previousErr)
            out.close()
            err.close()
        }
    }

    private fun refused(action: () -> Unit) {
        val failure = assertThrows<RuntimeException> { action() }
        assertTrue(failure is CatalogGenesisFreezeExceptionV1 || failure is CatalogGenesisFinalizeExceptionV1)
        assertEquals(CatalogGenesisExitV1.INPUT_REFUSED, catalogTargetFinalizeFailureExit(failure))
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

    private fun arguments(): Array<String> = arrayOf(
        "finalize", "--manifest", "/not-read/$CANARY-author.json",
        "--target-deployment", "/not-read/$CANARY-target.json", "--genesis-pin", "/not-read/$CANARY-independent.pin",
    )

    private fun environment(): Map<String, String> = FAMILIES.flatMap { family ->
        BOUNDS.keys.map { suffix -> "KIRA_CATALOG_TARGET_${family}_$suffix" to "synthetic-$family-$suffix" }
    }.toMap()

    private companion object {
        const val CANARY = "private-target-cli-canary"
        val FAMILIES = listOf("SECRETS", "PRIMARY_READ", "REPLICA_READ", "SEALER")
        val BOUNDS = mapOf("ACCESS_KEY_ID" to 128, "SECRET_ACCESS_KEY" to 256, "SESSION_TOKEN" to 16_384)
    }
}
