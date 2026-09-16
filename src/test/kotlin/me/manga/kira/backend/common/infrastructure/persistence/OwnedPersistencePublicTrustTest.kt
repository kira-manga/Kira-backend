package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@EnabledOnOs(OS.LINUX, OS.MAC)
class OwnedPersistencePublicTrustTest {
    @TempDir
    lateinit var temporary: Path

    @Test
    fun `root owns exact captured public bytes in an exclusive protected generation before use`() {
        val original = VersionBoundPersistenceTestInputs.pem()
        val expected = original.copyOf()
        val configuration = configuration(original)
        val root = configuration.createRoot()
        val file = trustPath(root)
        assertFalse(Files.exists(file.parent))
        original.fill(0)
        assertEquals(PersistenceLifecycleActivation.FAILED, root.start())
        assertEquals(PersistencePublicTrustPreparation.READY, root.preparePublicTrust())
        assertEquals(PersistencePublicTrustPreparation.READY, root.preparePublicTrust())
        assertFalse(root.snapshot().ordinaryReady)
        assertArrayEquals(expected, Files.readAllBytes(file))
        assertEquals(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(file.parent))
        assertEquals(PosixFilePermissions.fromString("r--------"), Files.getPosixFilePermissions(file))
        assertEquals(sha256(expected), configuration.descriptor.publicTrustSha256)
        root.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        assertFalse(Files.exists(file.parent))
    }

    @Test
    fun `only explicit strong root-wide drain releases trust and observers never do filesystem cleanup`() {
        val root = preparedRoot()
        val file = trustPath(root)
        assertEquals(PersistencePublicTrustRelease.RETAINED, root.releasePublicTrustAfterShutdown())
        root.requestDeletionShutdown()
        root.requestCatalogCoordinatorShutdown()
        assertEquals(PersistenceLifecycleObservation.DELETION_LOCAL_ENDED, root.deletionShutdownObservation())
        assertEquals(PersistenceLifecycleObservation.CATALOG_COORDINATOR_LOCAL_ENDED, root.catalogCoordinatorShutdownObservation())
        assertEquals(PersistencePublicTrustRelease.RETAINED, root.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(file))
        root.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, root.shutdownObservation())
        assertTrue(Files.exists(file))
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        assertEquals(PersistencePublicTrustPreparation.REFUSED, root.preparePublicTrust())
        assertEquals(PersistenceLifecycleActivation.CLOSED, root.start())
        assertFalse(Files.exists(file.parent))
    }

    @Test
    fun `a different conclusively drained root cannot release this generation`() {
        val root = preparedRoot()
        val other = configuration(parent = protectedParent("other")).createRoot()
        other.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, other.shutdownObservation())
        assertEquals(PersistencePublicTrustRelease.RELEASED, other.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(trustPath(root)))
        assertEquals(PersistencePublicTrustRelease.RETAINED, root.releasePublicTrustAfterShutdown())
        root.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
    }

    @Test
    fun `weak evidence and failure in any role retain trust despite otherwise sealed inert actors`() {
        repeat(6) { index ->
            val root = preparedRoot(protectedParent("negative-$index"))
            val participant = listOf(root.ordinary, root.deletion, root.catalogCoordinator)[index % 3]
            val completion = binding(participant).completion
            // Negative fault injection only: no manufactured positive drain and no actual driver/thread execution.
            if (index < 3) completion.weakEvidence.set(true) else completion.cleanupFailure.set(true)
            root.requestShutdown()
            val expected = if (index < 3) PersistenceLifecycleObservation.DRIVER_CONTRACT_ONLY_ENDED else PersistenceLifecycleObservation.UNKNOWN
            assertEquals(expected, root.shutdownObservation())
            assertEquals(PersistencePublicTrustRelease.RETAINED, root.releasePublicTrustAfterShutdown())
            assertTrue(Files.exists(trustPath(root)))
        }
    }

    @Test
    fun `an unresolved scanner start is not a drain even with no physical records`() {
        val root = preparedRoot()
        val scannerField = PersistenceJdbcDriverRoot::class.java.getDeclaredField("scanner").apply { check(trySetAccessible()) }
        val scanner = scannerField.get(root) as PersistenceRetainedPlatformThread
        val startField = PersistenceRetainedPlatformThread::class.java.getDeclaredField("start").apply { check(trySetAccessible()) }

        @Suppress("UNCHECKED_CAST")
        val start = startField.get(scanner) as AtomicReference<PersistenceThreadStartPhase>
        start.set(PersistenceThreadStartPhase.CLAIMED) // Negative model injection; no thread was actually started.
        root.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.PENDING, root.shutdownObservation())
        assertEquals(PersistencePublicTrustRelease.RETAINED, root.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(trustPath(root)))
    }

    @Test
    fun `prepare versus permanent shutdown and release cannot recreate a disposed generation`() {
        val callers = Executors.newFixedThreadPool(2)
        try {
            repeat(8) { index ->
                val root = configuration(parent = protectedParent("race-$index")).createRoot()
                val start = CountDownLatch(1)
                val preparing = callers.submit<PersistencePublicTrustPreparation> {
                    check(start.await(5, TimeUnit.SECONDS))
                    root.preparePublicTrust()
                }
                val releasing = callers.submit<PersistencePublicTrustRelease> {
                    check(start.await(5, TimeUnit.SECONDS))
                    root.requestShutdown()
                    root.releasePublicTrustAfterShutdown()
                }
                start.countDown()
                assertTrue(preparing.get(5, TimeUnit.SECONDS) in setOf(PersistencePublicTrustPreparation.READY, PersistencePublicTrustPreparation.REFUSED))
                assertEquals(PersistencePublicTrustRelease.RELEASED, releasing.get(5, TimeUnit.SECONDS))
                assertEquals(PersistencePublicTrustPreparation.REFUSED, root.preparePublicTrust())
                assertFalse(Files.exists(trustPath(root).parent))
            }
        } finally {
            callers.shutdownNow()
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `replacement and unrelated children are never deleted as if a pathname proved ownership`() {
        val replaced = preparedRoot(protectedParent("replaced"))
        val replacedPath = trustPath(replaced)
        val retainedOriginal = replacedPath.resolveSibling("original.pem")
        Files.move(replacedPath, retainedOriginal)
        Files.write(replacedPath, VersionBoundPersistenceTestInputs.pem())
        replaced.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.FAILED, replaced.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(replacedPath))
        assertTrue(Files.exists(retainedOriginal))

        val extra = preparedRoot(protectedParent("extra"))
        val extraPath = trustPath(extra)
        val unexpected = extraPath.resolveSibling("unowned-child")
        Files.writeString(unexpected, "fixture")
        extra.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.FAILED, extra.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(unexpected))
        assertFalse(Files.exists(extraPath))
        assertEquals(PersistencePublicTrustPreparation.REFUSED, extra.preparePublicTrust())
        Files.delete(unexpected)
        assertEquals(PersistencePublicTrustRelease.RELEASED, extra.releasePublicTrustAfterShutdown())
    }

    @Test
    fun `permissive or symlinked parents fail before material creation and expose no filesystem exception`() {
        val permissive = protectedParent("permissive")
        Files.setPosixFilePermissions(permissive, PosixFilePermissions.fromString("rwxr-xr-x"))
        val real = protectedParent("real")
        val alias = temporary.toRealPath().resolve("alias")
        Files.createSymbolicLink(alias, real)
        val inner = real.resolve("inner")
        Files.createDirectory(inner, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        listOf(permissive, alias, alias.resolve("inner")).forEach { parent ->
            val root = configuration(parent = parent).createRoot()
            assertEquals(PersistencePublicTrustPreparation.FAILED, root.preparePublicTrust())
            assertFalse(Files.exists(trustPath(root).parent))
            assertEquals(PersistenceLifecycleActivation.FAILED, root.start())
            root.requestShutdown()
            assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())
        }
    }

    @Test
    fun `ambiguous exclusive directory creation retains rather than removing an existing object`() {
        val root = configuration().createRoot()
        val directory = trustPath(root).parent
        Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val sentinel = directory.resolve("foreign")
        Files.writeString(sentinel, "fixture")
        assertEquals(PersistencePublicTrustPreparation.FAILED, root.preparePublicTrust())
        root.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.FAILED, root.releasePublicTrustAfterShutdown())
        assertTrue(Files.exists(sentinel))
    }

    @Test
    fun `byte and certificate count bounds are exact and only complete certificate PEM is accepted`() {
        val pem = VersionBoundPersistenceTestInputs.pem()
        val padded = pem + ByteArray(OwnedPersistencePublicTrust.MAX_PEM_BYTES - pem.size) { 32 }
        assertEquals(padded.size, configuration(padded).descriptor.publicTrustByteCount)
        val chain = List(OwnedPersistencePublicTrust.MAX_CERTIFICATES) { pem.toString(Charsets.US_ASCII) }.joinToString("").toByteArray()
        assertEquals(16, configuration(chain).descriptor.publicTrustCertificateCount)
        val crlf = pem.toString(Charsets.US_ASCII).replace("\n", "\r\n").toByteArray()
        assertNotEquals(configuration(pem).descriptor.publicTrustSha256, configuration(crlf).descriptor.publicTrustSha256)
        val invalid = listOf(
            byteArrayOf(), byteArrayOf(0), byteArrayOf(0x80.toByte()), " \n".toByteArray(), padded + byteArrayOf(32), chain + pem,
            "-----BEGIN PRIVATE KEY-----\nAA==\n-----END PRIVATE KEY-----\n".toByteArray(),
            pem + "unparsed-tail".toByteArray(), "prefix\n".toByteArray() + pem,
            "-----BEGIN CERTIFICATE-----\nAA==\n-----END CERTIFICATE-----\n".toByteArray(),
            pem.toString(Charsets.US_ASCII).replace("MIIF", "%%%%").toByteArray(),
        )
        invalid.forEach { bytes ->
            val failure = assertThrows(PersistenceBoundaryException::class.java) { configuration(bytes) }
            assertNull(failure.cause)
            assertTrue(failure.suppressed.isEmpty())
            assertFalse(failure.toString().contains("PRIVATE KEY"))
        }
    }

    @Test
    fun `phase permits and F G ownership locks forbid explicit material work`() {
        val root = configuration().createRoot()
        val permit = LocalPersistencePermit {}
        try {
            assertThrows(PersistencePhaseException::class.java) { root.preparePublicTrust() }
            root.requestShutdown()
            assertThrows(PersistencePhaseException::class.java) { root.releasePublicTrustAfterShutdown() }
        } finally {
            assertTrue(permit.releaseAfterQuiescence())
        }
        assertFalse(Files.exists(trustPath(root).parent))
        assertEquals(PersistencePublicTrustRelease.RELEASED, root.releasePublicTrustAfterShutdown())

        val locked = configuration(parent = protectedParent("locked")).createRoot()
        val binding = binding(locked.ordinary)
        binding.ledger.lock.lock()
        try {
            assertEquals(PersistencePublicTrustPreparation.REFUSED, locked.preparePublicTrust())
            assertEquals(PersistencePublicTrustRelease.RETAINED, locked.releasePublicTrustAfterShutdown())
            assertFalse(Files.exists(trustPath(locked).parent))
        } finally {
            binding.ledger.lock.unlock()
        }
        locked.requestShutdown()
        assertEquals(PersistencePublicTrustRelease.RELEASED, locked.releasePublicTrustAfterShutdown())
    }

    @Test
    fun `owner surface retains custody and legacy null trust including inert source-only timer stays compatible`() {
        val configuration = configuration()
        val owner = configuration.bindLifecycleOwner()
        assertEquals(PersistencePublicTrustPreparation.READY, owner.preparePublicTrust())
        assertEquals(PersistencePublicTrustRelease.RETAINED, owner.releasePublicTrustAfterShutdown())
        owner.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown(PersistenceTimeBudget.start(100)))
        assertEquals(PersistencePublicTrustRelease.RELEASED, owner.releasePublicTrustAfterShutdown())
        val endpoint = ResolvedPersistenceEndpoint(emptyMap(), PersistenceLoginPolicy.resolve("2", 2000))
        listOf(
            PersistenceJdbcLifecycleOwner(endpoint, 1, PersistencePathStyle.POSIX),
            PersistenceJdbcLifecycleOwner.sourceOnly(endpoint, 1, PersistencePathStyle.POSIX),
        ).forEach { legacy ->
            assertEquals(PersistencePublicTrustPreparation.NOT_REQUIRED, legacy.preparePublicTrust())
            legacy.requestShutdown()
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, legacy.observeShutdown(PersistenceTimeBudget.start(100)))
            assertEquals(PersistencePublicTrustRelease.NOT_REQUIRED, legacy.releasePublicTrustAfterShutdown())
        }
    }

    private fun protectedParent(name: String = "private"): Path {
        val parent = temporary.toRealPath().resolve(name)
        if (!Files.exists(parent)) Files.createDirectory(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        return parent
    }

    private fun configuration(
        pem: ByteArray = VersionBoundPersistenceTestInputs.pem(),
        parent: Path = protectedParent(),
    ): VersionBoundPersistenceConfiguration = VersionBoundPersistenceConfiguration.fromAcquired(
        VersionBoundPersistenceTestInputs.acquired(),
        "db.invalid",
        5432,
        "fixture_db",
        "fixture_user",
        2,
        pem,
        parent,
    )

    private fun preparedRoot(parent: Path = protectedParent()): PersistenceJdbcDriverRoot = configuration(parent = parent).createRoot().also {
        assertEquals(PersistencePublicTrustPreparation.READY, it.preparePublicTrust())
    }

    private fun trustPath(root: PersistenceJdbcDriverRoot): Path = Path.of(root.endpoint.driverProperties().getProperty("sslrootcert"))

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }

    private fun binding(participant: PersistenceJdbcParticipant): PersistencePhysicalFactoryBinding {
        val field = PersistenceJdbcParticipant::class.java.getDeclaredField("binding").apply { check(trySetAccessible()) }
        return field.get(participant) as PersistencePhysicalFactoryBinding
    }
}
