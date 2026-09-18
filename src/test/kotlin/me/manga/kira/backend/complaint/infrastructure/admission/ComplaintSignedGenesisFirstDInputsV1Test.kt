package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.VersionBoundCatalogReadbackTestFixture
import me.manga.kira.backend.complaint.domain.catalog.CatalogGenesisCapacity
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** Raw-input boundary tests only: genuine public fixture signatures, cold roots, no provider or database preparation. */
class ComplaintSignedGenesisFirstDInputsV1Test {
    @Test
    fun `each raw document has an inclusive byte bound and the independent pin has exact lowercase grammar`() {
        val raw = RawRelease()
        val fields = listOf<Pair<Int, (ByteArray) -> RawRelease>>(
            CatalogGenesisCapacity.MAX_DOCUMENT_BYTES to { raw.copy(intent = it) },
            OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES to { raw.copy(initial = it) },
            OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES to { raw.copy(current = it) },
            CatalogGenesisCapacity.MAX_DOCUMENT_BYTES to { raw.copy(envelope = it) },
        )
        for ((maximum, candidate) in fields) {
            // Retention is deliberately not parsing or authorization, even at the exact ceiling.
            candidate(ByteArray(maximum)).retain()
            refused { candidate(byteArrayOf()).retain() }
            refused { candidate(ByteArray(maximum + 1)).retain() }
        }
        for (pin in listOf("", "a".repeat(63), "a".repeat(65), "A".repeat(64), "g".repeat(64), "a".repeat(64) + "\n")) {
            refused { raw.copy(pin = pin).retain() }
        }
        requireConnectionFree()
    }

    @Test
    fun `caller mutation cannot replace retained raw bytes and genuine verification leaves both roots cold`() {
        withAssembly(DesiredInstallationInputFixture.document()) { assembly ->
            val raw = RawRelease()
            val inputs = raw.retain()
            listOf(raw.intent, raw.initial, raw.current, raw.envelope).forEach { it.fill(0) }
            val verified = inputs.verifyFor(assembly.target)
            assertEquals("ComplaintSignedGenesisFirstDInputsV1(raw-release,redacted,no-authority)", inputs.toString())
            assertEquals("SignedGenesisFirstDInputs.Verified(private-comparison,no-authority)", verified.toString())
        }
    }

    @Test
    fun `malformed independent intent is refused without exposing parser input or diagnostics`() {
        withAssembly(DesiredInstallationInputFixture.document()) { assembly ->
            val malformed = "{\"submitted-private-canary\":\"unterminated".toByteArray(Charsets.UTF_8)
            refused { RawRelease(intent = malformed).retain().verifyFor(assembly.target) }
        }
    }

    @Test
    fun `a genuine release cannot replace the actual target trust pair or independently held pin`() {
        val original = DesiredInstallationInputFixture.document()
        val reader = checkNotNull(original.catalog)
        val alternatives = listOf(
            reader.copy(initialBundleBase64 = reader.currentBundleBase64),
            // This is still an authenticated bundle under a permitted independent floor, but not the retained release's Tn.
            reader.copy(currentBundleBase64 = reader.initialBundleBase64, minimumBundleVersion = 1),
            reader.copy(expectedGenesisEnvelopeSha256 = "0".repeat(64)),
        )
        val release = RawRelease().retain()
        alternatives.forEach { catalog ->
            withAssembly(original.copy(catalog = catalog)) { assembly ->
                refused { release.verifyFor(assembly.target) }
            }
        }
    }

    @Test
    fun `signed inputs cannot substitute for the actual journal configuration or current approver policy`() {
        val original = DesiredInstallationInputFixture.document()
        val limits = original.journal.limits
        val changedJournal = original.journal.copy(
            limits = limits.copy(decoder = limits.decoder.copy(maximumJsonTokens = limits.decoder.maximumJsonTokens - 1)),
        )
        val changedPolicy = checkNotNull(original.catalog).copy(currentApproverIds = listOf("catalog-approver-a", "current-only-approver"))
        val release = RawRelease().retain()
        for (document in listOf(original.copy(journal = changedJournal), original.copy(catalog = changedPolicy))) {
            withAssembly(document) { assembly ->
                refused { release.verifyFor(assembly.target) }
            }
        }
    }

    private fun withAssembly(document: ComplaintDesiredDeploymentDocumentV1, work: (ComplaintDesiredProcessAssemblyV1) -> Unit) {
        val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(document)
        val assembly = ComplaintDesiredProcessAssemblyV1()
        try {
            assembly.assemble(inputs, DesiredInstallationInputFixture.acquired(inputs), null)
            requireCold(assembly)
            work(assembly)
            requireCold(assembly)
        } finally {
            assembly.close()
            assembly.requireCleanup(PersistenceTimeBudget.start(2_000))
        }
    }

    private fun requireCold(assembly: ComplaintDesiredProcessAssemblyV1) {
        for (name in listOf("targetOwner", "operatorOwner")) {
            val owner = ownedCutField(assembly, name) as PersistenceJdbcLifecycleOwner
            assertTrue(PgLifecycleTestScope(owner).actors().none { it.hasEntered() }, name)
            val pools = checkNotNull(owner.versionBoundPools)
            for (pool in listOf(pools.ordinary, pools.deletion, pools.catalogCoordinator.dataSource)) {
                assertFalse(actualPool(pool).isRunning, name)
            }
            assertEquals(0, pools.catalogCoordinator.activeSnapshotOwners(), name)
        }
        requireConnectionFree()
    }

    private fun refused(work: () -> Any?) {
        val failure = assertThrows<ComplaintDesiredInstallationExceptionV1> { work() }
        assertEquals(ComplaintDesiredInstallationFailureV1.INPUT_REFUSED, failure.code)
        assertEquals("Desired configuration installation refused.", failure.message)
        assertNull(failure.cause)
        assertTrue(failure.suppressed.isEmpty())
        assertTrue(failure.stackTrace.isEmpty())
    }

    private data class RawRelease(
        val intent: ByteArray = VersionBoundCatalogReadbackTestFixture.manifestBytes(),
        val initial: ByteArray = VersionBoundCatalogReadbackTestFixture.initialBundleBytes(),
        val current: ByteArray = VersionBoundCatalogReadbackTestFixture.currentBundleBytes(),
        val envelope: ByteArray = VersionBoundCatalogReadbackTestFixture.genesisBytes(),
        val pin: String = VersionBoundCatalogReadbackTestFixture.EXPECTED_GENESIS_SHA256,
    ) {
        fun retain(): ComplaintSignedGenesisFirstDInputsV1 = ComplaintSignedGenesisFirstDInputsV1.fromRaw(intent, initial, current, envelope, pin)
    }
}
