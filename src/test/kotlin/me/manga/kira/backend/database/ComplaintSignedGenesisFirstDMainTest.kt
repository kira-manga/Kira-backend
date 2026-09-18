package me.manga.kira.backend.database

import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintDesiredInstallationFailureV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.parallel.ResourceLock
import org.junit.jupiter.api.parallel.Resources
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.CommandLineRunner
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Path

@ResourceLock(Resources.SYSTEM_OUT)
@ResourceLock(Resources.SYSTEM_ERR)
class ComplaintSignedGenesisFirstDMainTest {
    @Test
    fun `select grammar retains six distinct absolute paths without reading them or exposing them in diagnostics`() {
        val args = arguments()
        val command = ComplaintSignedGenesisFirstDMain.parseArguments(args)
        val paths = listOf(command.manifest, command.intent, command.initialTrust, command.currentTrust, command.envelope, command.pin)
        assertEquals((2..12 step 2).map { Path.of(args[it]) }, paths)
        assertEquals("SignedGenesisFirstDCommandV1(redacted)", command.toString())
        val maximumPath = "/" + "x".repeat(4095)
        assertEquals(Path.of(maximumPath), ComplaintSignedGenesisFirstDMain.parseArguments(arguments().also { it[12] = maximumPath }).pin)
    }

    @Test
    fun `aliases omitted reordered duplicate or extra flags and invalid paths cannot describe a first D command`() {
        val shapes = listOf(
            emptyArray<String>(),
            arguments().also { it[0] = "bootstrap" },
            arguments().copyOfRange(0, 11),
            arguments().also {
                it[3] = "--initial-trust"
                it[5] = "--intent"
            },
            arguments().also { it[11] = "--signed-envelope" },
            arguments() + arrayOf("--target-D", "submitted-private-secret-canary"),
        )
        shapes.forEach(::refused)
        // Every new operand, not only --manifest, must obey the absolute-path rule.
        for (index in 2..12 step 2) {
            refused(arguments().also { it[index] = "relative-private-canary.json" })
        }
        for (path in listOf("", "/tmp/../private.json", "/tmp/./private.json", "/tmp/\u0000private.json", "/" + "x".repeat(4096))) {
            refused(arguments().also { it[2] = path })
        }
    }

    @Test
    fun `entry point is not a Spring component HTTP handler or startup runner`() {
        val type = ComplaintSignedGenesisFirstDMain::class.java
        assertFalse(ApplicationRunner::class.java.isAssignableFrom(type))
        assertFalse(CommandLineRunner::class.java.isAssignableFrom(type))
        val annotations = type.annotations.toList() + type.declaredMethods.flatMap { it.annotations.toList() }
        assertTrue(annotations.none { it.annotationClass.java.name.startsWith("org.springframework.") })
    }

    @Test
    fun `invalid invocation prints only bounded refusal before file credential or provider acquisition`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        PrintStream(stdout, false, Charsets.UTF_8).use { output ->
            PrintStream(stderr, false, Charsets.UTF_8).use { error ->
                try {
                    System.setOut(output)
                    System.setErr(error)
                    assertEquals(
                        1,
                        ComplaintSignedGenesisFirstDMain.run(arguments().also { it[11] = "--submitted-private-secret-canary" }),
                    )
                } finally {
                    System.setOut(previousOut)
                    System.setErr(previousErr)
                }
            }
        }
        assertEquals("", stdout.toString(Charsets.UTF_8))
        assertEquals("signed-genesis-first-d refused: INPUT_REFUSED${System.lineSeparator()}", stderr.toString(Charsets.UTF_8))
    }

    private fun arguments(): Array<String> = arrayOf(
        "select",
        "--manifest",
        "/deliberately-not-read/private-deployment.json",
        "--intent",
        "/deliberately-not-read/private-intent.json",
        "--initial-trust",
        "/deliberately-not-read/private-initial.json",
        "--current-trust",
        "/deliberately-not-read/private-current.json",
        "--signed-envelope",
        "/deliberately-not-read/private-envelope.json",
        "--genesis-pin",
        "/deliberately-not-read/private-pin.sha256",
    )

    private fun refused(args: Array<String>) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { ComplaintSignedGenesisFirstDMain.parseArguments(args) }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, failure.code)
        assertEquals("Desired configuration installation refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.stackTrace.isEmpty())
    }
}
