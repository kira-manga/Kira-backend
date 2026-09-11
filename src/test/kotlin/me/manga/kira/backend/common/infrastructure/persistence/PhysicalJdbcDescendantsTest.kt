package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertArrayEquals
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
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import java.io.IOException
import java.lang.reflect.Proxy
import java.sql.Blob
import java.sql.CallableStatement
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Ref
import java.sql.ResultSet
import java.sql.ResultSetMetaData
import java.sql.SQLException
import java.sql.SQLClientInfoException
import java.sql.Statement
import java.sql.Struct
import java.util.Collections
import java.util.IdentityHashMap
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.withLock
import java.sql.Array as JdbcArray

/** Model graph gates plus four real owned-cut migrations; no Array/JSON/Hikari or artifact qualification claim. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class PhysicalJdbcDescendantsTest {
    private val database = lazy { PgLifecycleDatabaseFixture().also { it.start() } }

    @AfterAll
    fun closeDatabase() {
        if (database.isInitialized()) database.value.close()
    }

    @Test
    fun `array result metadata generated-key and callable return graphs never expose a native parent`() {
        val f = PhysicalJdbcGraphFixture()
        val p = f.connection.prepareStatement("model")
        val a = f.connection.createArrayOf("int4", arrayOf(1))
        val rs = a.resultSet
        assertNotSame(f.nativeArray, a)
        assertSame(f.connection, rs.statement.connection)
        assertSame(f.connection, p.generatedKeys.statement.connection)
        assertSame(f.connection, f.connection.metaData.getTables(null, null, null, null).statement.connection)
        assertSame(f.connection, f.connection.metaData.connection)
        assertEquals(2, p.metaData.columnCount)
        assertEquals(2, p.parameterMetaData.parameterCount)
        val callable = f.connection.prepareCall("model")
        assertSame(a, callable.getArray(1))
        assertSame(a, callable.getObject(1))
        assertNotSame(f.nativeBlob, callable.getBlob(1))
        assertSame(p, p.unwrap(PreparedStatement::class.java))
        assertTrue(p.isWrapperFor(Statement::class.java))
        assertFalse(p.isWrapperFor(Connection::class.java))
        assertThrows<SQLException> { p.unwrap(Connection::class.java) }
        assertTrue(p.toString().contains("redacted"))
        assertEquals(System.identityHashCode(p), p.hashCode())
        assertTrue(p == p)
        assertFalse(p == callable)
        assertEquals(0, f.objectCalls.get())
    }

    @Test
    fun `resource containers struct and ref attributes preserve guards and scalar array types`() {
        val f = PhysicalJdbcGraphFixture()
        val struct = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Struct::class.java)) { _, method, _ ->
            when (method.name) {
                "getAttributes" -> arrayOf<Any>(f.nativeArray, f.nativeBlob)
                "getSQLTypeName" -> "model"
                else -> error("No native Object dispatch.")
            }
        } as Struct
        val ref = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Ref::class.java)) { _, method, _ ->
            if (method.name == "getObject") struct else error("No native Object dispatch.")
        } as Ref
        val resourceSet = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { add(f.nativeBlob) }
        f.returnedObject = arrayOf<Any>(
            struct,
            ref,
            arrayOf(1, 2),
            arrayOf(f.nativePrepared),
            arrayOf(f.nativeCallable),
            arrayOf(f.metadata),
            resourceSet,
        )
        val output = f.connection.prepareStatement("model").executeQuery().getObject(1) as Array<*>
        val guardedStruct = output[0] as Struct
        val guardedRef = output[1] as Ref
        assertSame(guardedStruct, guardedRef.getObject())
        assertNotSame(struct, guardedStruct)
        assertNotSame(f.nativeArray, guardedStruct.attributes[0])
        assertTrue(guardedStruct.attributes[0] is JdbcArray)
        assertTrue(guardedStruct.attributes[1] is Blob)
        assertEquals(arrayOf(1, 2).javaClass, output[2]!!.javaClass)
        assertEquals(PreparedStatement::class.java, output[3]!!.javaClass.componentType)
        assertEquals(CallableStatement::class.java, output[4]!!.javaClass.componentType)
        assertEquals(ResultSetMetaData::class.java, output[5]!!.javaClass.componentType)
        assertTrue(output[6] is Set<*>)
        assertSame(guardedStruct.attributes[1], (output[6] as Set<*>).single())
        assertEquals(0, f.objectCalls.get())
    }

    @Test
    fun `same owner array adaptation is private and eager across independent root call identities`() {
        val f = PhysicalJdbcGraphFixture()
        val array = f.connection.createArrayOf("int4", arrayOf(1, 2))
        val p = f.connection.prepareStatement("model")
        p.setArray(1, array)
        assertSame(f.nativeArray, f.suppliedArray)
        p.setObject(1, array)
        p.setObject(1, array, java.sql.Types.ARRAY)
        p.setObject(1, array, java.sql.Types.ARRAY, 0)
        assertSame(f.nativeArray, f.suppliedArray)
        array.free()
        assertTrue(p.execute(), "A successful eager Array binding no longer depends on the Array facade.")
        assertEquals(1, f.arrayFreeCalls.get())
        assertEquals(0, f.objectCalls.get())
    }

    @Test
    fun `new freed or cross-physical array is refused before either input or consumer native calls`() {
        val f = PhysicalJdbcGraphFixture()
        val other = PhysicalJdbcGraphFixture()
        val p = f.connection.prepareStatement("model")
        val foreign = other.connection.createArrayOf("int4", arrayOf(1))
        assertThrows<SQLException> { p.setArray(1, foreign) }
        assertEquals(0, f.setterCalls.get())
        assertEquals(0, other.arrayReadCalls.get())
        val own = f.connection.createArrayOf("int4", arrayOf(1))
        own.free()
        assertThrows<SQLException> { p.setObject(1, own) }
        assertEquals(0, f.setterCalls.get())
        assertEquals(0, f.arrayReadCalls.get())
    }

    @Test
    fun `real private pool rotates fresh borrower roots on one physical session and denies stale graphs before native use`() =
        withOwnedCutPool(database.value) { f ->
            val first = f.pool.connection
            val lease = ownedPoolLease(first)
            assertSame(lease, ownedCutField(lease.state, "lease"), "Committed state retains even a lease whose acquisition tail cannot deliver it.")
            val entry = f.entry(first)
            val root = ownedPoolRoot(lease)
            val pid = ownedPoolScalar(first, "SELECT pg_backend_pid()")
            val metadata = first.metaData
            val metadataLife = ownedPoolLife(metadata)
            val staleArray = first.createArrayOf("int4", arrayOf(1, 2))
            val staleInput = ownedCutInput(first)
            val staleStatement = first.prepareStatement("SELECT 1")
            val invokedAbort = AtomicInteger()
            try {
                staleArray.free()
                staleInput.close()
                staleStatement.close()
                first.close()
                assertTrue(lease.state.epoch.sealedAndEnded())
                assertFalse(entry.jdbc.currentPoolState(lease.state))
                assertFalse(entry.retirementRequested.get())
                assertThrows<SQLClientInfoException> { first.setClientInfo("ApplicationName", "stale") }
                assertThrows<SQLClientInfoException> { first.clientInfo = Properties() }
                assertThrows<SQLException> { staleStatement.cancel() }
                assertThrows<SQLException> { staleStatement.execute() }
                assertThrows<IOException> { staleInput.read() }
                staleStatement.close() // Duplicate facade close cannot issue another native first close.
                first.close()
                first.abort(Executor { invokedAbort.incrementAndGet() })
                assertEquals(0, invokedAbort.get())

                FactoryWorkerTestScope().use { callers ->
                    val successor = callers.launch {
                        f.pool.connection.use { second ->
                            val next = ownedPoolLease(second)
                            assertSame(entry, f.entry(second))
                            assertNotSame(lease.state.epoch, next.state.epoch)
                            assertNotSame(root.cell, ownedPoolRoot(next).cell)
                            assertEquals(pid, ownedPoolScalar(second, "SELECT pg_backend_pid()"))
                            assertSame(metadataLife.cell, ownedPoolLife(second.metaData).cell, "Passive physical Life is canonical, not reset on checkout.")
                            assertThrows<SQLException> { metadata.databaseProductName }
                            second.prepareStatement("SELECT ?::int[]").use { statement ->
                                assertThrows<SQLException> { statement.setArray(1, staleArray) }
                                assertThrows<SQLException> { statement.setObject(1, arrayOf(staleArray)) }
                            }
                            assertEquals(7, ownedPoolScalar(second, "SELECT 7"))
                        }
                        true
                    }
                    assertTrue(successor.join())
                }
                assertEquals(0L, f.lifecycle.actorSnapshot().futureLeaseEntries)
            } finally {
                first.close()
            }
        }

    @Test
    fun `real lease preserves Properties client info defaults and declared stale and foreign failure shapes`() = withOwnedCutPool(database.value) { f ->
        val connection = f.pool.connection
        try {
            val defaults = Properties().apply { setProperty("ApplicationName", "kira-default-client") }
            val properties = Properties(defaults)
            connection.clientInfo = properties
            assertEquals("kira-default-client", connection.getClientInfo("ApplicationName"))
            assertEquals("kira-default-client", connection.clientInfo.getProperty("ApplicationName"))
            assertTrue(properties.isEmpty(), "Upper adaptation must not mutate the caller's Properties/defaults.")
            defaults.setProperty("ApplicationName", "kira-shadowed-default-client")
            properties["ApplicationName"] = 7 // Properties.getProperty still falls back to its String default.
            connection.clientInfo = properties
            assertEquals("kira-shadowed-default-client", connection.getClientInfo("ApplicationName"))
            assertEquals(7, properties["ApplicationName"], "The caller's explicit non-String entry is not overwritten.")
            FactoryWorkerTestScope().use { callers ->
                assertTrue(callers.launch {
                    assertThrows<SQLClientInfoException> { connection.clientInfo = properties }
                    assertThrows<SQLClientInfoException> { connection.setClientInfo("ApplicationName", "foreign") }
                    true
                }.join())
            }
        } finally {
            connection.close()
        }
        assertThrows<SQLClientInfoException> { connection.clientInfo = Properties() }
    }

    @Test
    fun `deferred current and queued inputs have different lifetimes and rejected preflight is not drain`() = withOwnedCutConnection(database.value) { f ->
        f.sql("CREATE TEMP TABLE kira_cut_queue (payload bytea)")
        f.connection.prepareStatement("INSERT INTO kira_cut_queue VALUES (?)").use { p ->
            ownedCutInput(f.connection).use { input ->
                val native = ownedCutNative(p)
                val state = requireNotNull(ownedCutField(native, "kira"))
                val current = requireNotNull(ownedCutField(native, "preparedParameters"))
                val life = requireNotNull(PhysicalJdbcDescendants.knownGuard(input)?.driverLife)
                p.setBinaryStream(1, input, 8)
                val binding = ownedCutSingleBinding(current)
                assertSame(input, ownedCutField(ownedCutSingleValue(current), "stream"))
                assertSame(life.cell, (ownedCutField(binding, "dependencies") as Array<*>).single())
                p.addBatch()
                val queued = requireNotNull((ownedCutField(native, "batchParameters") as List<*>).single())
                val queuedImage = requireNotNull(ownedCutField(state, "parameters"))
                assertNotSame(current, queued)
                assertSame(binding, ownedCutSingleBinding(queued))

                p.setBytes(1, byteArrayOf(7)) // Independent bytes replace C, not the known-Life copy in Q.
                input.close()
                assertEquals(1, p.executeUpdate(), "Current execution must not validate the stale queued input.")
                repeat(2) {
                    val refused = ownedCutExecuteBatch(p) { _, invocation, output, failure ->
                        assertTrue(failure is SQLException)
                        assertNull(output)
                        assertEquals(6, invocation.owner.root.access.drainState(invocation)) // Actual native PRE_ENDED.
                        assertSame(queued, (ownedCutField(native, "batchParameters") as List<*>).single())
                        assertSame(queuedImage, ownedCutField(state, "parameters"))
                        assertSame(queued, (ownedCutBatchArrays(invocation)["parameters"] as Array<*>).single())
                    }
                    assertEquals(6, refused.owner.root.access.drainState(refused))
                    assertTrue(ownedCutBatchArrays(refused).values.all { it == null })
                }
                assertEquals(1, f.scalar("SELECT count(*) FROM kira_cut_queue"))
                assertFalse(f.epoch.poisoned())
                p.clearBatch()
                assertTrue((ownedCutField(native, "batchParameters") as List<*>).isEmpty())
                assertNull(ownedCutField(state, "parameters"))
                assertNull(ownedCutField(state, "queries"))
                ownedCutExecuteBatch(p) { _, invocation, output, failure ->
                    assertNull(failure)
                    assertArrayEquals(intArrayOf(), output)
                    assertEquals(6, invocation.owner.root.access.drainState(invocation))
                }
                assertSame(current, ownedCutField(native, "preparedParameters"))
                assertEquals(1, p.executeUpdate())
                assertEquals(2, f.scalar("SELECT count(*) FROM kira_cut_queue"))
            }
        }
    }

    @Test
    fun `successful batch drain releases queued references but never erases still current parameters`() = withOwnedCutConnection(database.value) { f ->
        f.sql("CREATE TEMP TABLE kira_cut_active (payload bytea)")
        f.connection.prepareStatement("INSERT INTO kira_cut_active VALUES (?)").use { p ->
            ownedCutInput(f.connection).use { input ->
                val native = ownedCutNative(p)
                val state = requireNotNull(ownedCutField(native, "kira"))
                val current = requireNotNull(ownedCutField(native, "preparedParameters"))
                p.setBinaryStream(1, input, 8)
                val currentBinding = ownedCutSingleBinding(current)
                p.addBatch()
                val queued = requireNotNull((ownedCutField(native, "batchParameters") as List<*>).single())
                var next: Any? = null
                var nextImage: Any? = null
                var replacement: Any? = null
                val outer = ownedCutExecuteBatch(p) { call, invocation, output, failure ->
                    assertNull(failure)
                    assertArrayEquals(intArrayOf(1), output)
                    assertTrue(call.actualUnended(ownedCutField(call, "context") as PersistenceJdbcGuardContext))
                    assertTrue(f.epoch.foregroundActive())
                    assertEquals(5, invocation.owner.root.access.drainState(invocation)) // Native COMMITTED, not method return.
                    val active = ownedCutBatchArrays(invocation)
                    active.values.forEach { assertNotNull(it) }
                    assertSame(queued, (active["parameters"] as Array<*>).single())
                    assertTrue((ownedCutField(native, "batchParameters") as List<*>).isEmpty())
                    assertNull(ownedCutField(state, "parameters"))
                    assertSame(currentBinding, ownedCutSingleBinding(current))

                    input.close() // Nested real cleanup while the outer GuardCall still owns A.
                    assertThrows<SQLException> { p.executeUpdate() }
                    assertFalse(f.epoch.poisoned())
                    p.setBytes(1, byteArrayOf(4))
                    p.addBatch()
                    next = requireNotNull((ownedCutField(native, "batchParameters") as List<*>).single())
                    nextImage = requireNotNull(ownedCutField(state, "parameters"))
                    p.setBytes(1, byteArrayOf(5))
                    replacement = ownedCutSingleBinding(current)
                    assertNotSame(replacement, ownedCutSingleBinding(requireNotNull(next)))
                    assertSame(next, (ownedCutField(native, "batchParameters") as List<*>).single())
                    val afterNested = ownedCutBatchArrays(invocation)
                    active.forEach { (name, retained) -> assertSame(retained, afterNested[name]) }
                    assertEquals(5, invocation.owner.root.access.drainState(invocation))
                }
                assertFalse(f.epoch.foregroundActive())
                assertEquals(5, outer.owner.root.access.drainState(outer))
                assertTrue(ownedCutBatchArrays(outer).values.all { it == null }, "Only outer actualEnd releases its A.")
                assertSame(next, (ownedCutField(native, "batchParameters") as List<*>).single())
                assertSame(nextImage, ownedCutField(state, "parameters"))
                assertSame(current, ownedCutField(native, "preparedParameters"))
                assertSame(replacement, ownedCutSingleBinding(current))
                assertEquals(1, f.scalar("SELECT count(*) FROM kira_cut_active"))
                assertArrayEquals(intArrayOf(1), p.executeBatch())
                assertEquals(0, p.executeBatch().size)
                assertEquals(1, p.executeUpdate())
                assertEquals(3, f.scalar("SELECT count(*) FROM kira_cut_active"))
            }
        }
    }

    @Test
    fun `unknown length stream conversion is eager while a failed setter retains its actual partial binding`() = withOwnedCutConnection(database.value) { f ->
        f.sql("CREATE TEMP TABLE kira_cut_streams (payload bytea)")
        f.connection.prepareStatement("INSERT INTO kira_cut_streams VALUES (?)").use { p ->
            val current = requireNotNull(ownedCutField(ownedCutNative(p), "preparedParameters"))
            ownedCutInput(f.connection).use { eager ->
                p.setBinaryStream(1, eager)
                assertEquals(0, eager.available())
                assertNull(ownedCutField(ownedCutSingleValue(current), "stream"))
                assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), ownedCutField(ownedCutSingleValue(current), "rawData") as ByteArray)
                assertTrue((ownedCutField(ownedCutSingleBinding(current), "dependencies") as Array<*>).isEmpty())
                eager.close()
                assertEquals(1, p.executeUpdate())
            }
            ownedCutInput(f.connection).use { deferred ->
                val before = ownedCutSingleBinding(current)
                assertOwnedCutSetterSqlIdentity(p, deferred)
                assertSame(before, ownedCutSingleBinding(current), "The real invalid-index SQL error precedes any native store.")
                assertFalse(f.epoch.poisoned(), "Ordinary setter SQL errors are not blanket eviction.")

                val flags = current.javaClass.getDeclaredField("flags").apply { check(trySetAccessible()) }
                val original = flags.get(current)
                try {
                    flags.set(current, byteArrayOf())
                    assertThrows<ArrayIndexOutOfBoundsException> { p.setBinaryStream(1, deferred, 8) }
                } finally {
                    flags.set(current, original)
                }
                // This fault is AFTER both the actual value and its binding metadata were stored.
                // It is not a torn raw-store/publication window, nor evidence that every failed setter rolls back.
                val stored = ownedCutSingleValue(current)
                val binding = ownedCutSingleBinding(current)
                val life = requireNotNull(PhysicalJdbcDescendants.knownGuard(deferred)?.driverLife)
                assertNotSame(before, binding)
                assertSame(deferred, ownedCutField(stored, "stream"))
                assertNull(ownedCutField(stored, "rawData"))
                assertSame(stored, ownedCutField(binding, "stored"))
                assertSame(life.cell, (ownedCutField(binding, "dependencies") as Array<*>).single())
                assertTrue(ownedCutField(binding, "complete") as Boolean)
                val metadata = requireNotNull(ownedCutField(current, "kira"))
                assertEquals(0, ownedCutField(metadata, "changing"))
                assertFalse(ownedCutField(metadata, "unknown") as Boolean)
                assertFalse(f.epoch.poisoned())
                deferred.close()
                assertThrows<SQLException> { p.executeUpdate() }
                assertEquals(1, f.scalar("SELECT count(*) FROM kira_cut_streams"))
                p.setBytes(1, byteArrayOf(1))
                assertTrue((ownedCutField(ownedCutSingleBinding(current), "dependencies") as Array<*>).isEmpty())
                assertEquals(1, p.executeUpdate())
                assertEquals(2, f.scalar("SELECT count(*) FROM kira_cut_streams"))
                assertEquals(0, f.root.access.rootState(f.root))
            }
        }
    }

    @Test
    fun `row aliases use base column identity and new stale input precedes even label resolution`() = withOwnedCutConnection(database.value) { f ->
        f.sql("CREATE TEMP TABLE kira_cut_rows (id integer PRIMARY KEY, payload int[] CHECK (payload[1] > 0), retained int[])")
        f.sql("INSERT INTO kira_cut_rows VALUES (1, '{1}', '{1}')")
        f.connection.createStatement(ResultSet.TYPE_SCROLL_INSENSITIVE, ResultSet.CONCUR_UPDATABLE).use { statement ->
            statement.executeQuery("SELECT id, payload, payload AS payload_alias, retained FROM kira_cut_rows").use { rs ->
                assertTrue(rs.next())
                val native = ownedCutNative(rs)
                val state = requireNotNull(ownedCutField(native, "kira"))
                val stale = f.connection.createArrayOf("int4", arrayOf(1))
                stale.free()
                assertNull(ownedCutField(native, "columnNameIndexMap"))
                assertNull(ownedCutField(native, "rsMetaData"))
                assertThrows<SQLException> { rs.updateArray("payload_alias", stale) }
                assertNull(ownedCutField(native, "columnNameIndexMap"), "NEW stale input precedes real lazy label resolution.")
                assertNull(ownedCutField(native, "rsMetaData"), "NEW stale input precedes real metadata creation.")

                val old = f.connection.createArrayOf("int4", arrayOf(-2))
                try {
                    val retained = f.connection.createArrayOf("int4", arrayOf(5))
                    var replacement: JdbcArray? = null
                    var otherReplacement: JdbcArray? = null
                    try {
                        rs.updateArray(2, old)
                        rs.updateArray(4, retained)
                        val actual = ownedCutField(native, "updateValues") as Map<*, *>
                        fun rows(): Map<*, *> = ownedCutField(state, "rows") as Map<*, *>
                        val otherRow = requireNotNull(rows()["retained"])
                        assertEquals(setOf("payload", "retained"), actual.keys)
                        assertSame(ownedCutNative(old), actual["payload"])
                        assertSame(ownedCutNative(retained), actual["retained"])

                        fun assertHiddenTransfer(before: List<Any>) {
                            val child = ownedCutNativeChildren(f.raw).single { candidate ->
                                before.none { it === candidate } && ownedCutField(candidate, "receiver") is PreparedStatement
                            }
                            assertTrue(ownedCutField(child, "factoryReturned") as Boolean)
                            assertEquals(PersistencePgOwnedCutAccess.FIRST_RETURNED, ownedCutField(requireNotNull(ownedCutField(child, "life")), "first"))
                            assertFalse(ownedCutField(child, "counted") as Boolean)
                            val parameters = requireNotNull(ownedCutField(requireNotNull(ownedCutField(child, "receiver")), "preparedParameters"))
                            val values = ownedCutField(parameters, "paramValues") as Array<*>
                            val bindings = ownedCutField(requireNotNull(ownedCutField(parameters, "kira")), "bindings") as Array<*>
                            assertEquals(3, values.size) // Two effective base columns plus the actual primary key.
                            bindings.forEachIndexed { index, binding ->
                                val bound = requireNotNull(binding)
                                assertSame(values[index], ownedCutField(bound, "stored"))
                                assertTrue(
                                    (ownedCutField(bound, "dependencies") as Array<*>).isEmpty(),
                                    "Inner Array conversion is eager; outer row ownership is separate.",
                                )
                            }
                        }

                        val beforeFailure = ownedCutNativeChildren(f.raw)
                        ownedCutUpdateRow(rs) { _, invocation, failure ->
                            assertTrue(failure is SQLException)
                            assertEquals("23514", (failure as SQLException).sqlState) // Real server CHECK failure after hidden binding/SQL.
                            assertTrue(f.epoch.foregroundActive())
                            assertHiddenTransfer(beforeFailure)
                            assertEquals(0, invocation.owner.root.access.invocationState(invocation))
                            assertSame(otherRow, rows()["retained"])
                            assertSame(ownedCutNative(old), actual["payload"])
                        }
                        assertFalse(f.epoch.poisoned(), "Ordinary row SQL failure is not blanket eviction or row rollback evidence.")
                        assertEquals(1, f.scalar("SELECT payload[1] FROM kira_cut_rows"))
                        old.free()
                        assertThrows<SQLException> { rs.updateRow() }
                        assertEquals(2, actual.size)

                        val fresh = f.connection.createArrayOf("int4", arrayOf(3)).also { replacement = it }
                        rs.updateArray("payload_alias", fresh) // Same actual base-column key, not a second alias bucket.
                        assertEquals(setOf("payload", "retained"), actual.keys)
                        assertSame(ownedCutNative(fresh), actual["payload"])
                        val replacedBinding = requireNotNull(ownedCutField(requireNotNull(rows()["payload"]), "binding"))
                        val freshLife = requireNotNull(PhysicalJdbcDescendants.knownGuard(fresh)?.driverLife)
                        assertSame(freshLife.cell, (ownedCutField(replacedBinding, "dependencies") as Array<*>).single())
                        assertSame(otherRow, rows()["retained"])
                        val retainedLife = requireNotNull(PhysicalJdbcDescendants.knownGuard(retained)?.driverLife)
                        val otherBinding = requireNotNull(ownedCutField(otherRow, "binding"))
                        assertSame(retainedLife.cell, (ownedCutField(otherBinding, "dependencies") as Array<*>).single())
                        retained.free()
                        assertThrows<SQLException> { rs.updateRow() } // Replacing payload must not erase the other base column's Life.
                        assertEquals(1, f.scalar("SELECT retained[1] FROM kira_cut_rows"))
                        val otherFresh = f.connection.createArrayOf("int4", arrayOf(4)).also { otherReplacement = it }
                        rs.updateArray("retained", otherFresh)
                        val beforeSuccess = ownedCutNativeChildren(f.raw)
                        ownedCutUpdateRow(rs) { _, _, failure ->
                            assertNull(failure)
                            assertHiddenTransfer(beforeSuccess)
                            assertTrue(actual.isEmpty(), "The actual native row clear, not wrapper return, releases stored values.")
                            assertTrue(rows().isEmpty())
                        }
                        assertEquals(3, f.scalar("SELECT payload[1] FROM kira_cut_rows"))
                        assertEquals(4, f.scalar("SELECT retained[1] FROM kira_cut_rows"))
                        assertEquals(0, f.root.access.rootState(f.root))
                    } finally {
                        otherReplacement?.free()
                        replacement?.free()
                        retained.free()
                    }
                } finally {
                    old.free()
                }
            }
        }
    }

    @Test
    fun `first close failure is sticky and cleanup remains admitted after business stop but not terminal seal`() {
        val f = PhysicalJdbcGraphFixture()
        val p = f.connection.prepareStatement("model")
        val failure = PhysicalHostileFailure()
        f.closeFailure = failure
        f.stopBusiness()
        assertThrows<SQLException> { p.execute() }
        assertThrows<SQLException> { p.close() }
        p.close()
        assertEquals(1, f.statementCloseCalls.get())
        assertTrue(f.prepared.epoch.poisoned())
        assertEquals(0, failure.reads.get())
        assertFalse(f.prepared.epoch.foregroundActive())

        val sealed = PhysicalJdbcGraphFixture()
        val untouched = sealed.connection.createStatement()
        sealed.seal()
        assertThrows<SQLException> { untouched.close() }
        assertEquals(0, sealed.statementCloseCalls.get())
    }

    @Test
    fun `savepoint uses private exact owner structural adaptation`() {
        val f = PhysicalJdbcGraphFixture()
        val savepoint = f.connection.setSavepoint()
        assertEquals(1, savepoint.savepointId)
        f.connection.rollback(savepoint)
        f.connection.releaseSavepoint(savepoint)
        assertEquals(2, f.savepointCalls.get())
        assertEquals(0, f.objectCalls.get())
    }

    @Test
    fun `ordinary children and same-thread reentry have no small universal cap`() {
        val f = PhysicalJdbcGraphFixture()
        val children = List(96) { f.connection.createStatement() }
        children.forEach { assertSame(f.connection, it.connection) }
        val p = f.connection.prepareStatement("model")
        var depth = 0
        f.executeHook = {
            depth++
            try {
                if (depth < 16) assertTrue(p.execute())
            } finally {
                depth--
            }
        }
        assertTrue(p.execute())
        assertEquals(16, f.executeCalls.get())
        assertEquals(0, depth)
        assertFalse(f.prepared.epoch.foregroundActive())
        children.forEach(Statement::close)
    }

    @Test
    fun `descendant actual completion does not need G and foreign business stays refused`() = FactoryWorkerTestScope().use { scope ->
        val f = PhysicalJdbcGraphFixture()
        val p = f.connection.prepareStatement("model")
        assertTrue(
            scope.launch {
                assertThrows<SQLException> { p.execute() }
                true
            }.join(),
        )
        assertEquals(0, f.executeCalls.get())
        val held = scope.gate()
        val holder = scope.launch {
            f.ownership.binding.ledger.lock.withLock { held.hold() }
            true
        }
        held.awaitEntered()
        assertTrue(p.execute())
        assertFalse(f.prepared.epoch.foregroundActive())
        held.release()
        assertTrue(holder.join())
    }

    @Test
    fun `resource output is escrowed before wrapping failure and actual extent includes wrapping`() {
        val f = PhysicalJdbcGraphFixture()
        val hostile = PhysicalHostileFailure()
        val rawOutput = object : AbstractMap<Any, Any>() {
            override val entries: Set<Map.Entry<Any, Any>> get() {
                assertTrue(f.prepared.epoch.foregroundActive())
                throw hostile
            }
        }
        f.returnedObject = rawOutput
        val rs = f.connection.prepareStatement("model").executeQuery()
        assertThrows<SQLException> { rs.getObject(1) }
        assertTrue(f.prepared.epoch.poisoned())
        assertFalse(f.prepared.epoch.foregroundActive())
        assertEquals(0, hostile.reads.get())
        val node = requireNotNull(PhysicalJdbcDescendants.knownGuard(rs))
        val graph = node.javaClass.getDeclaredField("graph").apply { isAccessible = true }.get(node) as PhysicalJdbcDescendants
        val failed = graph.context.javaClass.getDeclaredField("failedOutputs").apply { isAccessible = true }.get(graph.context) as AtomicReference<*>
        val call = requireNotNull(failed.get())
        val retained = call.javaClass.getDeclaredField("output").apply { isAccessible = true }.get(call)
        assertSame(rawOutput, retained)
    }

    @Test
    fun `first stream close failure uses IOException declaration without retry or hostile diagnostics`() {
        val f = PhysicalJdbcGraphFixture()
        val input = f.connection.createBlob().binaryStream
        val failure = PhysicalHostileFailure()
        f.probes.last().closeFailure = failure
        assertThrows<IOException> { input.close() }
        input.close()
        assertEquals(1, f.probes.last().closes.get())
        assertEquals(0, failure.reads.get())
        assertTrue(f.prepared.epoch.poisoned())
        assertFalse(f.prepared.epoch.foregroundActive())
    }
}
