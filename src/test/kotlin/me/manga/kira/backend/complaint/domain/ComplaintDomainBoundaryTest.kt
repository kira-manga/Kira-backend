package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class ComplaintDomainBoundaryTest {
    @Test
    fun `complaint domain remains independent of framework persistence transport and orchestration types`() {
        val root = Path.of("src/main/kotlin/me/manga/kira/backend/complaint/domain")
        assertTrue(Files.isDirectory(root), "The domain source tree must be present; this check must not silently skip.")
        val paths = Files.walk(root).use { it.filter { path -> path.toString().endsWith(".kt") }.toList() }
        assertTrue(paths.isNotEmpty())
        val allowedImports = setOf("java.time.Instant", "java.util.UUID", "java.util.Base64")
        val forbidden = listOf("org.springframework.", "jakarta.persistence.", "com.fasterxml.", "java.sql.", "java.net.")
        for (path in paths) {
            val source = Files.readString(path)
            val imports = source.lineSequence().filter { it.startsWith("import ") }.map { it.removePrefix("import ").trim() }.toList()
            assertTrue(imports.all { it in allowedImports || it.startsWith("kotlin.") }, "Unexpected domain dependency in ${path.fileName}: $imports")
            assertFalse(forbidden.any { source.contains(it) }, "A framework or I/O reference entered ${path.fileName}.")
            assertFalse(source.contains(".application.") || source.contains(".infrastructure."), "Wrong dependency direction in ${path.fileName}.")
        }
    }
}
