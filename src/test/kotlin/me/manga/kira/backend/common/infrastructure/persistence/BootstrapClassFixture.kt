package me.manga.kira.backend.common.infrastructure.persistence

import java.io.StringWriter
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import javax.tools.ToolProvider

/** All compilation stays in the caller-owned temporary directory; no generated class enters main resources. */
internal object BootstrapClassFixture {
    fun driver(root: Path, mode: BootstrapProbeCase): Path {
        val declaration = if (mode == BootstrapProbeCase.WRONG_TYPE) "" else "implements java.sql.Driver"
        val constructor = when (mode) {
            BootstrapProbeCase.PRIVATE_CONSTRUCTOR -> "private Driver() {}"
            BootstrapProbeCase.CONSTRUCTOR_FAILURE -> "public Driver() { throw new IllegalStateException(\"$BOOTSTRAP_CANARY\"); }"
            BootstrapProbeCase.CONSTRUCTOR_INTERRUPTED -> "public Driver() throws InterruptedException { throw new InterruptedException(); }"
            BootstrapProbeCase.CONSTRUCTOR_FATAL -> "public Driver() { throw FATAL; }"
            else -> "public Driver() {}"
        }
        val methods = if (declaration.isEmpty()) "" else driverMethods()
        val source = """
            package org.postgresql;
            public class Driver $declaration {
                public static final Error FATAL = new LinkageError("$BOOTSTRAP_CANARY");
                static { System.setProperty("$BOOTSTRAP_DRIVER_MARKER", "initialized"); }
                $constructor
                $methods
            }
        """.trimIndent()
        val classes = compile(root, mapOf("org/postgresql/Driver.java" to source))
        if (mode == BootstrapProbeCase.MISSING_CLASS) Files.delete(classes.resolve("org/postgresql/Driver.class"))
        return classes
    }

    fun discovery(root: Path): Path {
        val service = """
            package kira.bootstrap.fixture;
            public class ServiceDriver implements java.sql.Driver {
                public ServiceDriver() { System.setProperty("kira.synthetic.bootstrap.spi", "initialized"); }
                ${driverMethods()}
            }
        """.trimIndent()
        val legacy = """
            package kira.bootstrap.fixture;
            public class LegacySignal {
                static { System.setProperty("kira.synthetic.bootstrap.legacy", "initialized"); }
            }
        """.trimIndent()
        val classes = compile(root, mapOf("kira/bootstrap/fixture/ServiceDriver.java" to service, "kira/bootstrap/fixture/LegacySignal.java" to legacy))
        val serviceFile = classes.resolve("META-INF/services/java.sql.Driver")
        Files.createDirectories(serviceFile.parent)
        Files.writeString(serviceFile, "kira.bootstrap.fixture.ServiceDriver\n")
        return classes
    }

    fun bundle(root: Path): Path {
        val source = """
            package kira.bootstrap.b07.fixture;
            public class SyntheticLevels extends java.util.ListResourceBundle {
                static { System.setProperty("$BOOTSTRAP_BUNDLE_MARKER", "initialized"); }
                public SyntheticLevels() {}
                protected Object[][] getContents() {
                    return new Object[][] {{"KIRA_SYNTHETIC_LEVEL", "KIRA_SYNTHETIC_LOCALIZED"}};
                }
            }
        """.trimIndent()
        val classes = compile(root, mapOf("kira/bootstrap/b07/fixture/SyntheticLevels.java" to source))
        val expected = Path.of("kira/bootstrap/b07/fixture/SyntheticLevels.class")
        val actual = Files.walk(classes).use { stream -> stream.filter(Files::isRegularFile).map(classes::relativize).toList() }
        check(actual == listOf(expected)) { "Patch contains unexpected classes or resources." }
        check(!Files.isSymbolicLink(classes.resolve(expected)))
        println("BOOTSTRAP_C1_PATCH scope=synthetic-ordering-only module=java.logging class_sha256=${hash(classes.resolve(expected))}")
        return classes
    }

    private fun driverMethods(): String = """
        public java.sql.Connection connect(String u, java.util.Properties p) { throw new AssertionError("connect forbidden"); }
        public boolean acceptsURL(String u) { throw new AssertionError("acceptsURL forbidden"); }
        public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) { throw new AssertionError("properties forbidden"); }
        public int getMajorVersion() { return 1; }
        public int getMinorVersion() { return 0; }
        public boolean jdbcCompliant() { return false; }
        public java.util.logging.Logger getParentLogger() { throw new AssertionError("driver logger query forbidden"); }
    """.trimIndent()

    private fun compile(root: Path, sources: Map<String, String>): Path {
        val sourceRoot = Files.createDirectories(root.resolve("sources"))
        val classes = Files.createDirectories(root.resolve("classes"))
        val files = sources.map { (relative, source) ->
            sourceRoot.resolve(relative).also {
                Files.createDirectories(it.parent)
                Files.writeString(it, source)
                println("BOOTSTRAP_FIXTURE source=$relative sha256=${hash(it)}")
            }.toFile()
        }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler()) { "Java21 compiler required for synthetic fixtures." }
        val diagnostics = StringWriter()
        compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8).use { manager ->
            val options = listOf("--release", "21", "-proc:none", "-classpath", classes.toString(), "-d", classes.toString())
            check(compiler.getTask(diagnostics, manager, null, options, null, manager.getJavaFileObjectsFromFiles(files)).call()) {
                "Synthetic fixture compilation failed: ${diagnostics.toString().take(4096)}"
            }
        }
        return classes
    }

    private fun hash(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }
}
