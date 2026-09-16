package me.manga.kira.backend.common.infrastructure.persistence

import java.io.StringWriter
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.Locale
import javax.tools.ToolProvider

/** Synthetic provider shapes only; private metadata/model loader stays inside one owned child JVM. */
internal class PgTimerMetadataFixture(root: Path, private val mode: PgTimerProbeCase) : AutoCloseable {
    private val classes = compile(root)
    val loader: ClassLoader = object : URLClassLoader(arrayOf(classes.toUri().toURL()), PgTimerProbe::class.java.classLoader) {
        @Synchronized
        override fun loadClass(name: String, resolve: Boolean): Class<*> {
            val own = name.startsWith("org.postgresql.") || name.startsWith("kira.timer.fixture.")
            val foreign = mode === PgTimerProbeCase.META_FOREIGN_LOADER && name.startsWith("org.postgresql.util.")
            if (!own || foreign) return super.loadClass(name, resolve)
            val type = findLoadedClass(name) ?: findClass(name)
            if (resolve) resolveClass(type)
            return type
        }

        override fun getResources(name: String): java.util.Enumeration<java.net.URL> {
            check(name == "org/postgresql/driverconfig.properties")
            return Collections.emptyEnumeration()
        }
    }

    fun count(field: String): Int = counters().getField(field).getInt(null)

    fun fatal(): Error = counters().getField("FATAL").get(null) as Error

    fun hook(field: String, action: Runnable) = counters().getField(field).set(null, action)

    fun timerThread(): Thread? = (counters().getField("timerThread").get(null) as java.util.concurrent.atomic.AtomicReference<*>).get() as Thread?

    fun verifyInheritedMetadataShape() {
        val name = when (mode) {
            PgTimerProbeCase.META_UTILITY_INHERITED -> "getSharedTimer"
            PgTimerProbeCase.META_ACQUIRE_INHERITED -> "getTimer"
            PgTimerProbeCase.META_RELEASE_INHERITED -> "releaseTimer"
            else -> return
        }
        val utility = mode === PgTimerProbeCase.META_UTILITY_INHERITED
        val type = Class.forName(if (utility) "org.postgresql.Driver" else "org.postgresql.util.SharedTimer", false, loader)
        val parent = Class.forName(if (utility) "org.postgresql.Parent" else "org.postgresql.util.TimerParent", false, loader)
        check(type.superclass === parent)
        check(type.declaredMethods.none { it.name == name }) { "Inherited fixture unexpectedly declares the targeted method." }
        val inherited = type.getMethod(name)
        check(inherited.declaringClass === parent)
        check(!inherited.isBridge && !inherited.isSynthetic)
        check(Modifier.isPublic(inherited.modifiers) && !Modifier.isAbstract(inherited.modifiers))
        check(Modifier.isStatic(inherited.modifiers) == utility)
        val returns = when {
            utility -> Class.forName("org.postgresql.util.SharedTimer", false, loader)
            mode === PgTimerProbeCase.META_ACQUIRE_INHERITED -> java.util.Timer::class.java
            else -> Void.TYPE
        }
        check(inherited.returnType === returns)
        if (!utility) check(Modifier.isPublic(parent.modifiers))
    }

    private fun counters(): Class<*> = Class.forName("kira.timer.fixture.Counters", true, loader)

    override fun close() {
        val timer = counters().getField("timer").get(null) as java.util.Timer?
        val thread = timerThread()
        val emergency = timer != null && count("release") == 0
        // Only our synthetic provider's generic Timer, never real pgjdbc shared state. Emergency cleanup fails.
        if (emergency) timer?.cancel()
        val ended = runCatching {
            if (timer != null) awaitPgFixtureFact { thread != null && !thread.isAlive }
        }
        (loader as URLClassLoader).close()
        ended.getOrThrow()
        check(!emergency) { "Synthetic timer required emergency cleanup." }
        println("PG_TIMER_FIXTURE_CLEANUP all_terminated=true emergency=false proof=MODEL_PROVIDER")
    }

    private fun compile(root: Path): Path {
        val sourceRoot = Files.createDirectories(root.resolve("fixture-source"))
        val destination = Files.createDirectories(root.resolve("fixture-classes"))
        val sources = mapOf(
            "org/postgresql/Driver.java" to driver(),
            "org/postgresql/util/SharedTimer.java" to shared(),
            "org/postgresql/util/TimerParent.java" to timerParent(),
            "kira/timer/fixture/Counters.java" to countersSource(),
        )
        val files = sources.map { (name, body) ->
            sourceRoot.resolve(name).also {
                Files.createDirectories(it.parent)
                Files.writeString(it, body)
            }.toFile()
        }
        val compiler = checkNotNull(ToolProvider.getSystemJavaCompiler())
        val diagnostics = StringWriter()
        compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8).use { manager ->
            val options = listOf("--release", "21", "-proc:none", "-classpath", destination.toString(), "-d", destination.toString())
            check(compiler.getTask(diagnostics, manager, null, options, null, manager.getJavaFileObjectsFromFiles(files)).call()) {
                "Synthetic timer fixture compilation failed: ${diagnostics.toString().take(4096)}"
            }
        }
        if (mode === PgTimerProbeCase.META_MISSING_SHARED) Files.delete(destination.resolve("org/postgresql/util/SharedTimer.class"))
        return destination
    }

    private fun driver(): String {
        val inaccessible = mode in setOf(PgTimerProbeCase.META_SHARED_PACKAGE_PRIVATE, PgTimerProbeCase.META_MISSING_SHARED)
        val returns = if (inaccessible || mode === PgTimerProbeCase.META_UTILITY_RETURN) "Object" else "org.postgresql.util.SharedTimer"
        val modifier = when (mode) {
            PgTimerProbeCase.META_UTILITY_PRIVATE -> "private static"
            PgTimerProbeCase.META_UTILITY_INSTANCE -> "public"
            else -> "public static"
        }
        val args = if (mode === PgTimerProbeCase.META_UTILITY_ARGUMENT) "int unused" else ""
        val expression = when (mode) {
            PgTimerProbeCase.MODEL_NULL_UTILITY, PgTimerProbeCase.META_SHARED_ABSTRACT -> "return null;"
            PgTimerProbeCase.MODEL_UTILITY_FAILURE -> "throw new kira.timer.fixture.Counters.Hostile();"
            PgTimerProbeCase.MODEL_SUBCLASS_UTILITY -> "return new org.postgresql.util.SharedTimer() {};"
            else -> if (inaccessible) "return null;" else "return new org.postgresql.util.SharedTimer();"
        }
        val method = "$modifier $returns getSharedTimer($args) { kira.timer.fixture.Counters.utility++; $expression }"
        val inherited = mode === PgTimerProbeCase.META_UTILITY_INHERITED
        return """
            package org.postgresql;
            public class Driver ${if (inherited) "extends Parent" else ""} implements java.sql.Driver {
                public Driver() { kira.timer.fixture.Counters.constructors++; }
                ${if (inherited) "" else method}
                public java.sql.Connection connect(String u, java.util.Properties p) { throw new AssertionError("connect forbidden"); }
                public boolean acceptsURL(String u) { throw new AssertionError("URL query forbidden"); }
                public java.sql.DriverPropertyInfo[] getPropertyInfo(String u, java.util.Properties p) { throw new AssertionError("properties forbidden"); }
                public int getMajorVersion() { return 1; }
                public int getMinorVersion() { return 0; }
                public boolean jdbcCompliant() { return false; }
                public java.util.logging.Logger getParentLogger() { throw new AssertionError("logger query forbidden"); }
            }
            ${if (inherited) "class Parent { $method }" else ""}
        """.trimIndent()
    }

    private fun shared(): String {
        val declaration = when (mode) {
            PgTimerProbeCase.META_SHARED_ABSTRACT -> "public abstract class"
            PgTimerProbeCase.META_SHARED_PACKAGE_PRIVATE -> "class"
            else -> "public class"
        }
        val acquire = acquireMethod()
        val release = releaseMethod()
        val inheritAcquire = mode === PgTimerProbeCase.META_ACQUIRE_INHERITED
        val inheritRelease = mode === PgTimerProbeCase.META_RELEASE_INHERITED
        return """
            package org.postgresql.util;
            $declaration SharedTimer ${if (inheritAcquire || inheritRelease) "extends TimerParent" else ""} {
                static { System.setProperty("$PG_TIMER_INITIALIZED", "yes"); }
                public SharedTimer() {}
                ${if (inheritAcquire) "" else acquire}
                ${if (inheritRelease) "" else release}
            }
        """.trimIndent()
    }

    private fun timerParent(): String {
        val method = when (mode) {
            PgTimerProbeCase.META_ACQUIRE_INHERITED -> acquireMethod()
            PgTimerProbeCase.META_RELEASE_INHERITED -> releaseMethod()
            else -> ""
        }
        return """
            package org.postgresql.util;
            public class TimerParent { $method }
        """.trimIndent()
    }

    private fun acquireMethod(): String {
        val modifier = when (mode) {
            PgTimerProbeCase.META_ACQUIRE_PRIVATE -> "private"
            PgTimerProbeCase.META_ACQUIRE_STATIC -> "public static"
            else -> "public"
        }
        val returns = if (mode === PgTimerProbeCase.META_ACQUIRE_RETURN) "Object" else "java.util.Timer"
        val args = if (mode === PgTimerProbeCase.META_ACQUIRE_ARGUMENT) "int unused" else ""
        val body = when (mode) {
            PgTimerProbeCase.MODEL_ACQUIRE_FAILURE -> "throw new kira.timer.fixture.Counters.Hostile();"

            PgTimerProbeCase.MODEL_ACQUIRE_INTERRUPTED -> "throw new InterruptedException();"

            PgTimerProbeCase.MODEL_ACQUIRE_FATAL -> "throw kira.timer.fixture.Counters.FATAL;"

            PgTimerProbeCase.MODEL_NULL_TIMER -> "return null;"

            else -> """
                kira.timer.fixture.Counters.timer =
                    new java.util.Timer("synthetic-owned-timer", true) ${if (mode === PgTimerProbeCase.MODEL_SUBCLASS_TIMER) "{}" else ""};
                kira.timer.fixture.Counters.timer.schedule(new java.util.TimerTask() {
                    public void run() {
                        kira.timer.fixture.Counters.timerThread.set(Thread.currentThread());
                        kira.timer.fixture.Counters.timerObserved.countDown();
                    }
                }, 0L);
                if (!kira.timer.fixture.Counters.timerObserved.await(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new AssertionError("Synthetic timer witness missing");
                }
                return kira.timer.fixture.Counters.timer;
            """.trimIndent()
        }
        return """
            $modifier $returns getTimer($args) throws InterruptedException {
                kira.timer.fixture.Counters.acquire++;
                if (kira.timer.fixture.Counters.acquireHook != null) kira.timer.fixture.Counters.acquireHook.run();
                $body
            }
        """.trimIndent()
    }

    private fun releaseMethod(): String {
        val modifier = when (mode) {
            PgTimerProbeCase.META_RELEASE_PRIVATE -> "private"
            PgTimerProbeCase.META_RELEASE_STATIC -> "public static"
            else -> "public"
        }
        val returns = if (mode === PgTimerProbeCase.META_RELEASE_RETURN) "Object" else "void"
        val args = if (mode === PgTimerProbeCase.META_RELEASE_ARGUMENT) "int unused" else ""
        val result = when {
            mode === PgTimerProbeCase.MODEL_RELEASE_FAILURE -> "throw new kira.timer.fixture.Counters.Hostile();"
            returns == "Object" -> "return null;"
            else -> ""
        }
        return """
            $modifier $returns releaseTimer($args) {
                kira.timer.fixture.Counters.release++;
                if (kira.timer.fixture.Counters.releaseHook != null) kira.timer.fixture.Counters.releaseHook.run();
                if (kira.timer.fixture.Counters.timer != null) kira.timer.fixture.Counters.timer.cancel();
                $result
            }
        """.trimIndent()
    }

    private fun countersSource(): String = """
        package kira.timer.fixture;
        public class Counters {
            public static int constructors, utility, acquire, release, rendered;
            public static java.util.Timer timer;
            public static final java.util.concurrent.atomic.AtomicReference<Thread> timerThread = new java.util.concurrent.atomic.AtomicReference<>();
            public static final java.util.concurrent.CountDownLatch timerObserved = new java.util.concurrent.CountDownLatch(1);
            public static Runnable acquireHook, releaseHook;
            public static final Error FATAL = new LinkageError("synthetic-timer-fatal");
            public static class Hostile extends RuntimeException {
                public String getMessage() { rendered++; throw new AssertionError("message forbidden"); }
                public synchronized Throwable getCause() { rendered++; throw new AssertionError("cause forbidden"); }
                public String toString() { rendered++; throw new AssertionError("render forbidden"); }
            }
        }
    """.trimIndent()
}

internal const val PG_TIMER_INITIALIZED = "kira.synthetic.timer.initialized"
