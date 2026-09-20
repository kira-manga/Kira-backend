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
class ComplaintDesiredConfigurationMainTest {
    @Test
    fun `explicit bootstrap and bounded supersede grammar only describe an absolute manifest path`() {
        val path = "/deliberately-not-read/private-manifest.json"
        val bootstrap = ComplaintDesiredConfigurationMain.parseArguments(arrayOf("bootstrap", "--manifest", path))
        assertEquals(Path.of(path), bootstrap.path)
        assertNull(bootstrap.expectedGeneration)
        assertEquals("DesiredConfigurationCommandV1(redacted)", bootstrap.toString())
        for (generation in listOf(1L, Long.MAX_VALUE - 1)) {
            val supersede = ComplaintDesiredConfigurationMain.parseArguments(
                arrayOf("supersede", "--expected-generation", generation.toString(), "--manifest", path),
            )
            assertEquals(generation, supersede.expectedGeneration)
            assertEquals(Path.of(path), supersede.path)
        }
    }

    @Test
    fun `command aliases extra flags noncanonical generations and nonnormalized paths are refused`() {
        val path = "/deliberately-not-read/private-manifest.json"
        val shapes = listOf(
            emptyArray<String>(),
            arrayOf("BOOTSTRAP", "--manifest", path),
            arrayOf("bootstrap", "--target-D", "submitted-private-canary"),
            arrayOf("bootstrap", "--manifest", path, "--expected-generation", "1"),
            arrayOf("supersede", "--manifest", path, "--expected-generation", "1"),
        )
        shapes.forEach(::refused)
        for (number in listOf("0", "01", "+1", "-1", "1.0", "1e1", Long.MAX_VALUE.toString(), "9223372036854775808")) {
            refused(arrayOf("supersede", "--expected-generation", number, "--manifest", path))
        }
        for (invalidPath in listOf("relative.json", "/tmp/../private.json", "/tmp/./private.json", "/tmp/\u0000private.json", "/" + "x".repeat(4096))) {
            refused(arrayOf("bootstrap", "--manifest", invalidPath))
        }
    }

    @Test
    fun `entry point is neither a Spring component HTTP handler nor startup runner`() {
        val type = ComplaintDesiredConfigurationMain::class.java
        assertFalse(ApplicationRunner::class.java.isAssignableFrom(type))
        assertFalse(CommandLineRunner::class.java.isAssignableFrom(type))
        val annotations = type.annotations.toList() + type.declaredMethods.flatMap { it.annotations.toList() }
        assertTrue(annotations.none { it.annotationClass.java.name.startsWith("org.springframework.") })
    }

    @Test
    fun `invalid invocation prints only bounded refusal before manifest or provider acquisition`() {
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val previousOut = System.out
        val previousErr = System.err
        PrintStream(stdout, false, Charsets.UTF_8).use { output ->
            PrintStream(stderr, false, Charsets.UTF_8).use { error ->
                try {
                    System.setOut(output)
                    System.setErr(error)
                    // Invalid command shape: this cannot enter file, credential, SDK or database acquisition.
                    assertEquals(
                        1,
                        ComplaintDesiredConfigurationMain.run(arrayOf("bootstrap", "--target-D", "submitted-private-secret-canary")),
                    )
                } finally {
                    System.setOut(previousOut)
                    System.setErr(previousErr)
                }
            }
        }
        assertEquals("", stdout.toString(Charsets.UTF_8))
        assertEquals("desired-configuration refused: INPUT_REFUSED${System.lineSeparator()}", stderr.toString(Charsets.UTF_8))
    }

    private fun refused(args: Array<String>) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { ComplaintDesiredConfigurationMain.parseArguments(args) }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, failure.code)
        assertEquals("Desired configuration installation refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
    }
}
