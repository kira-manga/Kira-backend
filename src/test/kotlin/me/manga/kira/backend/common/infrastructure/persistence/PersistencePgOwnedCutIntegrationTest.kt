package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.lang.management.ManagementFactory
import java.lang.ref.Cleaner
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.Driver
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import java.util.concurrent.locks.LockSupport

/** Requires the qualified private runtime artifact. Missing owned-cut support fails; there is no stock/skip fallback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PersistencePgOwnedCutIntegrationTest {
    private val database = lazy { PgLifecycleDatabaseFixture().also { it.start() } }

    @TempDir
    lateinit var pendingProbeRoot: Path

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `required adapter resolves all22 exact descriptors and rejects missing changed or foreign helper`() {
        val prepared = PersistenceDriverBootstrap.prepare()
        val driver = prepared.construct()
        val loader = driver.javaClass.classLoader
        val access = PersistencePgOwnedCutAccess.prepare(prepared, driver.javaClass)
        val helper = Class.forName(OWNED_CUT_HELPER, false, loader)
        assertSame(loader, helper.classLoader)
        val marker = Class.forName("$OWNED_CUT_HELPER\$1", false, loader)
        assertMarkerShape(loader, helper, marker)
        fun cell(name: String): Class<*> = Class.forName("$OWNED_CUT_HELPER\$$name", false, loader).also {
            assertSame(loader, it.classLoader)
            assertSame(helper, it.declaringClass)
            assertTrue(Modifier.isPublic(it.modifiers) && Modifier.isFinal(it.modifiers) && Modifier.isStatic(it.modifiers))
        }
        val opening = cell("Opening")
        val root = cell("Root")
        val owner = cell("Owner")
        val life = cell("Life")
        val invocation = cell("Invocation")
        val objects = Array<Any?>::class.java
        val lives = java.lang.reflect.Array.newInstance(life, 0).javaClass
        val nativeConnection = Class.forName("$OWNED_CUT_HELPER\$NativeConnection", false, loader)
        val native = Class.forName("$OWNED_CUT_HELPER\$Native", false, loader)
        // Recorded native02 descriptors and exact constructor flags, independent of the adapter's shape predicate.
        val constructorArguments = mapOf(
            opening to listOf(Driver::class.java, Any::class.java),
            root to listOf(nativeConnection, Any::class.java, Any::class.java),
            owner to listOf(root),
            life to listOf(nativeConnection, life, Integer.TYPE, native),
            invocation to listOf(owner, Any::class.java, Any::class.java, Method::class.java, objects, objects, lives, IntArray::class.java),
        )
        constructorArguments.forEach { (type, arguments) ->
            assertEquals(2, type.declaredConstructors.size)
            val explicit = type.getDeclaredConstructor(*arguments.toTypedArray())
            assertEquals(Modifier.PRIVATE, explicit.modifiers)
            assertFalse(explicit.isSynthetic)
            val bridge = type.getDeclaredConstructor(*(arguments + marker).toTypedArray())
            assertEquals(0x1000, bridge.modifiers)
            assertTrue(bridge.isSynthetic)
            arguments.forEachIndexed { index, argument ->
                assertSame(argument, explicit.parameterTypes[index])
                assertSame(argument, bridge.parameterTypes[index])
            }
            assertSame(marker, bridge.parameterTypes.last())
        }
        val descriptors = mapOf(
            "prepareRuntime" to (Void.TYPE to emptyList()),
            "prepareOpening" to (opening to listOf(Driver::class.java, Any::class.java)),
            "armOpening" to (Void.TYPE to listOf(opening)),
            "recordReturned" to (Void.TYPE to listOf(opening, Connection::class.java)),
            "endOpening" to (Void.TYPE to listOf(opening)),
            "openingState" to (Integer.TYPE to listOf(opening)),
            "cleanupOpening" to (Void.TYPE to listOf(opening)),
            "attach" to (root to listOf(opening, Connection::class.java, Any::class.java, Any::class.java)),
            "owner" to (owner to listOf(root)),
            "life" to (life to listOf(owner, Any::class.java, life, java.lang.Boolean.TYPE)),
            "isLive" to (java.lang.Boolean.TYPE to listOf(owner, life)),
            "revoke" to (java.lang.Boolean.TYPE to listOf(owner, life)),
            "lifeKind" to (Integer.TYPE to listOf(life)),
            "firstCloseState" to (Integer.TYPE to listOf(life)),
            "liveNativeChildren" to (java.lang.Long.TYPE to listOf(root)),
            "rootState" to (Integer.TYPE to listOf(root)),
            "retentionState" to (Integer.TYPE to listOf(root)),
            "prepareInvocation" to
                (invocation to listOf(owner, Any::class.java, Any::class.java, Method::class.java, objects, objects, lives, IntArray::class.java)),
            "arm" to (Void.TYPE to listOf(invocation)),
            "invocationState" to (Integer.TYPE to listOf(invocation)),
            "drainState" to (Integer.TYPE to listOf(invocation)),
            "disarm" to (Void.TYPE to listOf(invocation)),
            "actualEnd" to (Void.TYPE to listOf(invocation)),
        )
        val resolved = (ownedCutField(access, "methods") as Map<*, *>).values.map { it as Method }.associateBy { it.name }
        // Historical selector name stays stable; the successor requires all23 with no old-JAR fallback.
        assertEquals(23, resolved.size)
        assertEquals(descriptors.keys, resolved.keys)
        descriptors.forEach { (name, descriptor) ->
            val method = helper.getDeclaredMethod(name, *descriptor.second.toTypedArray())
            assertEquals(method, resolved.getValue(name))
            assertSame(descriptor.first, method.returnType)
            assertTrue(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers))
            assertFalse(method.isBridge || method.isSynthetic || Modifier.isAbstract(method.modifiers))
        }
        val verifyConsumerInputs = assertFinalJarConsumerProvenance(
            "required",
            driver.javaClass,
            listOf(helper, marker, opening, root, owner, life, invocation),
        )
        CutDescriptorDefect.entries.forEach { defect ->
            // Only negative cold linkage: authentic artifact class bytes, never a synthetic successful Driver.
            val deniedLoader = CutDescriptorDefectLoader(loader, defect)
            val denied = PersistenceDriverBootstrap.prepareWithLoader(deniedLoader)
            val driverType = Class.forName("org.postgresql.Driver", false, deniedLoader)
            assertSame(deniedLoader, driverType.classLoader)
            assertTrue(denied.ownsTimerDriverClass(driverType))
            deniedLoader.assertConstructorDefect()
            assertEndpointFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER) {
                PersistencePgOwnedCutAccess.prepare(denied, driverType)
            }
        }
        verifyConsumerInputs()
    }

    private fun assertMarkerShape(loader: ClassLoader?, helper: Class<*>, marker: Class<*>) {
        assertSame(loader, marker.classLoader)
        assertSame(helper, marker.enclosingClass)
        assertNull(marker.declaringClass)
        assertNull(marker.enclosingMethod)
        assertNull(marker.enclosingConstructor)
        assertTrue(marker.isAnonymousClass && marker.isSynthetic)
        assertEquals(0, marker.modifiers and (Modifier.PUBLIC or Modifier.PROTECTED or Modifier.PRIVATE))
        assertEquals(0, marker.declaredFields.size)
        assertEquals(0, marker.declaredMethods.size)
        assertEquals(0, marker.declaredConstructors.size)
    }

    /** Actual positive Classes under the explicit development profile, not hashes of Class.getResource bytes. */
    private fun assertFinalJarConsumerProvenance(witnessName: String, driverType: Class<*>, cutTypes: List<Class<*>>): () -> Unit {
        assertTrue(witnessName == "required" || witnessName == "original")
        fun input(name: String): String = requireNotNull(System.getProperty("kira.finalJarConsumer.$name")) {
            "The required consumer test needs its explicit final-JAR profile: $name"
        }
        assertEquals("app-29-final-jar-consumer-profile-04", input("profile"))
        val jar = Path.of(input("jar")).toRealPath()
        val checker = Path.of(input("checker")).toRealPath()
        fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
        val verifyInputs = {
            assertEquals("50f7dc4a6314cc5105f4990be26ba8be65ae242e1f7e62652cc60d6a2c0e03df", sha256(jar))
            assertEquals("857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288", sha256(checker))
        }
        verifyInputs()
        val loader = requireNotNull(driverType.classLoader)
        assertSame(javaClass.classLoader, loader)
        assertSame(Thread.currentThread().contextClassLoader, loader)
        assertEquals("org.postgresql.Driver", driverType.name)
        val witness = linkedMapOf("profile" to input("profile"), "witness" to witnessName)
        val loaderIdentity = "${loader.javaClass.name}@${Integer.toHexString(System.identityHashCode(loader))}"
        fun associate(type: Class<*>, expected: Path) {
            assertSame(loader, type.classLoader)
            val source = requireNotNull(type.protectionDomain.codeSource).location.toURI()
            assertEquals("file", source.scheme)
            val actual = Path.of(source).toRealPath()
            assertEquals(expected, actual)
            witness["class.${type.name}.source"] = actual.toString()
            witness["class.${type.name}.loader"] = loaderIdentity
        }
        fun literal(name: String): Class<*> = Class.forName(name, false, loader)
        (listOf(driverType) + cutTypes).forEach { associate(it, jar) }
        val info = literal("org.postgresql.util.DriverInfo")
        val cleaner = literal("org.postgresql.util.LazyCleanerImpl")
        val wrapper = literal("org.postgresql.util.LazyCleanerImpl\$CleanableWrapper")
        val scram = literal("org.postgresql.shaded.com.ongres.scram.client.ScramClient")
        listOf(info, cleaner, wrapper, scram).forEach { associate(it, jar) }
        associate(literal("org.checkerframework.checker.nullness.qual.NonNull"), checker)
        assertEquals("42.7.12-kira.1", driverType.`package`.implementationVersion)
        assertEquals("42.7.12-kira.1", info.getField("DRIVER_VERSION").get(null))
        val cleanerField = cleaner.getDeclaredField("cleaner")
        assertEquals(Modifier.PRIVATE or Modifier.FINAL, cleanerField.modifiers)
        assertSame(Cleaner::class.java, cleanerField.type)
        assertSame(Cleaner.Cleanable::class.java, wrapper.getDeclaredField("nativeCleanable").type)
        witness["mr.cleaner.field"] = cleanerField.type.name
        witness["mr.wrapper.field"] = wrapper.getDeclaredField("nativeCleanable").type.name
        val registered = java.util.Collections.list(DriverManager.getDrivers())
        // acceptsURL parses only; Gate A never calls connect or initializes the lazy database fixture.
        val providers = registered.filter { it.acceptsURL("jdbc:postgresql://127.0.0.1/gate_a_no_connection") }
        assertEquals(1, providers.size)
        assertSame(driverType, providers.single().javaClass)
        registered.forEachIndexed { index, provider -> witness["registered.$index"] = provider.javaClass.name }
        witness["postgres.provider.count"] = providers.size.toString()
        witness["jar.sha256"] = sha256(jar)
        witness["checker.sha256"] = sha256(checker)
        val report = Path.of(input("report")).resolveSibling("worker-runtime-$witnessName.txt")
        recordFinalJarConsumerJvm(witness, Path.of(input("javaHome")), report)
        witness["provenance"] = "PASS"
        assertTrue(witness.values.none { '\n' in it || '\r' in it })
        Files.writeString(
            report,
            witness.entries.joinToString("\n", postfix = "\n") { "${it.key}=${it.value}" },
            StandardOpenOption.CREATE_NEW,
        )
        return verifyInputs
    }

    private fun recordFinalJarConsumerJvm(witness: MutableMap<String, String>, expectedHome: Path, report: Path) {
        val home = Path.of(System.getProperty("java.home")).toRealPath()
        assertEquals(expectedHome.toRealPath(), home)
        assertEquals(21, Runtime.version().feature())
        val process = ProcessHandle.current()
        val info = process.info()
        val executable = Path.of(info.command().orElseThrow()).toRealPath()
        assertEquals(home.resolve("bin/java").toRealPath(), executable)
        witness["worker.pid"] = process.pid().toString()
        witness["worker.executable"] = executable.toString()
        witness["java.home"] = home.toString()
        witness["java.runtime.version"] = Runtime.version().toString()
        witness["java.vm.name"] = System.getProperty("java.vm.name")
        witness["java.vm.version"] = System.getProperty("java.vm.version")
        val arguments = ManagementFactory.getRuntimeMXBean().inputArguments
        val forbidden = listOf(
            "-javaagent:", "-agentlib:", "-agentpath:", "-Xbootclasspath", "--patch-module", "--upgrade-module-path", "--module-path",
            "-Djava.system.class.loader", "-Djava.class.path=", "-Djdk.util.jar.", "-XX:SharedArchiveFile=", "-XX:ArchiveClassesAtExit=",
        )
        assertFalse(arguments.any { argument -> forbidden.any { argument.startsWith(it) } })
        assertTrue(arguments.containsAll(listOf("-XX:+DisableAttachMechanism", "-XX:-EnableDynamicAgentLoading")))
        assertTrue(arguments.contains("-Xlog:class+load=info:file=${report.parent}/class-load-%p.txt:uptime,level,tags"))
        listOf("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").forEach { assertNull(System.getenv(it)) }
        arguments.forEachIndexed { index, argument -> witness["jvm.arg.$index"] = argument }
        info.arguments().orElseThrow().forEachIndexed { index, argument -> witness["process.arg.$index"] = argument }
    }

    @Test
    fun `original provider retains a real opening capsule without inventing tracked transport evidence`() {
        var verifyConsumerInputs: (() -> Unit)? = null
        var primaryFailure: Throwable? = null
        try {
            withOwnedCutConnection(database.value, originalProvider = true) { f ->
                assertEquals("app-29-gate-b-hosted-01", System.getProperty("kira.finalJarConsumer.gate"))
                val access = f.opening.access
                val driverType = ownedCutField(access, "driverType") as Class<*>
                val methods = (ownedCutField(access, "methods") as Map<*, *>).values.map { it as Method }
                val helper = methods.map { it.declaringClass }.distinct().single()
                val types = requireNotNull(ownedCutField(access, "types"))
                val opaque = listOf("opening", "root", "owner", "life", "invocation").map { ownedCutField(types, it) as Class<*> }
                assertSame(opaque.first(), f.opening.cell.javaClass)
                val marker = opaque.first().declaredConstructors.single { it.isSynthetic }.parameterTypes.last()
                assertEquals(OWNED_CUT_HELPER, helper.name)
                assertEquals("org.postgresql.jdbc.PgConnection", f.raw.javaClass.name)
                verifyConsumerInputs = assertFinalJarConsumerProvenance("original", driverType, listOf(helper, marker, f.raw.javaClass) + opaque)
                assertSame(PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, f.entry.policy)
                assertNull(requireNotNull(f.entry.driverOpening).image)
                assertNull(f.entry.driverScope)
                assertNull(f.entry.transports)
                assertTrue(f.entry.driverCut.enabled)
                assertSame(f.raw, ownedCutField(f.opening.cell, "returned"))
                assertEquals(PersistencePgOwnedCutAccess.OPENING_ENDED, f.opening.access.openingState(f.opening))
                assertNull((ownedCutField(f.entry.driverCut, "root") as AtomicReference<*>).get())
                f.connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT 1").use { result ->
                        assertTrue(result.next())
                        assertEquals(1, result.getInt(1))
                    }
                }
                f.retire()
                assertEquals(PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED, f.work.disposition())
                assertNull(f.work.acknowledgedBoundary())
            }
        } catch (failure: Throwable) {
            primaryFailure = failure
            throw failure
        } finally {
            // After BOTH outer use blocks (connection retirement and scope close), even when either fails.
            try {
                verifyConsumerInputs?.invoke()
            } catch (recheckFailure: Throwable) {
                val failure = primaryFailure
                if (failure == null) throw recheckFailure
                if (failure !== recheckFailure) failure.addSuppressed(recheckFailure)
            }
        }
    }

    @Test
    fun `lost core returned receipt skips speculative capsule cleanup while real terminal close ends unknown`() = withOwnedCutConnection(database.value) { f ->
        assertTrue(f.connection.isValid(1))
        val receipt = ownedCutField(f.entry.driverCut, "returnedRecorded") as AtomicBoolean
        assertTrue(receipt.get())
        assertSame(f.raw, ownedCutField(f.opening.cell, "returned"))
        // Deliberately withhold ONLY the core receipt. This is not the native connect/correspondence fault window.
        receipt.set(false)
        f.scope.expectedUnknown = true
        f.connection.close()
        f.awaitUnknown()
        assertFalse((ownedCutField(f.opening.cell, "cleanupClaim") as AtomicBoolean).get())
        assertFalse(ownedCutField(f.opening.cell, "cleanupEnded") as Boolean)
        assertEquals(PersistenceTerminalCall.RETURNED, f.work.closeState())
        assertTrue(f.raw.isClosed)
        assertTrue(f.entry.driverCut.hasCleanupFailure())
        assertSame(f.entry, f.scope.entries().single())
    }

    @Test
    fun `metadata type cache hidden statement receives its own first close after real producer drain`() = withOwnedCutConnection(database.value) { f ->
        f.connection.metaData.typeInfo.use { result -> assertTrue(result.next()) }
        val cache = requireNotNull(ownedCutField(f.raw, "typeCache"))
        val hidden = requireNotNull(ownedCutField(cache, "getAllTypeInfoStatement")) as Statement
        val state = requireNotNull(ownedCutField(hidden, "kira"))
        val life = requireNotNull(ownedCutField(state, "life"))
        assertTrue(ownedCutField(state, "factoryReturned") as Boolean)
        assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(life, "first"))
        assertTrue(f.root.access.liveNativeChildren(f.root) > 0L)
        f.retire()
        assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(life, "first"))
        assertFalse(ownedCutField(state, "counted") as Boolean)
        assertEquals(0L, f.root.access.liveNativeChildren(f.root))
        assertEquals(0, f.root.access.rootState(f.root))
    }

    @Test
    fun `prepared describe temporary result is not certified by parent close and is genuinely closed at terminal`() =
        withOwnedCutConnection(database.value) { f ->
            val statement = f.connection.prepareStatement("SELECT 1")
            val statementLife = requireNotNull(PhysicalJdbcDescendants.knownGuard(statement)?.driverLife)
            assertEquals(1, statement.metaData.columnCount)
            val hidden = ownedCutNativeChildren(f.raw).single { child ->
                ownedCutField(child, "receiver") is ResultSet &&
                    ownedCutField(requireNotNull(ownedCutField(child, "life")), "parent") === statementLife.cell
            }
            val life = requireNotNull(ownedCutField(hidden, "life"))
            assertTrue(ownedCutField(hidden, "factoryReturned") as Boolean)
            statement.close()
            assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, statementLife.access.firstCloseState(statementLife))
            assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(life, "first"))
            f.retire()
            assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(life, "first"))
            assertFalse(ownedCutField(hidden, "counted") as Boolean)
            assertEquals(0L, f.root.access.liveNativeChildren(f.root))
            assertEquals(0, f.root.access.rootState(f.root))
        }

    @Test
    fun `real pooled TypeInfo cache and unexposed describe shell survive fresh roots and receive own terminal first closes`() {
        var retainedCache: Any? = null
        var retainedShell: Any? = null
        withOwnedCutPool(database.value) { f ->
            val first = f.pool.connection
            val lease = ownedPoolLease(first)
            val root = ownedPoolRoot(lease)
            val entry = f.entry(first)
            val raw = requireNotNull(entry.raw.get())
            val pid = ownedPoolScalar(first, "SELECT pg_backend_pid()")
            try {
                first.metaData.typeInfo.use { result ->
                    val life = ownedPoolLife(result)
                    assertTrue(result.next())
                    result.close()
                    assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, life.access.firstCloseState(life))
                }
                val cache = requireNotNull(ownedCutField(raw, "typeCache"))
                val hidden = requireNotNull(ownedCutField(cache, "getAllTypeInfoStatement"))
                retainedCache = ownedCutField(requireNotNull(ownedCutField(hidden, "kira")), "life")
                first.prepareStatement("SELECT 1").use { statement ->
                    val life = ownedPoolLife(statement)
                    assertEquals(1, statement.metaData.columnCount)
                    retainedShell = ownedCutNativeChildren(raw).single { child ->
                        ownedCutField(child, "receiver") is ResultSet &&
                            ownedCutField(requireNotNull(ownedCutField(child, "life")), "parent") === life.cell
                    }.let { ownedCutField(it, "life") }
                }
                assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(requireNotNull(retainedCache), "first"))
                assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(requireNotNull(retainedShell), "first"))
                assertTrue(root.access.liveNativeChildren(root) >= 2L)
                first.close()
                assertTrue(lease.state.epoch.sealedAndEnded())
                assertFalse(entry.retirementRequested.get())
                f.pool.connection.use { second ->
                    assertSame(entry, f.entry(second))
                    assertNotSame(root.cell, ownedPoolRoot(ownedPoolLease(second)).cell)
                    assertEquals(pid, ownedPoolScalar(second, "SELECT pg_backend_pid()"))
                    second.metaData.typeInfo.use { assertTrue(it.next()) }
                }
                assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(requireNotNull(retainedCache), "first"))
                assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, ownedCutField(requireNotNull(retainedShell), "first"))
            } finally {
                first.close()
            }
        }
        assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(requireNotNull(retainedCache), "first"))
        assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(requireNotNull(retainedShell), "first"))
    }

    @Test
    fun `real Array result hidden plain parent denies reuse and retires without losing its own native custody`() = withOwnedCutPool(database.value) { f ->
        val first = f.pool.connection
        val entry = f.entry(first)
        val pid = ownedPoolScalar(first, "SELECT pg_backend_pid()")
        val array = first.createArrayOf("int4", arrayOf(1))
        try {
            array.resultSet.use { result -> assertTrue(result.next()) }
            array.free()
            assertThrows<SQLException> { first.close() }
            assertTrue(entry.retirementRequested.get())
            awaitLifecycleFact { f.scope.entries().none { it === entry } }
            assertTrue(entry.driverCut.canReclaim(), "Conservative refusal is not lost terminal custody.")
            f.pool.connection.use { replacement -> assertTrue(pid != ownedPoolScalar(replacement, "SELECT pg_backend_pid()")) }
        } finally {
            array.free()
            first.close()
        }
    }

    @Test
    fun `real native first close remains in progress through its actual lock and duplicate lease close cannot consume the return`() =
        withOwnedCutPool(database.value) { f ->
            val connection = f.pool.connection
            val lease = ownedPoolLease(connection)
            val entry = f.entry(connection)
            val statement = connection.createStatement()
            val life = ownedPoolLife(statement)
            val native = ownedCutNative(ownedPoolLower(statement))
            val lock = ownedCutField(native, "lock") as ReentrantLock
            val held = CountDownLatch(1)
            try {
                FactoryWorkerTestScope().use { workers ->
                    val blocker = workers.launch {
                        lock.lock()
                        try {
                            held.countDown()
                            awaitLifecycleFact { life.access.firstCloseState(life) == PersistencePgOwnedCutAccess.FIRST_IN_PROGRESS }
                            assertTrue(lease.state.epoch.foregroundActive())
                            assertTrue(entry.jdbc.currentPoolState(lease.state))
                            assertFalse((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
                            assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
                            connection.close() // Already RETURNING: facade-only even from a foreign thread.
                            assertThrows<SQLException> { statement.cancel() }
                            assertEquals(PersistencePgOwnedCutAccess.FIRST_IN_PROGRESS, life.access.firstCloseState(life))
                        } finally {
                            lock.unlock()
                        }
                        true
                    }
                    assertTrue(held.await(5, TimeUnit.SECONDS))
                    connection.close() // Genuine Hikari closeStatements invokes this native first-close winner.
                    assertTrue(blocker.join())
                }
                assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, life.access.firstCloseState(life))
                assertTrue(lease.state.epoch.sealedAndEnded())
                assertFalse(entry.retirementRequested.get())
                f.pool.connection.use { assertSame(entry, f.entry(it)) }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `injected missing refcursor makes real first close fail and duplicate success cannot heal it`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entry = f.entry(connection)
        connection.autoCommit = false
        val statement = connection.createStatement()
        val cursor = statement.executeQuery("SELECT 1")
        val life = ownedPoolLife(cursor)
        try {
            val native = ownedCutNative(ownedPoolLower(cursor))
            val refCursor = native.javaClass.getDeclaredField("refCursorName").apply { check(trySetAccessible()) }
            assertNull(refCursor.get(native))
            // An exact data-field fault, not a fabricated FirstClose receipt or replacement driver.
            // Current pgjdbc eagerly closes public refcursors; do not mislabel this as that route.
            refCursor.set(native, "kira_pool_missing_cursor")
            f.scope.expectedUnknown = true
            val failed = assertThrows<SQLException> { cursor.close() }
            assertEquals("34000", failed.sqlState)
            assertNull(refCursor.get(native), "The genuine native finally clears the injected cursor name.")
            assertEquals(PersistencePgOwnedCutAccess.FIRST_FAILED, life.access.firstCloseState(life))
            cursor.close()
            assertEquals(PersistencePgOwnedCutAccess.FIRST_FAILED, life.access.firstCloseState(life))
            assertTrue(lease.state.epoch.poisoned())
            assertThrows<SQLException> { connection.close() }
            awaitLifecycleFact { requireNotNull(entry.terminalWork).bodyExited() }
            assertFalse(entry.driverCut.canReclaim())
            assertTrue(entry.driverCut.hasCleanupFailure())
            assertSame(entry, f.scope.entries().single())
        } finally {
            cursor.close()
            connection.close()
        }
    }

    @Test
    fun `real native finalizers end before the held core last count and a racing terminal seal still denies return`() =
        withOwnedCutPool(database.value) { f ->
            val connection = f.pool.connection
            val lease = ownedPoolLease(connection)
            val entry = f.entry(connection)
            val root = ownedPoolRoot(lease)
            try {
                CoreLastCountBarrier(lease).use { barrier ->
                    FactoryWorkerTestScope().use { workers ->
                        val observer = workers.launch {
                            barrier.awaitEntered()
                            try {
                                assertTrue(barrier.nativeEnded(), "This seam is AFTER native actualEnd, not a native-finalizer suspension claim.")
                                assertTrue(lease.state.epoch.foregroundActive())
                                assertFalse(lease.state.epoch.sealedAndEnded())
                                assertTrue(entry.jdbc.currentPoolState(lease.state))
                                assertSame(root.cell, ownedPoolRoot(lease).cell)
                                assertFalse((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
                                requireNotNull(f.pool.requestShutdown())
                                awaitLifecycleFact { !entry.jdbc.permitsCleanup(lease.state.epoch) }
                                assertFalse(entry.jdbc.postOpeningCallsEnded())
                                assertFalse(requireNotNull(entry.terminalWork).producerDrainProven())
                                connection.close() // No second real return, despite the held first one.
                            } finally {
                                barrier.release()
                            }
                            true
                        }
                        assertThrows<SQLException> { connection.close() }
                        assertTrue(observer.join())
                    }
                    assertFalse(barrier.timedOut.get())
                }
                awaitLifecycleFact { f.scope.entries().none { it === entry } }
                assertFalse((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
            } finally {
                connection.close()
            }
        }

    @Test
    fun `consented real Hikari recycle tail retains no successor eviction or abort authority`() = withOwnedCutPool(database.value) { f ->
        assertConsentedOldTail(f, failTail = false)
    }

    @Test
    fun `failed post consent real Hikari tail seals actor admission but cannot retire the successor epoch`() = withOwnedCutPool(database.value) { f ->
        f.expectedPoolUnknown = true
        assertConsentedOldTail(f, failTail = true)
    }

    @Test
    fun `real post commit Blob work cannot silently commit through Hikari auto commit reset`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entry = f.entry(connection)
        val raw = requireNotNull(entry.raw.get())
        connection.autoCommit = false
        val oid = ownedPoolScalar(connection, "SELECT lo_create(0)")
        val blob = connection.createStatement().use { statement ->
            statement.executeQuery("SELECT $oid::oid").use { result ->
                assertTrue(result.next())
                result.getBlob(1)
            }
        }
        try {
            connection.commit()
            assertTrue(lease.state.context.transaction.clean())
            blob.setBytes(1, byteArrayOf(7)) // Native fastpath starts a NEW transaction; Hikari sees no Statement execute.
            blob.free()
            assertFalse(lease.state.context.transaction.clean())
            assertThrows<SQLException> { connection.close() }
            assertEquals(false, ownedCutField(raw, "autoCommit"), "Refusal must precede native setAutoCommit(true), not merely deny final consent.")
            awaitLifecycleFact { f.scope.entries().none { it === entry } }
            f.pool.connection.use { next ->
                try {
                    assertEquals(0, ownedPoolScalar(next, "SELECT octet_length(lo_get($oid))"), "The post-commit write must have rolled back on retirement.")
                } finally {
                    assertEquals(1, ownedPoolScalar(next, "SELECT lo_unlink($oid)"))
                }
            }
        } finally {
            blob.free()
            connection.close()
        }
    }

    @Test
    fun `real commit failure retains unknown outcome and refuses reset before native auto commit`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val entry = f.entry(connection)
        val raw = requireNotNull(entry.raw.get())
        try {
            connection.autoCommit = false
            connection.createStatement().use { statement ->
                statement.execute("CREATE TEMP TABLE kira_pool_commit (id int UNIQUE DEFERRABLE INITIALLY DEFERRED)")
                statement.execute("INSERT INTO kira_pool_commit VALUES (1), (1)")
            }
            assertEquals("23505", assertThrows<SQLException> { connection.commit() }.sqlState)
            assertTrue(lease.state.context.transaction.uncertain())
            assertThrows<SQLException> { connection.close() }
            assertEquals(false, ownedCutField(raw, "autoCommit"))
            assertTrue(entry.retirementRequested.get())
            awaitLifecycleFact { f.scope.entries().none { it === entry } }
        } finally {
            connection.close()
        }
    }

    @Test
    fun `real lease return expiry before actor entry revokes only its unused future right and retains physical retirement`() =
        withOwnedCutPool(database.value) { f ->
            val connection = f.pool.connection
            val lease = ownedPoolLease(connection)
            val entry = f.entry(connection)
            val gate = requireNotNull(ownedCutField(f.lifecycle, "gate"))
            val handle = ownedCutField(lease, "handle") as Connection
            val lower = requireNotNull(ownedCutField(handle, "delegate"))
            val original = Thread.currentThread()
            val threads = ManagementFactory.getThreadMXBean()
            val held = CountDownLatch(1)
            try {
                FactoryWorkerTestScope().use { workers ->
                    val blocker = workers.launch {
                        synchronized(gate) {
                            held.countDown()
                            awaitLifecycleFact {
                                threads.getThreadInfo(original.threadId())?.let { blocked ->
                                    blocked.threadState === Thread.State.BLOCKED && blocked.lockOwnerId == Thread.currentThread().threadId() &&
                                        blocked.lockInfo?.identityHashCode == System.identityHashCode(gate)
                                } == true
                            }
                            // Expire this actual close's fixed 1s allowance while it is blocked
                            // before actor admission, not a synthetic operation or refreshed clock.
                            val end = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1_100)
                            while (System.nanoTime() < end) LockSupport.parkNanos(minOf(end - System.nanoTime(), 1_000_000L))
                        }
                        true
                    }
                    assertTrue(held.await(5, TimeUnit.SECONDS))
                    assertThrows<SQLException> { connection.close() }
                    assertTrue(blocker.join())
                }
                assertTrue(lease.closed())
                assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
                assertNull(ownedCutField(lease, "transfer"), "Refusal precedes the return transfer/Hikari close extent.")
                assertSame(lower, ownedCutField(handle, "delegate"), "No Hikari close finally replaced its delegate with CLOSED_CONNECTION.")
                assertTrue(entry.retirementRequested.get())
                connection.close() // No fresh return budget/operation is issued by the duplicate.
                awaitLifecycleFact { f.scope.entries().none { it === entry } }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `ownership lock refusal precedes any real lease return issuance and does not orphan the future right`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        val lease = ownedPoolLease(connection)
        val lock = f.scope.binding().ledger.lock
        try {
            lock.lock()
            try {
                assertThrows<SQLException> { connection.close() }
                assertFalse(lease.closed())
            } finally {
                lock.unlock()
            }
            assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            connection.close()
            assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `blocked overriding original checkout sample retains its real holder with F G T free before successful delivery`() =
        withOwnedCutPool(database.value) { f -> assertOwnedCheckoutCaller(f, CheckoutCallerFault.NONE) }

    @Test
    fun `throwing overriding original checkout sample cannot orphan its captured handle or future return right`() =
        withOwnedCutPool(database.value) { f -> assertOwnedCheckoutCaller(f, CheckoutCallerFault.SAMPLE) }

    @Test
    fun `actual checkout interruption beats a false override and restores only after source refusal under retained acquisition`() =
        withOwnedCutPool(database.value) { f -> assertOwnedCheckoutCaller(f, CheckoutCallerFault.ACTUAL_FLAG) }

    @Test
    fun `throwing checkout interrupt restoration preserves refused source and authentic acquisition cleanup`() =
        withOwnedCutPool(database.value) { f -> assertOwnedCheckoutCaller(f, CheckoutCallerFault.RESTORE) }

    @Test
    fun `blocked overriding final RETURN sample spends the same real allowance and cannot commit after expiry`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.EXPIRE) }

    @Test
    fun `throwing original RETURN override leaves F G T free and retires its exact source without a second return`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.SAMPLE) }

    @Test
    fun `actual RETURN interruption defeats a false override while outer actor custody survives held restoration`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.ACTUAL_FLAG) }

    @Test
    fun `throwing RETURN interrupt restoration cannot undo retirement or erase its actor failure`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.RESTORE) }

    @Test
    fun `RETURN override InterruptedException publishes source failure before an overriding self interrupt can block`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.INTERRUPTED_EXCEPTION) }

    @Test
    fun `RETURN InterruptedException adaptation throwing another InterruptedException never retries restoration after actor end`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.INTERRUPTED_RESTORE_INTERRUPTED) }

    @Test
    fun `RETURN InterruptedException adaptation throwing Error preserves that error without a late restoration callback`() =
        withOwnedCutPool(database.value) { f -> assertOwnedReturnCaller(f, ReturnCallerFault.INTERRUPTED_RESTORE_ERROR) }

    @Test
    fun `genuine throwing RETURN TL entry restores the authentic lineage and revokes only its still unused future right`() =
        withOwnedCutPool(database.value) { f ->
            f.expectedPoolUnknown = true
            val connection = f.pool.connection
            val lease = ownedPoolLease(connection)
            val entry = f.entry(connection)
            val handle = ownedCutField(lease, "handle") as Connection
            val lower = requireNotNull(ownedCutField(handle, "delegate"))
            val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
            val problem = IllegalStateException("Injected authentic RETURN TL installation failure.")
            try {
                ThrowingReturnEntry(f.lifecycle, problem).use { fault ->
                    assertSame(problem, assertThrows<IllegalStateException> { connection.close() })
                    val operation = ownedCutField(entitlement, "prepared") as PoolLifecycle.Operation
                    assertSame(operation.frame, fault.selected)
                    assertEquals("REFUSED", requireNotNull(ownedCutField(operation.frame, "phase")).toString())
                    assertFalse(operation.frame.hasEntered(), "The real installation threw before counted admission.")
                    assertFalse(operation.actualFrameEnded(), "REFUSED is not a manufactured ENDED receipt.")
                    assertFalse(operation.frame.completion.hasEnded())
                    assertNull(PoolCallFrames.current(), "restoreUnadmitted must remove the actually published frame.")
                    assertEquals(1, fault.injections)
                    assertTrue(fault.outsideOwnershipLocks)
                    assertEquals("REVOKED", requireNotNull(ownedCutField(entitlement, "phase")).toString())
                    assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
                    assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
                    assertEquals(PoolActorFault.BOOKKEEPING_FAILED, f.lifecycle.actorSnapshot().firstFailure)
                    assertNull(ownedCutField(lease, "transfer"))
                    assertSame(lower, ownedCutField(handle, "delegate"), "No Hikari close or reset was reached.")
                    assertTrue(lease.closed() && entry.retirementRequested.get())
                    assertTrue(lease.state.epoch.sealedAndEnded())
                    connection.close()
                    assertSame(operation, ownedCutField(entitlement, "prepared"), "Duplicate close issues no new allowance or operation.")
                    assertEquals(1, fault.injections)
                }
                awaitLifecycleFact { f.scope.entries().none { it === entry } }
            } finally {
                connection.close()
            }
        }

    @Test
    fun `genuine checkout acquisition end TL failure keeps undelivered custody through witnessed process only exit`() =
        pendingPoolProbe(PgLifecycleDatabaseMode.POOL_ACQUISITION_END_TL_FAILURE)

    @Test
    fun `genuine RETURN core TL failure after native actual end keeps its last producer through witnessed process only exit`() =
        pendingPoolProbe(PgLifecycleDatabaseMode.POOL_CORE_LAST_COUNT_TL_FAILURE)

    private fun pendingPoolProbe(mode: PgLifecycleDatabaseMode) {
        val server = database.value
        check(server.host == "localhost" || server.host == "127.0.0.1") {
            "The existing sanitized child lane requires its documented local database port."
        }
        val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY, mode)
        check(case.poolPendingFailure && case.attempts == 1 && !case.succeeds)
        val directory = pgLifecycleDatabasePrivateDirectory(pendingProbeRoot.resolve(UUID.randomUUID().toString()))
        val child = PgLifecycleDatabaseProbeProcess(directory, case)
        child.use {
            child.start(server.port)
            child.awaitPendingPoolExit()
            // Exact exit23 and its negative witnesses must still FAIL the unchanged positive oracle.
            val failure = assertThrows<IllegalStateException> { child.awaitVerified() }
            assertEquals("Synthetic database child rejected its scenario.", failure.message)
        }
        val cleanup = requireNotNull(child.cleanupObservation)
        check(cleanup.contains("alive=false forced=false reader_joined=true streams_closed=true"))
        check(cleanup.contains("output_overflow=false output_read_failed=false output_eof=true reader_alive=false reader_state=TERMINATED"))
        println("PG_POOL_PENDING_PROCESS_OBSERVED ${case.label} nonce=${child.nonce} cleanup=PROCESS_ONLY product_end=false")
    }

    @Test
    fun `already admitted native stream read survives seal while foreign cancel preserves its original lineage`() =
        withOwnedCutConnection(database.value) { f ->
            val input = ownedCutInput(f.connection)
            val nativeInput = ownedCutNative(input)
            assertSame(ByteArrayInputStream::class.java, nativeInput.javaClass)
            assertEquals(1, input.read()) // Warm the real read route before placing its exact native monitor barrier.
            val statement = f.connection.createStatement()
            val statementLife = requireNotNull(PhysicalJdbcDescendants.knownGuard(statement)?.driverLife)
            val original = Thread.currentThread()
            val threads = ManagementFactory.getThreadMXBean()
            val monitorHeld = CountDownLatch(1)
            // An intentionally unclosed facade stream remains the exact UNKNOWN holder after seal.
            // Terminal native disposal must not forge that facade-only close receipt.
            f.scope.expectedUnknown = true
            FactoryWorkerTestScope().use { workers ->
                val cancellation = workers.launch {
                    synchronized(nativeInput) {
                        monitorHeld.countDown()
                        awaitLifecycleFact {
                            val blocked = threads.getThreadInfo(original.threadId())
                            blocked?.threadState === Thread.State.BLOCKED && blocked.lockOwnerId == Thread.currentThread().threadId() &&
                                blocked.lockInfo?.identityHashCode == System.identityHashCode(nativeInput)
                        }
                        assertTrue(f.epoch.foregroundActive())
                        assertThrows<SQLException> { statement.execute("SELECT 1") }
                        statement.cancel() // Real idle Statement.cancel; not a claim of a PostgreSQL wire-cancel event.
                        assertEquals(0L, f.epoch.activeCancellations())
                        assertTrue(f.epoch.foregroundActive())
                        f.connection.close()
                        awaitLifecycleFact { !f.entry.jdbc.permitsCleanup(f.epoch) }
                        assertFalse(f.entry.jdbc.postOpeningCallsEnded())
                        assertFalse(f.work.producerDrainProven())
                        assertFalse((ownedCutField(f.opening.cell, "cleanupClaim") as AtomicBoolean).get())
                        assertEquals(PersistencePgOwnedCutAccess.FIRST_NEVER, statementLife.access.firstCloseState(statementLife))
                    }
                    true
                }
                assertTrue(monitorHeld.await(5, TimeUnit.SECONDS))
                assertEquals(2, input.read()) // The admitted native body, not a new admission after seal.
                assertTrue(cancellation.join())
            }
            assertFalse(f.epoch.foregroundActive())
            assertFalse(f.epoch.poisoned())
            assertThrows<IOException> { input.read() }
            assertThrows<IOException> { input.close() }
            f.awaitUnknown()
            assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, statementLife.access.firstCloseState(statementLife))
            assertEquals(0L, f.root.access.liveNativeChildren(f.root))
            assertEquals(0, f.root.access.rootState(f.root))
            assertNull(ownedCutField(requireNotNull(ownedCutField(f.raw, "kira")), "current"))
            assertEquals(1L, (ownedCutField(f.entry.driverCut, "facadeChildren") as AtomicLong).get())
            assertFalse(f.entry.driverCut.hasCleanupFailure())
        }
}

/**
 * Two check-only child cases in the existing sanitized negative-probe process. They deliberately
 * do NOT use the successful OwnedCutPool/PgLifecycleTestScope close contract: neither retained
 * failure can truthfully finish it. Only the existing parent process owner may observe exit/EOF.
 */
internal object OwnedPoolPendingProbe {
    @Suppress("TooGenericExceptionCaught")
    fun verify(case: PgLifecycleDatabaseCase, port: Int, nonce: String, handshake: PgLifecycleDatabaseHandshake) {
        check(case.poolPendingFailure && case.lane === PgLifecycleDatabaseLane.ORDINARY && case.attempts == 1)
        val endpoint = PgLifecycleDatabaseSettings.endpoint(case, port, "w03c_$nonce")
        val scope = PgLifecycleTestScope(endpoint, capacity = 2)
        val pool = GuardedDataSource(scope.owner, endpoint, 1, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
        val lifecycle = ownedCutField(pool, "lifecycle") as PoolLifecycle
        check(pool.start() === PersistenceLifecycleActivation.STARTED)
        awaitLifecycleFact { scope.owner.snapshot().ordinaryReady && scope.owner.snapshot().timerReady }
        check(pool.observePreparation() === PersistenceLifecycleObservation.READY)
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
        val entry = pool.connection.use { warm ->
            val lease = checkedLease(warm)
            scope.entries().single { it.jdbc.currentPoolState(lease.state) }
        }
        check(lifecycle.activeAcquisitions() == 0L && lifecycle.actorSnapshot().futureLeaseEntries == 0L)

        val result = AtomicReference<PendingEvidence?>()
        val failure = AtomicReference<Throwable?>()
        // Retain this exact unstarted borrower before any activation. This is not a waiter/pool actor.
        val caller = Thread.ofPlatform().daemon(true).inheritInheritableThreadLocals(false).name("w03-pool-pending-borrower").unstarted {
            try {
                result.set(
                    when (case.mode) {
                        PgLifecycleDatabaseMode.POOL_ACQUISITION_END_TL_FAILURE -> acquisitionEnd(pool, lifecycle, entry)
                        PgLifecycleDatabaseMode.POOL_CORE_LAST_COUNT_TL_FAILURE -> coreLastCount(scope, pool, lifecycle, entry)
                        else -> error("Not a closed pending-pool child case.")
                    },
                )
            } catch (problem: Throwable) {
                failure.set(problem)
            }
        }
        caller.start()
        val joined = PgLifecycleDatabaseDeadline(15_000)
        while (caller.isAlive) caller.join(joined.millis(100))
        check(caller.state === Thread.State.TERMINATED && !caller.isAlive)
        failure.get()?.let { throw it }
        val evidence = requireNotNull(result.get())
        check(evidence.caller === caller)
        requireNotNull(pool.requestShutdown())
        if (case.mode === PgLifecycleDatabaseMode.POOL_CORE_LAST_COUNT_TL_FAILURE) {
            // Unlike failed acquisition.end, this real outer actor ended and permits the one
            // actual Hikari close. Its return cannot erase the retained core foreground token.
            check(pool.shutdownInvocation() === PoolShutdownInvocation.RETURNED)
            awaitLifecycleFact {
                val abort = (ownedCutField(requireNotNull(entry.terminalWork), "abort") as AtomicReference<*>).get()
                abort === PersistenceTerminalCall.RETURNED || abort === PersistenceTerminalCall.THREW
            }
        }
        evidence.requireRetained()
        val cut = when (case.mode) {
            PgLifecycleDatabaseMode.POOL_ACQUISITION_END_TL_FAILURE ->
                "cut=ACQUISITION_END_TL acquisition=ENDING active_acquisitions=1 delivered=false future_entries=0 pool_close_claimed=false"
            PgLifecycleDatabaseMode.POOL_CORE_LAST_COUNT_TL_FAILURE ->
                "cut=RETURN_CORE_LAST_COUNT_TL native_actual_end=true core_producer_ended=false future_entries=0 transfer_consented=false"
            else -> error("Not a closed pending-pool child case.")
        }
        println("PG_POOL_PENDING_RETAINED ${case.label} nonce=$nonce $cut caller_terminated=true product_end=false")
        System.out.flush()
        handshake.publish(PgLifecycleDatabasePhase.RETAINED)
        handshake.await(PgLifecycleDatabasePhase.EXIT)
        evidence.requireRetained() // Actual terminated caller plus the SAME unhealed product state, not a receipt replay.
    }

    private fun acquisitionEnd(pool: GuardedDataSource, lifecycle: PoolLifecycle, entry: PersistencePhysicalEntry): PendingEvidence {
        val problem = IllegalStateException("Injected actual acquisition end TL removal failure.")
        val fault = AcquisitionEndFailure(lifecycle, entry, problem)
        var delivered = false
        fault.use {
            val outcome = runCatching { pool.connection.also { delivered = true } }
            check(!delivered && outcome.exceptionOrNull() === problem)
            check(PoolCallFrames.current() === fault.selected)
            check(PersistenceJdbcDispatch.current() == null)
        }
        check(!delivered)
        return AcquisitionEvidence(Thread.currentThread(), pool, lifecycle, entry, fault)
    }

    private fun coreLastCount(
        scope: PgLifecycleTestScope,
        pool: GuardedDataSource,
        lifecycle: PoolLifecycle,
        entry: PersistencePhysicalEntry,
    ): PendingEvidence {
        val connection = pool.connection
        val lease = checkedLease(connection)
        check(entry.jdbc.currentPoolState(lease.state) && scope.entries().single() === entry)
        val problem = IllegalStateException("Injected actual RETURN core TL removal failure.")
        val fault = CoreLastCountFailure(lease, entry, problem)
        fault.use {
            val outcome = runCatching { connection.close() }
            check(outcome.exceptionOrNull() is SQLException && fault.injections == 1)
            check(PoolCallFrames.current() == null && PersistenceJdbcDispatch.current() == null)
            check(lease.state.context.hasCurrentFrame()) // This failed core TL, not the independent outer actor TL.
        }
        val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
        val operation = ownedCutField(entitlement, "prepared") as PoolLifecycle.Operation
        connection.close() // Genuine duplicate is facade-only. Never retry the failed core finish or its TL removal.
        check(ownedCutField(entitlement, "prepared") === operation && fault.injections == 1)
        awaitLifecycleFact { !entry.jdbc.permitsCleanup(lease.state.epoch) }
        return CoreEvidence(Thread.currentThread(), scope, lifecycle, entry, lease, operation, fault)
    }

    private fun checkedLease(connection: Connection): PersistenceJdbcLease {
        check(connection is LeaseJdbcFacade)
        return ownedCutField(requireNotNull(ownedCutField(connection, "calls")), "lease") as PersistenceJdbcLease
    }

    private sealed interface PendingEvidence {
        val caller: Thread
        fun requireRetained()
    }

    private class AcquisitionEvidence(
        override val caller: Thread,
        private val pool: GuardedDataSource,
        private val lifecycle: PoolLifecycle,
        private val entry: PersistencePhysicalEntry,
        private val fault: AcquisitionEndFailure,
    ) : PendingEvidence {
        override fun requireRetained() {
            check(caller.state === Thread.State.TERMINATED && !caller.isAlive)
            fault.requireRestoredUnhealed()
            val frame = requireNotNull(fault.selected)
            val lease = requireNotNull(fault.lease)
            val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
            check(ownedCutField(frame, "caller") === caller && frame.hasEntered() && !frame.ended() && !frame.completion.hasEnded())
            check(requireNotNull(ownedCutField(frame, "phase")).toString() == "ENDING")
            check(lease.closed() && ownedCutField(lease.state, "lease") === lease)
            check(ownedCutField(lease, "handle") === fault.handle && ownedCutField(lease, "original") === caller)
            check(entry.jdbc.currentPoolState(lease.state) && entry.retirementRequested.get() && lease.state.epoch.sealedAndEnded())
            check(requireNotNull(ownedCutField(entitlement, "phase")).toString() == "REVOKED")
            check(ownedCutField(entitlement, "prepared") == null)
            check(ownedCutField(lease, "transfer") == null) // No borrower RETURN was ever delivered or prepared.
            val checkout = requireNotNull(fault.checkout)
            check(checkout.kind === PersistenceJdbcPoolTransfer.Kind.CHECKOUT && checkout.consented() && checkout.actualEnded())
            check(checkout.budget === frame.budget)
            val actors = lifecycle.actorSnapshot()
            check(lifecycle.activeAcquisitions() == 1L && actors.futureLeaseEntries == 0L && actors.activeOperations == 0L)
            check(actors.firstFailure === PoolActorFault.BOOKKEEPING_FAILED)
            check(pool.shutdownInvocation() === PoolShutdownInvocation.INITIALIZATION_PENDING)
            check((ownedCutField(lifecycle, "firstClose") as AtomicReference<*>).get() == null)
            check(requireNotNull(pool.requestShutdown()).observe() === PoolShutdownObservation.PENDING)
            // Native source retirement may separately complete; it does not end this acquisition or pool.
        }
    }

    private class CoreEvidence(
        override val caller: Thread,
        private val scope: PgLifecycleTestScope,
        private val lifecycle: PoolLifecycle,
        private val entry: PersistencePhysicalEntry,
        private val lease: PersistenceJdbcLease,
        private val operation: PoolLifecycle.Operation,
        private val fault: CoreLastCountFailure,
    ) : PendingEvidence {
        override fun requireRetained() {
            check(caller.state === Thread.State.TERMINATED && !caller.isAlive)
            fault.requireRestoredUnhealed()
            val call = requireNotNull(fault.selected)
            val token = ownedCutField(call, "token") as PersistenceProducerEpoch.Call
            check(ownedCutField(call, "actualCaller") === caller && ownedCutField(call, "ended") == false)
            check(token.outcome() == null && token.epoch === lease.state.epoch)
            val state = requireNotNull((ownedCutField(lease.state.epoch, "state") as AtomicReference<*>).get())
            check(ownedCutField(state, "foreground") === token && ownedCutField(state, "sealed") == true)
            check(lease.state.epoch.foregroundActive() && lease.state.epoch.poisoned() && !lease.state.epoch.sealedAndEnded())
            check(lease.state.context.graphFailed() && (ownedCutField(entry.driverCut, "unresolved") as AtomicReference<*>).get() === call)
            check(entry.retirementRequested.get() && entry.jdbc.currentPoolState(lease.state) && scope.entries().any { it === entry })
            check(!entry.jdbc.postOpeningCallsEnded())
            val transfer = ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer
            check(transfer.source === lease.state && transfer.actualEnded() && !transfer.consented())
            check(operation.frame.budget === transfer.budget && operation.actualFrameEnded() && operation.frame.completion.hasEnded())
            check(requireNotNull(ownedCutField(operation.entitlement, "phase")).toString() == "CONSUMED")
            check(ownedCutField(operation.entitlement, "prepared") === operation)
            val actors = lifecycle.actorSnapshot()
            check(lifecycle.activeAcquisitions() == 0L && actors.futureLeaseEntries == 0L && actors.activeOperations == 0L)
            val physicalClose = requireNotNull((ownedCutField(lifecycle, "firstClose") as AtomicReference<*>).get())
            check((ownedCutField(physicalClose, "ended") as AtomicBoolean).get())
            val work = requireNotNull(entry.terminalWork)
            val abort = (ownedCutField(work, "abort") as AtomicReference<*>).get()
            check(abort === PersistenceTerminalCall.RETURNED || abort === PersistenceTerminalCall.THREW)
            check(!work.producerDrainProven() && work.closeState() === PersistenceTerminalCall.NOT_INVOKED && !work.bodyExited())
            check(work.disposition() === PersistenceTerminalDisposition.PENDING)
            // The real native call ended before this core cut. Neither actor/pool close nor caller death supplies the missing count.
        }
    }

    /** Throw before the real delegate removal, AFTER actor claimEnd and the genuine checkout commit. */
    private class AcquisitionEndFailure(
        private val lifecycle: PoolLifecycle,
        private val entry: PersistencePhysicalEntry,
        private val problem: Throwable,
    ) : ThreadLocal<PoolCallFrame?>(), AutoCloseable {
        private val caller = Thread.currentThread()
        private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
        private val field = storage.javaClass.getDeclaredField("current").apply { check(trySetAccessible()) }
        @Suppress("UNCHECKED_CAST")
        private val delegate = field.get(storage) as ThreadLocal<PoolCallFrame?>
        var selected: PoolCallFrame? = null
            private set
        var lease: PersistenceJdbcLease? = null
            private set
        var handle: Connection? = null
            private set
        var checkout: PersistenceJdbcPoolTransfer? = null
            private set
        private var injections = 0
        private var restored = false
        private var unhealedOnCaller = false

        init {
            check(delegate.get() == null)
            field.set(storage, this)
        }

        override fun get(): PoolCallFrame? = delegate.get()
        override fun set(value: PoolCallFrame?) = delegate.set(value)

        override fun remove() {
            val frame = delegate.get()
            if (Thread.currentThread() === caller && frame?.kind === PoolCallKind.ACQUISITION) {
                check(++injections == 1 && ownedCutField(frame, "pool") === lifecycle && !entry.jdbc.ownershipLockHeld())
                selected = frame
                check(requireNotNull(ownedCutField(frame, "phase")).toString() == "ENDING")
                val state = (ownedCutField(entry.jdbc, "poolState") as AtomicReference<*>).get() as PersistenceJdbcPoolEpoch
                check(state.leased && entry.jdbc.currentPoolState(state))
                lease = ownedCutField(state, "lease") as PersistenceJdbcLease
                handle = ownedCutField(requireNotNull(lease), "handle") as Connection
                checkout = (ownedCutField(entry.jdbc, "poolTransfer") as AtomicReference<*>).get() as PersistenceJdbcPoolTransfer
                check(requireNotNull(checkout).consented() && requireNotNull(checkout).actualEnded())
                throw problem
            }
            delegate.remove()
        }

        override fun close() {
            check(field.get(storage) === this)
            field.set(storage, delegate) // Restore ONLY the instance field. Never remove/retry the retained caller frame.
            restored = true
            if (selected != null) {
                check(delegate.get() === selected)
                unhealedOnCaller = true
            }
        }

        fun requireRestoredUnhealed() {
            check(injections == 1 && restored && unhealedOnCaller && field.get(storage) === delegate)
        }
    }

    /** The same genuine final lower clearWarnings boundary as the held-count test, now a nonhealable removal failure. */
    private class CoreLastCountFailure(
        private val lease: PersistenceJdbcLease,
        private val entry: PersistencePhysicalEntry,
        private val problem: Throwable,
    ) : ThreadLocal<PersistenceJdbcGuardCall?>(), AutoCloseable {
        private val caller = Thread.currentThread()
        private val context = lease.state.context
        private val field = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }
        @Suppress("UNCHECKED_CAST")
        private val delegate = field.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
        private val driver = PersistenceJdbcGuardCall::class.java.getDeclaredField("driver").apply { check(trySetAccessible()) }
        var selected: PersistenceJdbcGuardCall? = null
            private set
        private var invocation: PersistencePgOwnedCutAccess.Invocation? = null
        var injections = 0
            private set
        private var nativeEndedAtRemoval = false
        private var restored = false
        private var unhealedOnCaller = false

        init {
            check(delegate.get() == null)
            field.set(context, this)
        }

        override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
            if (Thread.currentThread() === caller && call != null && PersistenceJdbcDispatch.current()?.returning() == true) {
                val native = driver.get(call) as? PersistencePgOwnedCutAccess.Invocation
                if (native != null && ownedCutField(native.cell, "operation") == "clearWarnings") {
                    check(selected == null || selected === call)
                    selected = call
                    invocation = native
                }
            }
        }

        override fun set(value: PersistenceJdbcGuardCall?) = delegate.set(value)

        override fun remove() {
            if (Thread.currentThread() === caller && selected != null && delegate.get() === selected) {
                check(++injections == 1 && !entry.jdbc.ownershipLockHeld())
                check(driver.get(requireNotNull(selected)) == null) // finishDriver really returned/cleared before finishProducer tried its TL.
                check(ownedCutField(requireNotNull(invocation).cell, "ended") == true)
                check(requireNotNull(invocation).owner.root === (ownedCutField(context, "driverRoot") as AtomicReference<*>).get())
                nativeEndedAtRemoval = true
                throw problem
            }
            delegate.remove()
        }

        override fun close() {
            check(field.get(context) === this)
            field.set(context, delegate) // No TL removal, count write, manual finish or raw close is permitted here.
            restored = true
            if (selected != null) {
                check(delegate.get() === selected)
                unhealedOnCaller = true
            }
        }

        fun requireRestoredUnhealed() {
            check(injections == 1 && nativeEndedAtRemoval && restored && unhealedOnCaller && field.get(context) === delegate)
            check(ownedCutField(requireNotNull(invocation).cell, "ended") == true)
            check(ownedCutField(requireNotNull(selected), "ended") == false)
        }
    }
}

/** Small fixture glue over the existing disposable server and authentic typed request; no model Entry installation. */
internal fun withOwnedCutConnection(database: PgLifecycleDatabaseFixture, originalProvider: Boolean = false, test: (OwnedCutConnection) -> Unit) {
    val case = PgLifecycleDatabaseCase(
        PgLifecycleDatabaseRecipe.DEFAULT,
        0,
        PgLifecycleDatabaseLane.ORDINARY,
        if (originalProvider) PgLifecycleDatabaseMode.ORIGINAL_MATRIX else PgLifecycleDatabaseMode.MATRIX,
    )
    val base = PgLifecycleDatabaseSettings.endpoint(case, database.port, "w03c_${UUID.randomUUID()}")
    val properties = base.driverProperties().stringPropertyNames().associateWith { base.driverProperties().getProperty(it) } +
        ("PGHOST" to database.host)
    PgLifecycleTestScope(ResolvedPersistenceEndpoint(properties, base.loginPolicy)).use { scope ->
        scope.start()
        awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
        val request = scope.owner.prepareOrdinaryRequest()
        val result = request.executePoolConnection()
        check(result is PersistenceFactoryResult.Success) { "Actual owned lower-facade request failed: $result" }
        val entry = requireNotNull(request.admittedEntry(scope.binding()))
        assertSame(entry, scope.entries().single())
        assertSame(requireNotNull(entry.control).receipt, result.receipt)
        awaitLifecycleFact { result.receipt.state() !== PersistenceFactoryProcessing.PENDING }
        assertEquals(PersistenceFactoryProcessing.PROCESSING_ENDED, result.receipt.state())
        OwnedCutConnection(scope, entry, result.value).use(test)
    }
}

/** Same retained driver/physical fixture, now through the actual private lower -> stock Hikari -> lease path. */
internal fun withOwnedCutPool(database: PgLifecycleDatabaseFixture, test: (OwnedCutPool) -> Unit) {
    val case = PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, PgLifecycleDatabaseLane.ORDINARY, PgLifecycleDatabaseMode.MATRIX)
    val base = PgLifecycleDatabaseSettings.endpoint(case, database.port, "w03c_${UUID.randomUUID()}")
    val properties = base.driverProperties().stringPropertyNames().associateWith { base.driverProperties().getProperty(it) } +
        ("PGHOST" to database.host)
    val endpoint = ResolvedPersistenceEndpoint(properties, base.loginPolicy)
    // One Hikari slot, with a second physical custody cell for retirement/replacement overlap.
    PgLifecycleTestScope(endpoint, capacity = 2).use { scope ->
        val pool = GuardedDataSource(scope.owner, endpoint, 1, PersistencePoolLaunchProfile.CONTROLLED_TEST_ONLY)
        OwnedCutPool(scope, pool).use { fixture ->
            assertEquals(PersistenceLifecycleActivation.STARTED, pool.start())
            awaitLifecycleFact { scope.owner.snapshot().ordinaryReady && scope.owner.snapshot().timerReady }
            assertEquals(PersistenceLifecycleObservation.READY, pool.observePreparation())
            awaitLifecycleFact { scope.binding().isOwnedReceiverReady() }
            test(fixture)
        }
    }
}

/** Test-only assertion handles. Production has no pool, raw, epoch or entitlement getter. */
internal class OwnedCutPool(val scope: PgLifecycleTestScope, val pool: GuardedDataSource) : AutoCloseable {
    val lifecycle = ownedCutField(pool, "lifecycle") as PoolLifecycle
    var expectedPoolUnknown = false

    fun entry(connection: Connection): PersistencePhysicalEntry {
        val state = ownedPoolLease(connection).state
        return scope.entries().single { it.jdbc.currentPoolState(state) }
    }

    override fun close() {
        val receipt = requireNotNull(pool.requestShutdown())
        assertTrue(pool.shutdownInvocation() in setOf(PoolShutdownInvocation.RETURNED, PoolShutdownInvocation.ALREADY_CLAIMED))
        var observation = PoolShutdownObservation.PENDING
        awaitLifecycleFact {
            observation = receipt.observe()
            observation !== PoolShutdownObservation.PENDING
        }
        val expected = if (scope.expectedUnknown || expectedPoolUnknown) {
            PoolShutdownObservation.UNKNOWN
        } else {
            PoolShutdownObservation.TRACKED_LOCAL_ENDED
        }
        assertEquals(expected, observation)
        val actors = lifecycle.actorSnapshot()
        assertEquals(0L, actors.futureLeaseEntries)
        assertEquals(0L, actors.activeOperations)
        assertEquals(0, actors.constructing)
        assertTrue(actors.factorySealed)
        assertTrue(actors.retiredGenerations > 0L, "A real stock-Hikari population, not an inert MODEL shell, ended.")
    }
}

internal fun ownedPoolLease(connection: Connection): PersistenceJdbcLease {
    assertTrue(connection is LeaseJdbcFacade)
    return ownedCutField(requireNotNull(ownedCutField(connection, "calls")), "lease") as PersistenceJdbcLease
}

internal fun ownedPoolRoot(lease: PersistenceJdbcLease): PersistencePgOwnedCutAccess.Root =
    requireNotNull((ownedCutField(lease.state.context, "driverRoot") as AtomicReference<*>).get()) as PersistencePgOwnedCutAccess.Root

/** Exact test-only upper -> Hikari-or-lower -> lower association, not arbitrary unwrapping in production. */
internal fun ownedPoolLower(guard: Any): Any {
    val delegated = ownedCutNative(guard)
    if (PhysicalJdbcDescendants.knownGuard(delegated) != null) return delegated
    assertTrue(delegated.javaClass.name.startsWith("com.zaxxer.hikari.pool.HikariProxy"))
    return requireNotNull(ownedCutField(delegated, "delegate")).also { assertNotNull(PhysicalJdbcDescendants.knownGuard(it)) }
}

internal fun ownedPoolLife(guard: Any): PersistencePgOwnedCutAccess.Life =
    requireNotNull(PhysicalJdbcDescendants.knownGuard(ownedPoolLower(guard))?.driverLife)

internal fun ownedPoolScalar(connection: Connection, sql: String): Int = connection.createStatement().use { statement ->
    statement.executeQuery(sql).use { result ->
        assertTrue(result.next())
        result.getInt(1).also { assertFalse(result.next()) }
    }
}

private enum class CheckoutCallerFault { NONE, SAMPLE, ACTUAL_FLAG, RESTORE }

/** Existing C5 caller fixtures, now around an actual captured stock-Hikari checkout. */
private fun assertOwnedCheckoutCaller(f: OwnedCutPool, fault: CheckoutCallerFault) {
    val entry = f.pool.connection.use { f.entry(it) } // Warm genuine pool before arming an original borrower override.
    f.expectedPoolUnknown = fault !== CheckoutCallerFault.NONE
    OwnedCallerTestScope().use { callers ->
        val sample = callers.gate()
        val restore = callers.gate()
        val problem = IllegalStateException("Injected original checkout caller failure.")
        val behavior = OwnedCallerTestBehavior().apply {
            sampleGate = sample
            gateAtSample = 2 // The former second claim sample ran under G; this must not.
            if (fault === CheckoutCallerFault.ACTUAL_FLAG || fault === CheckoutCallerFault.RESTORE) restoreGate = restore
            if (fault === CheckoutCallerFault.RESTORE) restoreFailure = problem
        }
        val exposed = AtomicBoolean()
        val flagAfter = AtomicBoolean()
        val caller = callers.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            try {
                val outcome = runCatching {
                    f.pool.connection.use { connection ->
                        exposed.set(true)
                        assertSame(entry, f.entry(connection))
                        assertEquals(9, ownedPoolScalar(connection, "SELECT 9"))
                    }
                }
                flagAfter.set((Thread.currentThread() as OverridingCallerThread).actualFlag())
                assertNull(PoolCallFrames.current())
                assertNull(PersistenceJdbcDispatch.current())
                outcome
            } finally {
                Thread.interrupted() // Fixture flag cleanup only; no product receipt/counter is changed.
            }
        }
        sample.awaitEntered()
        assertTransferLocksAvailable(f, entry)
        val transfer = (ownedCutField(entry.jdbc, "poolTransfer") as AtomicReference<*>).get() as PersistenceJdbcPoolTransfer
        assertSame(PersistenceJdbcPoolTransfer.Kind.CHECKOUT, transfer.kind)
        assertFalse(transfer.source.leased)
        assertTrue(transfer.source.epoch.sealedAndEnded())
        assertFalse(transfer.consented() || transfer.actualEnded() || exposed.get())
        assertEquals(1L, f.lifecycle.activeAcquisitions())
        assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
        assertFalse(behavior.reportedFlag)
        when (fault) {
            CheckoutCallerFault.SAMPLE -> behavior.sampleFailure = problem
            CheckoutCallerFault.ACTUAL_FLAG, CheckoutCallerFault.RESTORE -> (caller.thread as OverridingCallerThread).setActualFlag()
            CheckoutCallerFault.NONE -> Unit
        }
        sample.release()
        if (fault === CheckoutCallerFault.ACTUAL_FLAG || fault === CheckoutCallerFault.RESTORE) {
            restore.awaitEntered()
            assertTransferLocksAvailable(f, entry)
            assertTrue(entry.retirementRequested.get(), "Authoritative exact-source failure precedes overriding restoration.")
            assertFalse(transfer.consented() || transfer.actualEnded() || exposed.get())
            assertEquals(1L, f.lifecycle.activeAcquisitions())
            assertEquals(1L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            assertFalse((caller.thread as OverridingCallerThread).actualFlag(), "The C5 actual read, not a false override, consumed this flag.")
            assertFalse(requireNotNull(entry.terminalWork).producerDrainProven(), "The authentic transfer holder still prevents a terminal drain receipt.")
            restore.release()
        }
        val outcome = caller.value()
        if (fault === CheckoutCallerFault.NONE) {
            outcome.getOrThrow()
            assertTrue(exposed.get() && transfer.consented())
            assertFalse(entry.retirementRequested.get())
        } else {
            assertFalse(exposed.get() || transfer.consented())
            assertTrue(entry.retirementRequested.get())
            if (fault === CheckoutCallerFault.ACTUAL_FLAG) {
                assertTrue(outcome.exceptionOrNull() is SQLException)
            } else {
                assertSame(problem, outcome.exceptionOrNull())
            }
            awaitLifecycleFact { f.scope.entries().none { it === entry } }
        }
        assertTrue(transfer.actualEnded())
        assertEquals(fault === CheckoutCallerFault.ACTUAL_FLAG, flagAfter.get())
        assertEquals(if (fault === CheckoutCallerFault.ACTUAL_FLAG || fault === CheckoutCallerFault.RESTORE) 1 else 0, behavior.restores.get())
        assertEquals(0L, f.lifecycle.activeAcquisitions())
        assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
    }
}

private enum class ReturnCallerFault {
    EXPIRE,
    SAMPLE,
    ACTUAL_FLAG,
    RESTORE,
    INTERRUPTED_EXCEPTION,
    INTERRUPTED_RESTORE_INTERRUPTED,
    INTERRUPTED_RESTORE_ERROR,
}

/** Arms only after real acquisition, on the original overriding Thread, before its actual one-use RETURN. */
private fun assertOwnedReturnCaller(f: OwnedCutPool, fault: ReturnCallerFault) {
    val entry = f.pool.connection.use { f.entry(it) }
    val interruptedSample = fault in setOf(
        ReturnCallerFault.INTERRUPTED_EXCEPTION,
        ReturnCallerFault.INTERRUPTED_RESTORE_INTERRUPTED,
        ReturnCallerFault.INTERRUPTED_RESTORE_ERROR,
    )
    val restorationExpected = interruptedSample || fault === ReturnCallerFault.ACTUAL_FLAG || fault === ReturnCallerFault.RESTORE
    f.expectedPoolUnknown = interruptedSample || fault === ReturnCallerFault.SAMPLE || fault === ReturnCallerFault.RESTORE
    OwnedCallerTestScope().use { callers ->
        val sample = callers.gate()
        val restore = callers.gate()
        val problem = if (interruptedSample) {
            InterruptedException("Injected authentic RETURN caller interruption.")
        } else {
            IllegalStateException("Injected authentic RETURN caller failure.")
        }
        val restorationProblem = when (fault) {
            ReturnCallerFault.INTERRUPTED_RESTORE_INTERRUPTED -> InterruptedException("Injected RETURN adapter restoration interruption.")
            ReturnCallerFault.INTERRUPTED_RESTORE_ERROR -> AssertionError("Injected RETURN adapter restoration Error.")
            ReturnCallerFault.RESTORE -> problem
            else -> null
        }
        val behavior = OwnedCallerTestBehavior()
        val retained = AtomicReference<PersistenceJdbcLease>()
        val originalDelegate = AtomicReference<Any>()
        val flagAfter = AtomicBoolean()
        val caller = callers.launch(OwnedCallerTestKind.OVERRIDING, behavior) {
            val connection = f.pool.connection
            val lease = ownedPoolLease(connection)
            retained.set(lease)
            originalDelegate.set(requireNotNull(ownedCutField(ownedCutField(lease, "handle") as Connection, "delegate")))
            behavior.sampleGate = sample
            val samplesUntilCut = when (fault) {
                ReturnCallerFault.EXPIRE -> 3 // Final commit sample, after genuine native/core finalizers and successor preparation.
                ReturnCallerFault.RESTORE,
                ReturnCallerFault.INTERRUPTED_EXCEPTION,
                ReturnCallerFault.INTERRUPTED_RESTORE_INTERRUPTED,
                ReturnCallerFault.INTERRUPTED_RESTORE_ERROR -> 1 // Before any Hikari return/reset call.
                else -> 2 // The former second claim sample was under G.
            }
            behavior.gateAtSample = behavior.samples.get() + samplesUntilCut
            if (restorationExpected) behavior.restoreGate = restore
            behavior.restoreFailure = restorationProblem
            try {
                val outcome = runCatching { connection.close() }
                flagAfter.set((Thread.currentThread() as OverridingCallerThread).actualFlag())
                assertNull(PoolCallFrames.current())
                assertNull(PersistenceJdbcDispatch.current())
                connection.close() // Facade-only after success or failure, never another return budget.
                outcome
            } finally {
                Thread.interrupted()
                connection.close()
            }
        }
        sample.awaitEntered()
        assertTransferLocksAvailable(f, entry)
        val lease = retained.get()
        val transfer = ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer
        val entitlement = ownedCutField(lease, "entitlement") as PoolLifecycle.LeaseEntitlement
        val operation = ownedCutField(entitlement, "prepared") as PoolLifecycle.Operation
        assertSame(PersistenceJdbcPoolTransfer.Kind.RETURN, transfer.kind)
        assertSame(lease.state, transfer.source)
        assertSame(operation.frame.budget, transfer.budget, "Use the actual preissued RETURN frame's original allowance.")
        assertEquals(TimeUnit.MILLISECONDS.toNanos(1_000), ownedCutField(transfer.budget, "allowanceNanos"))
        assertFalse(transfer.consented() || transfer.actualEnded() || operation.actualFrameEnded())
        assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
        assertFalse(behavior.reportedFlag)
        when (fault) {
            ReturnCallerFault.EXPIRE -> {
                assertTrue(lease.state.epoch.sealedAndEnded())
                awaitLifecycleFact(2_000) { persistenceFactoryRemainingMillis(transfer.budget) == 0L }
                assertTrue(entry.jdbc.currentPoolState(lease.state))
            }
            ReturnCallerFault.SAMPLE,
            ReturnCallerFault.INTERRUPTED_EXCEPTION,
            ReturnCallerFault.INTERRUPTED_RESTORE_INTERRUPTED,
            ReturnCallerFault.INTERRUPTED_RESTORE_ERROR -> behavior.sampleFailure = problem
            ReturnCallerFault.ACTUAL_FLAG, ReturnCallerFault.RESTORE -> (caller.thread as OverridingCallerThread).setActualFlag()
        }
        sample.release()
        if (restorationExpected) {
            restore.awaitEntered()
            assertTransferLocksAvailable(f, entry)
            assertTrue(entry.retirementRequested.get(), "Failure disposition must be visible BEFORE an overridable self-interrupt.")
            assertFalse(transfer.consented() || operation.actualFrameEnded())
            assertFalse(operation.frame.completion.hasEnded(), "Even the creator completion must remain unpublished throughout this callback.")
            assertEquals("ACTIVE", requireNotNull(ownedCutField(operation.frame, "phase")).toString())
            assertEquals(1, behavior.restores.get(), "Observe the sole restoration while the authentic RETURN is still counted.")
            assertTrue(lease.closed())
            assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
            assertFalse((caller.thread as OverridingCallerThread).actualFlag())
            if (fault === ReturnCallerFault.ACTUAL_FLAG) {
                assertTrue(transfer.actualEnded(), "The genuine lower failure tail ended, but the outer RETURN actor has not.")
            } else {
                assertFalse(transfer.actualEnded())
                assertSame(originalDelegate.get(), ownedCutField(ownedCutField(lease, "handle") as Connection, "delegate"))
            }
            restore.release()
        }
        val outcome = caller.value().exceptionOrNull()
        assertEquals(Thread.State.TERMINATED, caller.thread.state)
        if (fault === ReturnCallerFault.INTERRUPTED_RESTORE_ERROR) {
            assertSame(restorationProblem, outcome, "An adapter Error is rethrown unchanged, never adapted or retried.")
        } else {
            assertTrue(outcome is SQLException)
            assertNull(outcome?.cause, "Neither original nor adapter-thrown InterruptedException is retained in the SQL envelope.")
        }
        assertTrue(lease.closed() && entry.retirementRequested.get() && transfer.actualEnded())
        assertFalse(transfer.consented())
        assertEquals(fault === ReturnCallerFault.SAMPLE || interruptedSample, transfer.callerSamplingFailed())
        assertTrue(operation.actualFrameEnded())
        assertSame(operation, ownedCutField(entitlement, "prepared"))
        assertSame(operation.frame.budget, transfer.budget)
        if (fault === ReturnCallerFault.EXPIRE) assertEquals(0L, persistenceFactoryRemainingMillis(transfer.budget))
        assertEquals(fault === ReturnCallerFault.ACTUAL_FLAG || fault === ReturnCallerFault.INTERRUPTED_EXCEPTION, flagAfter.get())
        assertEquals(if (restorationExpected) 1 else 0, behavior.restores.get(), "No second restoration may run before or after actual RETURN end.")
        assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
        assertEquals(0L, f.lifecycle.actorSnapshot().activeOperations)
        if (f.expectedPoolUnknown) assertEquals(PoolActorFault.BOOKKEEPING_FAILED, f.lifecycle.actorSnapshot().firstFailure)
        awaitLifecycleFact { f.scope.entries().none { it === entry } }
    }
}

/** While the exact caller is held in its override, no F/G/T lock may be held by that callback. */
private fun assertTransferLocksAvailable(f: OwnedCutPool, entry: PersistencePhysicalEntry) {
    val binding = f.scope.binding()
    val transports = requireNotNull(entry.transports)
    val transportOwner = requireNotNull(ownedCutField(transports, "owner"))
    val t = ownedCutField(transportOwner, "lock") as ReentrantLock
    for (lock in listOf(binding.rendezvous.lock, binding.ledger.lock, t)) {
        val acquired = lock.tryLock(250, TimeUnit.MILLISECONDS)
        assertTrue(acquired, "A blocked/throwing application interruption override must leave F/G/T free.")
        if (acquired) lock.unlock()
    }
}

/** One exact private actor-storage instance; preserve its single global TL key and every real value. */
private class ThrowingReturnEntry(private val lifecycle: PoolLifecycle, private val problem: Throwable) : ThreadLocal<PoolCallFrame?>(), AutoCloseable {
    private val caller = Thread.currentThread()
    private val storage = requireNotNull(ownedCutField(PoolCallFrames, "storage"))
    private val field = storage.javaClass.getDeclaredField("current").apply { check(trySetAccessible()) }
    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(storage) as ThreadLocal<PoolCallFrame?>
    var selected: PoolCallFrame? = null
        private set
    var injections = 0
        private set
    var outsideOwnershipLocks = false
        private set

    init {
        assertNull(delegate.get())
        field.set(storage, this)
    }

    override fun get(): PoolCallFrame? = delegate.get()

    override fun set(value: PoolCallFrame?) {
        delegate.set(value) // Genuine publication, not a fabricated current-frame result.
        if (Thread.currentThread() === caller && value?.kind === PoolCallKind.RETURN && selected == null) {
            assertSame(lifecycle, ownedCutField(value, "pool"))
            selected = value
            injections++
            outsideOwnershipLocks = !lifecycle.ownershipLockHeld()
            throw problem // enterFrame must run its actual failure/refuseEntry restoration path.
        }
    }

    override fun remove() = delegate.remove()

    override fun close() {
        assertSame(this, field.get(storage))
        field.set(storage, delegate) // Restore storage only; never write frame phase/completion/counters.
        assertNull(delegate.get())
    }
}

/** Test-only exact instance-TL interception. All storage stays in the original ThreadLocal. */
private class CoreLastCountBarrier(private val lease: PersistenceJdbcLease) : ThreadLocal<PersistenceJdbcGuardCall?>(), AutoCloseable {
    private val context = lease.state.context
    private val caller = Thread.currentThread()
    private val field = context.javaClass.getDeclaredField("frames").apply { check(trySetAccessible()) }
    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(context) as ThreadLocal<PersistenceJdbcGuardCall?>
    private val driver = PersistenceJdbcGuardCall::class.java.getDeclaredField("driver").apply { check(trySetAccessible()) }
    private val root = ownedPoolRoot(lease)
    private val nativeInvocation = Class.forName("$OWNED_CUT_HELPER\$Invocation", false, root.cell.javaClass.classLoader)
    private val operation = nativeInvocation.getDeclaredField("operation").apply { check(trySetAccessible()) }
    private val nativeEnd = nativeInvocation.getDeclaredField("ended").apply { check(trySetAccessible()) }
    private val once = AtomicBoolean()
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    private var selected: PersistenceJdbcGuardCall? = null
    private var invocation: PersistencePgOwnedCutAccess.Invocation? = null
    val timedOut = AtomicBoolean()

    init {
        assertNull(delegate.get())
        field.set(context, this)
    }

    override fun get(): PersistenceJdbcGuardCall? = delegate.get().also { call ->
        if (Thread.currentThread() === caller && call != null && PersistenceJdbcDispatch.current()?.returning() == true) {
            val native = driver.get(call) as? PersistencePgOwnedCutAccess.Invocation
            if (native != null && operation.get(native.cell) == "clearWarnings") {
                selected = call
                invocation = native
            }
        }
    }

    override fun set(value: PersistenceJdbcGuardCall?) = delegate.set(value)

    override fun remove() {
        if (Thread.currentThread() === caller && delegate.get() === selected && selected != null && once.compareAndSet(false, true)) {
            entered.countDown()
            // Timeout records a failed assertion but still lets the actual frame finish/clean up.
            if (!released.await(5, TimeUnit.SECONDS)) timedOut.set(true)
        }
        delegate.remove()
    }

    fun nativeEnded(): Boolean = invocation?.let { nativeEnd.getBoolean(it.cell) } == true
    fun awaitEntered() = assertTrue(entered.await(5, TimeUnit.SECONDS))
    fun release() = released.countDown()

    override fun close() {
        release()
        assertSame(this, field.get(context))
        field.set(context, delegate)
        assertNull(delegate.get())
    }
}

/** Stock ConcurrentBag's instance TL get is after STATE_NOT_IN_USE; no driver/metrics callback is installed. */
private class ConsentedRecycleBarrier(
    private val fixture: OwnedCutPool,
    private val lease: PersistenceJdbcLease,
    private val failTail: Boolean,
) : ThreadLocal<Any?>(), AutoCloseable {
    private val caller = Thread.currentThread()
    private val hikari = requireNotNull(ownedCutField(fixture.pool, "pool"))
    private val pool = requireNotNull(ownedCutField(hikari, "pool"))
    private val bag = requireNotNull(ownedCutField(pool, "connectionBag"))
    private val field = bag.javaClass.getDeclaredField("threadLocalList").apply { check(trySetAccessible()) }
    @Suppress("UNCHECKED_CAST")
    private val delegate = field.get(bag) as ThreadLocal<Any?>
    private val once = AtomicBoolean()
    private val entered = CountDownLatch(1)
    private val released = CountDownLatch(1)
    val timedOut = AtomicBoolean()

    init {
        assertEquals(0, (ownedCutField(bag, "waiters") as java.util.concurrent.atomic.AtomicInteger).get())
        field.set(bag, this)
    }

    override fun get(): Any? {
        val value = delegate.get()
        val attempt = ownedCutField(lease, "transfer") as? PersistenceJdbcPoolTransfer
        if (Thread.currentThread() === caller && attempt?.consented() == true && once.compareAndSet(false, true)) {
            entered.countDown()
            if (!released.await(5, TimeUnit.SECONDS)) timedOut.set(true)
            // The authentic original RETURN actor still exists, but its exact source credential
            // was revoked before this point. This call MUST be a no-op against the successor.
            fixture.pool.evictOwned(lease, ownedCutField(lease, "handle") as Connection, attempt.budget)
            if (failTail) throw SQLException("Injected post-consent Hikari bookkeeping failure.")
        }
        return value
    }

    override fun set(value: Any?) = delegate.set(value)
    override fun remove() = delegate.remove()
    fun awaitEntered() = assertTrue(entered.await(5, TimeUnit.SECONDS))
    fun release() = released.countDown()

    override fun close() {
        release()
        assertSame(this, field.get(bag))
        field.set(bag, delegate)
    }
}

private fun assertConsentedOldTail(f: OwnedCutPool, failTail: Boolean) {
    val first = f.pool.connection
    val lease = ownedPoolLease(first)
    val entry = f.entry(first)
    val root = ownedPoolRoot(lease)
    val pid = ownedPoolScalar(first, "SELECT pg_backend_pid()")
    val tailEnded = CountDownLatch(1)
    try {
        ConsentedRecycleBarrier(f, lease, failTail).use { barrier ->
            FactoryWorkerTestScope().use { workers ->
                val successor = workers.launch {
                    barrier.awaitEntered()
                    var next: Connection? = null
                    try {
                        assertTrue(lease.state.epoch.sealedAndEnded())
                        assertTrue((ownedCutField(lease, "transfer") as PersistenceJdbcPoolTransfer).consented())
                        assertEquals(1L, f.lifecycle.actorSnapshot().activeOperations)
                        val borrowed = f.pool.connection
                        next = borrowed
                        val current = ownedPoolLease(borrowed)
                        assertSame(entry, f.entry(borrowed))
                        assertNotSame(root.cell, ownedPoolRoot(current).cell)
                        assertEquals(pid, ownedPoolScalar(borrowed, "SELECT pg_backend_pid()"))
                        first.close()
                        val aborts = java.util.concurrent.atomic.AtomicInteger()
                        first.abort(java.util.concurrent.Executor { aborts.incrementAndGet() })
                        assertEquals(0, aborts.get())
                        barrier.release()
                        assertTrue(tailEnded.await(5, TimeUnit.SECONDS))
                        assertTrue(entry.jdbc.currentPoolState(current.state))
                        assertFalse(entry.retirementRequested.get(), "The old consented tail cannot evict/poison this successor.")
                        assertFalse(current.state.epoch.poisoned())
                        if (failTail) {
                            assertEquals(PoolActorFault.BOOKKEEPING_FAILED, f.lifecycle.actorSnapshot().firstFailure)
                            assertThrows<SQLException> { borrowed.createStatement() }
                            assertThrows<SQLException> { borrowed.close() }
                        } else {
                            assertEquals(5, ownedPoolScalar(borrowed, "SELECT 5"))
                            borrowed.close()
                        }
                        next = null
                    } finally {
                        barrier.release()
                        next?.close()
                    }
                    true
                }
                try {
                    if (failTail) assertThrows<SQLException> { first.close() } else first.close()
                } finally {
                    barrier.release()
                    tailEnded.countDown()
                }
                assertTrue(successor.join())
            }
            assertFalse(barrier.timedOut.get())
        }
    } finally {
        tailEnded.countDown()
        first.close()
    }
}

internal class OwnedCutConnection(val scope: PgLifecycleTestScope, val entry: PersistencePhysicalEntry, val connection: PhysicalJdbcFacade) : AutoCloseable {
    val raw: Connection = requireNotNull(entry.raw.get())
    val epoch: PersistenceProducerEpoch = requireNotNull(entry.jdbc.poolEpoch(scope.binding().poolIdentity))
    val work: PersistenceTerminalWork = requireNotNull(entry.terminalWork)
    val opening: PersistencePgOwnedCutAccess.Opening
        get() = requireNotNull((ownedCutField(entry.driverCut, "opening") as AtomicReference<*>).get()) as PersistencePgOwnedCutAccess.Opening
    val root: PersistencePgOwnedCutAccess.Root
        get() = requireNotNull((ownedCutField(entry.driverCut, "root") as AtomicReference<*>).get()) as PersistencePgOwnedCutAccess.Root

    fun sql(sql: String) = connection.createStatement().use { it.executeUpdate(sql) }

    fun scalar(sql: String): Int = connection.createStatement().use { statement ->
        statement.executeQuery(sql).use { result ->
            assertTrue(result.next())
            result.getInt(1).also { assertFalse(result.next()) }
        }
    }

    override fun close() {
        // Standard use suppression preserves the original assertion/native failure during retirement.
        connection.close()
        if (scope.expectedUnknown) awaitUnknown() else retire()
    }

    fun retire() {
        connection.close()
        awaitLifecycleFact { scope.entries().isEmpty() }
        assertTrue(work.bodyExited() && work.producerDrainProven() && entry.scopeEnded)
        assertEquals(PersistenceTerminalCall.RETURNED, work.closeState())
        val expected = if (entry.policy === PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER) {
            PersistenceTerminalDisposition.DRIVER_CLOSE_RETURNED
        } else {
            assertSame(PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONJUNCTION, entry.policy)
            assertNotNull(work.acknowledgedBoundary())
            PersistenceTerminalDisposition.TRACKED_DISPOSED
        }
        assertEquals(expected, work.disposition())
        assertTrue(entry.driverCut.canReclaim())
        assertEquals(0L, entry.driverCut.fixedNativeChildren())
    }

    fun awaitUnknown() {
        assertTrue(scope.expectedUnknown)
        awaitLifecycleFact { work.bodyExited() && work.disposition() !== PersistenceTerminalDisposition.PENDING }
        assertTrue(work.producerDrainProven() && entry.jdbc.postOpeningCallsEnded())
        assertEquals(PersistenceTerminalDisposition.UNKNOWN_ENDED, work.disposition())
        assertEquals(PersistenceTerminalCall.RETURNED, work.closeState())
        assertFalse(entry.driverCut.canReclaim())
        assertSame(entry, scope.entries().single())
    }
}

/** Native bytea is already copied by pgjdbc; its guarded stream is independent of the closed source ResultSet. */
internal fun ownedCutInput(connection: Connection): InputStream = connection.createStatement().use { statement ->
    statement.executeQuery("SELECT decode('0102030405060708', 'hex')").use { result ->
        assertTrue(result.next())
        requireNotNull(result.getBinaryStream(1))
    }
}

internal fun ownedCutNative(guard: Any): Any = requireNotNull(ownedCutField(requireNotNull(PhysicalJdbcDescendants.knownGuard(guard)), "native"))

/** Assertion-only reads of the audited core/native fields, including PgPreparedStatement's inherited native state. */
internal fun ownedCutField(owner: Any, name: String): Any? {
    var type: Class<*>? = owner.javaClass
    while (type != null) {
        val field = type.declaredFields.singleOrNull { it.name == name }
        if (field != null) {
            check(field.trySetAccessible())
            return field.get(owner)
        }
        type = type.superclass
    }
    error("Required owned-cut assertion field is missing: $name")
}

internal fun ownedCutSingleBinding(parameters: Any): Any =
    requireNotNull((ownedCutField(requireNotNull(ownedCutField(parameters, "kira")), "bindings") as Array<*>).single())

internal fun ownedCutSingleValue(parameters: Any): Any = requireNotNull((ownedCutField(parameters, "paramValues") as Array<*>).single())

internal fun ownedCutNativeChildren(connection: Connection): List<Any> = buildList {
    var child = ownedCutField(requireNotNull(ownedCutField(connection, "kira")), "children")
    while (child != null) {
        add(child)
        child = ownedCutField(child, "next")
    }
}

internal fun ownedCutBatchArrays(invocation: PersistencePgOwnedCutAccess.Invocation): Map<String, Any?> {
    val batch = requireNotNull(ownedCutField(invocation.cell, "batch"))
    return listOf("queries", "parameters", "queryImage", "parameterImage", "actualImages")
        .associateWith { ownedCutField(batch, it) }
}

/**
 * Batch-only observation of the real core extent that a finished public handler has already discarded.
 * The existing admitted node/context, authentic native receiver, real carrier, escrow, guardOutput and
 * GuardCall.finish are used. The observer runs after native return/throw, never in the two-clear cut.
 */
internal fun ownedCutExecuteBatch(
    statement: PreparedStatement,
    inspect: (PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation, IntArray?, Throwable?) -> Unit,
): PersistencePgOwnedCutAccess.Invocation {
    val node = requireNotNull(PhysicalJdbcDescendants.knownGuard(statement))
    val graph = ownedCutField(node, "graph") as PhysicalJdbcDescendants
    val identity = ownedCutField(node, "identity") as PersistenceJdbcGuardIdentity
    val native = ownedCutNative(statement)
    val method = Statement::class.java.getMethod("executeBatch")
    val arguments = emptyArray<Any?>()
    val call = graph.context.enter(identity, PersistenceJdbcGuardCallKind.BUSINESS)
    try {
        node.requireInput(graph, identity)
        call.prepareDriver(native, method, arguments, PhysicalJdbcInputs.prepare(graph, identity, arguments, arguments))
        val invocation = ownedCutField(call, "driver") as PersistencePgOwnedCutAccess.Invocation
        assertSame(call, invocation.callKey)
        assertSame(call, ownedCutField(invocation.cell, "callKey"))
        var failure: Throwable? = null
        call.armDriver()
        val output = try {
            val returned = method.invoke(native)
            call.captureOutput(returned)
            returned
        } catch (envelope: InvocationTargetException) {
            call.reconcileDriver()
            call.failedBeforeBoxing(false)
            failure = call.failure(envelope.targetException)
            assertSame(envelope.targetException, failure)
            null
        }
        call.reconcileDriver()
        // Empty PRE legitimately has no arrays. The test, not a public return, establishes COMMITTED.
        val arrays = ownedCutBatchArrays(invocation)
        inspect(call, invocation, output as IntArray?, failure)
        if (failure == null) {
            assertSame(output, ownedCutField(call, "output"))
            assertSame(output, graph.guardOutput(call, output, node))
            call.outputGuarded()
        }
        val afterGuarding = ownedCutBatchArrays(invocation)
        arrays.forEach { (name, retained) -> assertSame(retained, afterGuarding[name], "Batch custody must survive core output guarding: $name") }
        return invocation
    } finally {
        call.finish()
    }
}

/** Row-only counterpart: inspect the actual hidden Prepared before outer actualEnd compacts its close receipt. */
internal fun ownedCutUpdateRow(
    result: ResultSet,
    inspect: (PersistenceJdbcGuardCall, PersistencePgOwnedCutAccess.Invocation, Throwable?) -> Unit,
): PersistencePgOwnedCutAccess.Invocation {
    val node = requireNotNull(PhysicalJdbcDescendants.knownGuard(result))
    val graph = ownedCutField(node, "graph") as PhysicalJdbcDescendants
    val identity = ownedCutField(node, "identity") as PersistenceJdbcGuardIdentity
    val native = ownedCutNative(result)
    val method = ResultSet::class.java.getMethod("updateRow")
    val arguments = emptyArray<Any?>()
    val call = graph.context.enter(identity, PersistenceJdbcGuardCallKind.BUSINESS)
    try {
        node.requireInput(graph, identity)
        call.prepareDriver(native, method, arguments, PhysicalJdbcInputs.prepare(graph, identity, arguments, arguments))
        val invocation = ownedCutField(call, "driver") as PersistencePgOwnedCutAccess.Invocation
        assertSame(call, invocation.callKey)
        assertSame(call, ownedCutField(invocation.cell, "callKey"))
        var failure: Throwable? = null
        call.armDriver()
        try {
            call.captureOutput(method.invoke(native))
        } catch (envelope: InvocationTargetException) {
            call.reconcileDriver()
            call.failedBeforeBoxing(false)
            failure = call.failure(envelope.targetException)
            assertSame(envelope.targetException, failure)
        }
        call.reconcileDriver()
        inspect(call, invocation, failure)
        if (failure == null) {
            assertNull(graph.guardOutput(call, null, node))
            call.outputGuarded()
        }
        return invocation
    } finally {
        call.finish()
    }
}

/** An actual invalid-index native SQL exception, not an injected replacement, crosses the real core failure adapter unchanged. */
internal fun assertOwnedCutSetterSqlIdentity(statement: PreparedStatement, input: InputStream) {
    val node = requireNotNull(PhysicalJdbcDescendants.knownGuard(statement))
    val graph = ownedCutField(node, "graph") as PhysicalJdbcDescendants
    val identity = ownedCutField(node, "identity") as PersistenceJdbcGuardIdentity
    val native = ownedCutNative(statement)
    val method = PreparedStatement::class.java.getMethod("setBinaryStream", Integer.TYPE, InputStream::class.java, Integer.TYPE)
    val arguments = arrayOf<Any?>(2, input, 8)
    val call = graph.context.enter(identity, PersistenceJdbcGuardCallKind.BUSINESS)
    try {
        node.requireInput(graph, identity)
        graph.validateArguments(identity, arguments)
        val adapted = graph.adaptArguments(identity, arguments, structural = false)
        call.prepareDriver(native, method, adapted, PhysicalJdbcInputs.prepare(graph, identity, arguments, adapted))
        val invocation = ownedCutField(call, "driver") as PersistencePgOwnedCutAccess.Invocation
        assertSame(call, invocation.callKey)
        assertSame(call, ownedCutField(invocation.cell, "callKey"))
        call.armDriver()
        val envelope = assertThrows<InvocationTargetException> { method.invoke(native, *adapted) }
        val failure = envelope.targetException as SQLException
        assertEquals("22023", failure.sqlState)
        call.reconcileDriver()
        call.failedBeforeBoxing(false)
        assertSame(failure, call.failure(failure))
    } finally {
        call.finish()
    }
}

private const val OWNED_CUT_HELPER = "org.postgresql.jdbc.KiraOwnedJdbcCut"
private enum class CutDescriptorDefect { MISSING_HELPER, MISSING_DESCRIPTOR, MISSING_RETENTION_DESCRIPTOR, FOREIGN_HELPER, VISIBLE_CONSTRUCTOR, UNMARKED_BRIDGE }

/** Cold denial cases only; does not construct an alternate driver or touch the installed artifact/cache. */
private class CutDescriptorDefectLoader(parent: ClassLoader, private val defect: CutDescriptorDefect) : ClassLoader(parent) {
    private var constructorMutations = 0

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val cut = name == OWNED_CUT_HELPER || name.startsWith("$OWNED_CUT_HELPER\$")
        if (cut && defect === CutDescriptorDefect.MISSING_HELPER) throw ClassNotFoundException(name)
        val loaded = findLoadedClass(name) ?: if (name == "org.postgresql.Driver" || (cut && defect !== CutDescriptorDefect.FOREIGN_HELPER)) {
            val bytes = requireNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class")).use { it.readAllBytes() }
            if (name == OWNED_CUT_HELPER && defect in setOf(CutDescriptorDefect.MISSING_DESCRIPTOR, CutDescriptorDefect.MISSING_RETENTION_DESCRIPTOR)) {
                // Rename exactly this UTF-8 method name, preserving class shape and every other descriptor.
                val target = (if (defect === CutDescriptorDefect.MISSING_RETENTION_DESCRIPTOR) "retentionState" else "recordReturned").toByteArray(Charsets.UTF_8)
                val matches = (0..bytes.size - target.size).filter { at -> target.indices.all { bytes[at + it] == target[it] } }
                assertEquals(1, matches.size)
                bytes[matches.single() + target.lastIndex] = 'X'.code.toByte()
            }
            if (name == "$OWNED_CUT_HELPER\$Opening" &&
                (defect === CutDescriptorDefect.VISIBLE_CONSTRUCTOR || defect === CutDescriptorDefect.UNMARKED_BRIDGE)
            ) {
                mutateOpeningConstructor(bytes)
            }
            defineClass(name, bytes, 0, bytes.size)
        } else {
            super.loadClass(name, false)
        }
        if (resolve) resolveClass(loaded)
        loaded
    }

    fun assertConstructorDefect() {
        if (defect !== CutDescriptorDefect.VISIBLE_CONSTRUCTOR && defect !== CutDescriptorDefect.UNMARKED_BRIDGE) return
        // Resolve the deliberately changed member outside the expected adapter failure: linkage errors do not count.
        val helper = Class.forName(OWNED_CUT_HELPER, false, this)
        val opening = Class.forName("$OWNED_CUT_HELPER\$Opening", false, this)
        val marker = Class.forName("$OWNED_CUT_HELPER\$1", false, this)
        assertSame(this, helper.classLoader)
        assertSame(this, opening.classLoader)
        assertSame(this, marker.classLoader)
        assertSame(helper, opening.declaringClass)
        assertSame(helper, marker.enclosingClass)
        assertEquals(2, opening.declaredConstructors.size)
        val explicit = opening.getDeclaredConstructor(Driver::class.java, Any::class.java)
        val bridge = opening.getDeclaredConstructor(Driver::class.java, Any::class.java, marker)
        assertEquals(if (defect === CutDescriptorDefect.VISIBLE_CONSTRUCTOR) Modifier.PUBLIC else Modifier.PRIVATE, explicit.modifiers)
        assertFalse(explicit.isSynthetic)
        assertEquals(if (defect === CutDescriptorDefect.UNMARKED_BRIDGE) 0 else 0x1000, bridge.modifiers)
        assertEquals(defect !== CutDescriptorDefect.UNMARKED_BRIDGE, bridge.isSynthetic)
        explicit.parameterTypes.forEachIndexed { index, argument -> assertSame(argument, bridge.parameterTypes[index]) }
        assertSame(marker, bridge.parameterTypes.last())
        assertEquals(1, constructorMutations)
    }

    private fun mutateOpeningConstructor(bytes: ByteArray) {
        val unmarkBridge = defect === CutDescriptorDefect.UNMARKED_BRIDGE
        val descriptor = if (unmarkBridge) {
            "(Ljava/sql/Driver;Ljava/lang/Object;Lorg/postgresql/jdbc/KiraOwnedJdbcCut\$1;)V"
        } else {
            "(Ljava/sql/Driver;Ljava/lang/Object;)V"
        }
        val expectedFlags = if (unmarkBridge) 0x1000 else Modifier.PRIVATE
        val changedFlags = if (unmarkBridge) 0 else Modifier.PUBLIC
        assertEquals(0, constructorMutations)
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertEquals(0xCAFEBABE.toInt(), input.readInt())
            input.readUnsignedShort() // minor version
            assertEquals(52, input.readUnsignedShort())
            val utf8 = arrayOfNulls<String>(input.readUnsignedShort())
            var index = 1
            while (index < utf8.size) {
                when (input.readUnsignedByte()) {
                    1 -> utf8[index] = input.readUTF()

                    3, 4 -> input.skipNBytes(4)

                    5, 6 -> {
                        input.skipNBytes(8)
                        index++
                    }

                    7, 8, 16 -> input.skipNBytes(2)

                    9, 10, 11, 12, 18 -> input.skipNBytes(4)

                    15 -> input.skipNBytes(3)

                    else -> error("Unexpected Java8 constant-pool tag")
                }
                index++
            }
            fun skipAttributes() {
                repeat(input.readUnsignedShort()) {
                    input.readUnsignedShort()
                    input.skipNBytes(input.readInt().toLong())
                }
            }
            input.skipNBytes(6) // access flags, this class, superclass
            input.skipNBytes(2L * input.readUnsignedShort())
            repeat(input.readUnsignedShort()) {
                input.skipNBytes(6) // field flags, name, descriptor
                skipAttributes()
            }
            repeat(input.readUnsignedShort()) {
                val flagsOffset = bytes.size - input.available()
                val flags = input.readUnsignedShort()
                val name = utf8[input.readUnsignedShort()]
                val arguments = utf8[input.readUnsignedShort()]
                if (name == "<init>" && arguments == descriptor) {
                    assertEquals(expectedFlags, flags)
                    // Only valid method_info access flags change; descriptors and bytecode remain authentic.
                    bytes[flagsOffset] = (changedFlags ushr 8).toByte()
                    bytes[flagsOffset + 1] = changedFlags.toByte()
                    constructorMutations++
                }
                skipAttributes()
            }
            assertEquals(1, constructorMutations)
        }
    }
}
