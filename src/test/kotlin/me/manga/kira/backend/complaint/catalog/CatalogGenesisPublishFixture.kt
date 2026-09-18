package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisReleaseLeafV1
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

/** Reuses the coherent TARGET fixture's genuine AUTHOR freeze, independent pin and real fixed-operator first-D selection. */
internal fun withCatalogGenesisPublish(
    tls: VersionBoundPersistenceConnectedFixture,
    catalogAttemptMillis: Long? = null,
    test: (CatalogGenesisPublishFixture) -> Unit,
) = CatalogGenesisPublishFixture(tls, catalogAttemptMillis).use { fixture ->
    fixture.prepare()
    test(fixture)
}

internal class CatalogGenesisPublishFixture(tls: VersionBoundPersistenceConnectedFixture, private val catalogAttemptMillis: Long? = null) : AutoCloseable {
    val selected = CatalogGenesisTargetFinalizeFixture(tls)
    val freeze get() = selected.freeze
    val observer get() = selected.observer
    val inputs get() = selected.inputs
    val invocations = mutableListOf<CatalogGenesisPublishInvocation>()
    lateinit var http: CatalogGenesisPublishHttpFixture
        private set
    val request: CatalogGenesisPublishRequestV1 get() = request()

    fun prepare() {
        selected.prepare("D2", catalogAttemptMillis)
        http = CatalogGenesisPublishHttpFixture(selected.envelope, freeze.manifest.creation.createdAtEpochSecond)
        assertUnchangedFreeze()
    }

    fun request(
        frozen: CatalogGenesisFreezeRequestV1 = selected.request.frozen,
        targetDeployment: Path = selected.request.targetDeployment,
    ): CatalogGenesisPublishRequestV1 = CatalogGenesisPublishRequestV1(frozen, targetDeployment)

    fun invocation(): CatalogGenesisPublishInvocation = CatalogGenesisPublishInvocation(this).also(invocations::add)

    fun state(): List<String> = selected.state()

    fun exists(leaf: CatalogGenesisReleaseLeafV1): Boolean = selected.exists(leaf)

    fun complete(leaf: CatalogGenesisReleaseLeafV1): Boolean {
        requireConnectionFree()
        val main = freeze.leafPath(leaf)
        val path = selected.completeness(leaf)
        if (!Files.isRegularFile(main, NOFOLLOW_LINKS) || !Files.isRegularFile(path, NOFOLLOW_LINKS)) return false
        val bytes = Files.readAllBytes(main)
        val expected = ByteBuffer.allocate(68).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array()
        // Visibility/checksum observation only; callers use the real returned arm/write boundary, never infer force completion from presence.
        return Files.readAllBytes(path).contentEquals(expected)
    }

    fun read(leaf: CatalogGenesisReleaseLeafV1): ByteArray = freeze.read(leaf)

    fun assertUnchangedFreeze() {
        requireConnectionFree()
        selected.frozenFiles.forEach { (path, bytes) -> assertArrayEquals(bytes, Files.readAllBytes(path)) }
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FIRST_D_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FIRST_D_OUTCOME))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FINALIZE_ARMED))
        assertFalse(exists(CatalogGenesisReleaseLeafV1.FINALIZE_OUTCOME))
    }

    override fun close() {
        val retired = invocations.map { runCatching(it::fixtureCleanup) }
        val ready = runCatching {
            requireConnectionFree()
            assertTrue(invocations.all { it.cleanupVerified }, "Do not restore rows or erase files before all original publisher owners retire.")
        }
        val restored = runCatching {
            ready.getOrThrow()
            selected.close() // Original nested author/first-D owners and snapshots remain owned by the existing fixture.
        }
        rethrowTargetFinalizeFixtureFailures(retired + listOf(ready, restored))
    }
}
