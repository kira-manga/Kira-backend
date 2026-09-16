package me.manga.kira.backend.common.infrastructure.persistence

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import javax.tools.ToolProvider

/** Synthetic public class shapes, compiled only into the caller's JUnit-owned temporary directory. */
internal object JdbcDiagnosticClassFixture {
    fun compile(root: Path, mode: JdbcFailureProbeMode): Path {
        val superclass = when (mode) {
            JdbcFailureProbeMode.WRONG_SUPERCLASS -> "java.sql.SQLTimeoutException"
            JdbcFailureProbeMode.LINKAGE_ERROR -> "MissingBase"
            else -> "java.sql.SQLException"
        }
        val accessorOverride = when (mode) {
            JdbcFailureProbeMode.OVERRIDDEN_STATE -> "public String getSQLState() { throw new AssertionError(); }"
            JdbcFailureProbeMode.OVERRIDDEN_CODE -> "public int getErrorCode() { throw new AssertionError(); }"
            else -> ""
        }
        val missingBase = if (mode == JdbcFailureProbeMode.LINKAGE_ERROR) "class MissingBase extends java.sql.SQLException {}" else ""
        val source = """
            package org.postgresql.util;
            public class PSQLException extends $superclass {
                static { System.setProperty("$JDBC_PROBE_INITIALIZATION_MARKER", "initialized"); }
                $accessorOverride
            }
            $missingBase
        """.trimIndent()
        val sourceFile = root.resolve("org/postgresql/util/PSQLException.java")
        Files.createDirectories(sourceFile.parent)
        Files.writeString(sourceFile, source)
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "JDK compiler required for the synthetic class-shape fixture." }
        val diagnostics = StringWriter()
        compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8).use { manager ->
            val units = manager.getJavaFileObjects(sourceFile.toFile())
            val options = listOf("--release", "21", "-proc:none", "-classpath", root.toString(), "-d", root.toString())
            check(compiler.getTask(diagnostics, manager, null, options, null, units).call()) {
                "Synthetic JDBC fixture compilation failed: ${diagnostics.toString().take(8192)}"
            }
        }
        if (mode == JdbcFailureProbeMode.LINKAGE_ERROR) {
            Files.delete(root.resolve("org/postgresql/util/MissingBase.class"))
        }
        return root
    }
}
