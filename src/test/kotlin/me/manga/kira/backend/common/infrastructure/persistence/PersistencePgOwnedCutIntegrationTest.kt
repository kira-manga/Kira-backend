package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
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

/** Requires the qualified private runtime artifact. Missing owned-cut support fails; there is no stock/skip fallback. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PersistencePgOwnedCutIntegrationTest {
    private val database = lazy { PgLifecycleDatabaseFixture().also { it.start() } }

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
            "prepareInvocation" to
                (invocation to listOf(owner, Any::class.java, Any::class.java, Method::class.java, objects, objects, lives, IntArray::class.java)),
            "arm" to (Void.TYPE to listOf(invocation)),
            "invocationState" to (Integer.TYPE to listOf(invocation)),
            "drainState" to (Integer.TYPE to listOf(invocation)),
            "disarm" to (Void.TYPE to listOf(invocation)),
            "actualEnd" to (Void.TYPE to listOf(invocation)),
        )
        val resolved = (ownedCutField(access, "methods") as Map<*, *>).values.map { it as Method }.associateBy { it.name }
        assertEquals(22, resolved.size)
        assertEquals(descriptors.keys, resolved.keys)
        descriptors.forEach { (name, descriptor) ->
            val method = helper.getDeclaredMethod(name, *descriptor.second.toTypedArray())
            assertEquals(method, resolved.getValue(name))
            assertSame(descriptor.first, method.returnType)
            assertTrue(Modifier.isPublic(method.modifiers) && Modifier.isStatic(method.modifiers))
            assertFalse(method.isBridge || method.isSynthetic || Modifier.isAbstract(method.modifiers))
        }
        val verifyConsumerInputs = assertFinalJarConsumerProvenance(
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

    /** Actual positive Classes under the explicit Gate A profile, not hashes of Class.getResource bytes. */
    private fun assertFinalJarConsumerProvenance(driverType: Class<*>, cutTypes: List<Class<*>>): () -> Unit {
        fun input(name: String): String = requireNotNull(System.getProperty("kira.finalJarConsumer.$name")) {
            "The required consumer test needs its explicit final-JAR profile: $name"
        }
        assertEquals("app-29-final-jar-consumer-profile-01", input("profile"))
        val jar = Path.of(input("jar")).toRealPath()
        val checker = Path.of(input("checker")).toRealPath()
        fun sha256(path: Path): String = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path))
            .joinToString("") { "%02x".format(it) }
        val verifyInputs = {
            assertEquals("f1a6dad0aa9ee3288c1a57cb230336fab96ca183b648976d68012caf9413395c", sha256(jar))
            assertEquals("857658c8bdcbff794949a8d9922f88d63ee3751c203c7cd7d21ea97f9e745288", sha256(checker))
        }
        verifyInputs()
        val loader = requireNotNull(driverType.classLoader)
        assertSame(javaClass.classLoader, loader)
        assertSame(Thread.currentThread().contextClassLoader, loader)
        assertEquals("org.postgresql.Driver", driverType.name)
        val witness = linkedMapOf("profile" to input("profile"))
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
        val report = Path.of(input("report"))
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
                verifyConsumerInputs = assertFinalJarConsumerProvenance(driverType, listOf(helper, marker, f.raw.javaClass) + opaque)
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
private enum class CutDescriptorDefect { MISSING_HELPER, MISSING_DESCRIPTOR, FOREIGN_HELPER, VISIBLE_CONSTRUCTOR, UNMARKED_BRIDGE }

/** Cold denial cases only; does not construct an alternate driver or touch the installed artifact/cache. */
private class CutDescriptorDefectLoader(parent: ClassLoader, private val defect: CutDescriptorDefect) : ClassLoader(parent) {
    private var constructorMutations = 0

    override fun loadClass(name: String, resolve: Boolean): Class<*> = synchronized(getClassLoadingLock(name)) {
        val cut = name == OWNED_CUT_HELPER || name.startsWith("$OWNED_CUT_HELPER\$")
        if (cut && defect === CutDescriptorDefect.MISSING_HELPER) throw ClassNotFoundException(name)
        val loaded = findLoadedClass(name) ?: if (name == "org.postgresql.Driver" || (cut && defect !== CutDescriptorDefect.FOREIGN_HELPER)) {
            val bytes = requireNotNull(parent.getResourceAsStream(name.replace('.', '/') + ".class")).use { it.readAllBytes() }
            if (name == OWNED_CUT_HELPER && defect === CutDescriptorDefect.MISSING_DESCRIPTOR) {
                // Rename exactly this UTF-8 method name, preserving class shape and every other descriptor.
                val target = "recordReturned".toByteArray(Charsets.UTF_8)
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
