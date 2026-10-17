package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.ServiceUnavailableException
import me.manga.kira.backend.config.KiraClientCatalogProperties
import me.manga.kira.backend.config.KiraClientCatalogProperties.VersionPin
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`

class ClientCatalogQueryServiceTest {
    private val fixture = ClientCatalogFixture()

    @Test
    fun `only an exact admitted wire version selects current and a pinned version never returns to baseline`() {
        val query = fixture.query()
        val fallback = listOf(
            null, emptyList(), listOf(""), listOf("9.0.0"), listOf("9.0.3"), listOf("9.0.2-rc.1"),
            listOf("09.0.2"), listOf(" 9.0.2"), listOf("9.0.2 "), listOf("9.0.2\n"), listOf("9.0.2,9.0.2"),
            listOf("9.0.2", "9.0.2"), listOf("9.0.2", ""), listOf("9.0.2+build"), listOf("9".repeat(65)),
        )
        fallback.forEach { versions ->
            assertSame(fixture.baseline.document, query.documentForClient(versions))
            assertSame(fixture.baseline.document, query.documentMetaForClient(versions))
            assertSame(fixture.baseline.manifest, query.manifestForClient(versions))
        }
        assertSame(fixture.current.document, query.documentForClient(listOf("9.0.2")))
        assertSame(fixture.current.manifest, query.manifestForClient(listOf("9.0.2")))
        assertSame(fixture.pinned.document, query.documentForClient(listOf("9.0.1")))
        assertSame(fixture.pinned.manifest, query.manifestForClient(listOf("9.0.1")))
    }

    @Test
    fun `startup checks all pins but each client read checks only selected stored pair and authoritative pointer`() {
        val query = fixture.query()
        query.validateStartup()
        verify(fixture.documents).latestPointer()
        verify(fixture.documents).findByRevision(100)
        verify(fixture.documents).findByRevision(101)
        verify(fixture.catalogs).findByRevision(100)
        verify(fixture.catalogs).findByRevision(101)
        verify(fixture.documents).findByRevision(102)
        verify(fixture.catalogs).findByRevision(102)
        clearInvocations(fixture.documents, fixture.catalogs)

        assertSame(fixture.current.document, query.documentForClient(listOf("9.0.2")))
        verify(fixture.documents).latestPointer()
        verify(fixture.documents).findByRevision(102)
        verify(fixture.catalogs).findByRevision(102)
        verify(fixture.documents, never()).findByRevision(100)
        verify(fixture.documents, never()).findByRevision(101)
        verify(fixture.documents, never()).maxDocumentRevision()

        clearInvocations(fixture.documents, fixture.catalogs)
        assertSame(fixture.baseline.manifest, query.manifestForClient(null))
        verify(fixture.documents).findByRevision(102)
        verify(fixture.catalogs).findByRevision(102)
        verify(fixture.documents).findByRevision(100)
        verify(fixture.catalogs).findByRevision(100)
        verify(fixture.documents, never()).findByRevision(101)
        verify(fixture.catalogs, never()).findByRevision(101)
    }

    @Test
    fun `missing corrupt unsigned wrong-key and wrong-chain selected artifacts refuse without a latest fallback`() {
        val query = fixture.query()
        query.validateStartup()
        val original = fixture.baseline.document
        val documentCases = listOf(
            null,
            original.copy(documentJson = original.documentJson + "synthetic_PRIVATE_payload"),
            original.copy(checksum = "0".repeat(64)),
            original.copy(documentRevision = 99),
            original.copy(schemaVersion = 2),
            original.copy(signatureBase64 = null),
            original.copy(signingKeyId = "unknown-synthetic-key"),
            original.copy(signatureBase64 = "A".repeat(86) + "=="),
            original.copy(previousDocumentRevision = 99, previousDocumentChecksum = "f".repeat(64)),
        )
        documentCases.forEach { value ->
            if (value == null) fixture.documentRows.remove(100) else fixture.documentRows[100] = value
            assertUnavailable { query.manifestForClient(null) }
        }
        fixture.documentRows[100] = original
        val manifest = fixture.baseline.manifest
        listOf(
            null,
            manifest.copy(manifestJson = manifest.manifestJson + "synthetic_PRIVATE_payload"),
            manifest.copy(signatureFormat = "unknown"),
            manifest.copy(createdAt = manifest.createdAt.plusSeconds(1)),
            manifest.copy(previousCatalogRevision = 100, previousCatalogChecksum = "f".repeat(64)),
        ).forEach { value ->
            if (value == null) fixture.manifestRows.remove(100) else fixture.manifestRows[100] = value
            assertUnavailable { query.documentForClient(null) }
        }
    }

    @Test
    fun `missing or corrupt actual pointer pair refuses baseline clients after successful startup`() {
        val query = fixture.query()
        query.validateStartup()
        fixture.documentRows.remove(102)
        assertUnavailable { query.documentForClient(null) }
        fixture.documentRows[102] = fixture.current.document
        fixture.manifestRows.remove(102)
        assertUnavailable { query.documentMetaForClient(null) }
        fixture.manifestRows[102] = fixture.current.manifest.copy(manifestJson = "synthetic_PRIVATE_corruption")
        assertUnavailable { query.manifestForClient(null) }
    }

    @Test
    fun `missing or rewound pointer and mismatched independent pin refuse startup and reads`() {
        val query = fixture.query()
        listOf(null, 0L, 99L, 100L).forEach { pointer ->
            fixture.pointer = pointer
            assertUnavailable { query.validateStartup() }
            assertUnavailable { query.documentForClient(listOf("9.0.2")) }
        }
        fixture.pointer = 102
        val wrong = fixture.config().copy(baseline = fixture.pin(fixture.baseline).copy(manifestChecksum = "0".repeat(64)))
        assertUnavailable { fixture.query(wrong).validateStartup() }
        assertUnavailable { fixture.query(wrong).manifestForClient(null) }
        // Even when current equals an explicitly pinned revision, current cannot bypass that known hash binding.
        fixture.pointer = 100
        assertUnavailable { fixture.query(wrong.copy(pinnedVersions = emptyList())).documentForClient(listOf("9.0.2")) }
    }

    @Test
    fun `enabled configuration rejects absent conflicting noncanonical overlapping and oversized selections`() {
        val original = fixture.config()
        val baseline = fixture.pin(fixture.baseline)
        val cases = listOf(
            original.copy(baseline = null),
            original.copy(baseline = baseline.copy(revision = 0)),
            original.copy(baseline = baseline.copy(documentChecksum = "X".repeat(64))),
            original.copy(currentVersions = listOf("9.0.2", "9.0.2")),
            original.copy(currentVersions = listOf("9.0.1")),
            original.copy(currentVersions = listOf(" 9.0.2")),
            original.copy(currentVersions = listOf("9.0.2-rc")),
            original.copy(currentVersions = listOf("09.0.2")),
            original.copy(currentVersions = listOf("9".repeat(65))),
            original.copy(currentVersions = (0..32).map { "9.1.$it" }),
            original.copy(pinnedVersions = listOf(VersionPin("9.0.1", baseline.copy(revision = 99)))),
            original.copy(pinnedVersions = original.pinnedVersions + original.pinnedVersions),
            original.copy(pinnedVersions = listOf(VersionPin("9.0.1", baseline.copy(documentChecksum = "0".repeat(64))))),
        )
        clearInvocations(fixture.documents, fixture.catalogs, fixture.signer)
        cases.forEach { properties ->
            val error = assertThrows(IllegalStateException::class.java) { fixture.query(properties) }
            assertEquals("client catalog delivery configuration is invalid; see docs/SOURCE_CONFIG_LIFECYCLE.md.", error.message)
        }
        verifyNoInteractions(fixture.documents, fixture.catalogs, fixture.signer)
    }

    @Test
    fun `disabled preserves old latest and invalid-v1 behavior without becoming rollout protection`() {
        val query = fixture.query(KiraClientCatalogProperties())
        clearInvocations(fixture.documents, fixture.catalogs, fixture.signer)
        query.validateStartup()
        verifyNoInteractions(fixture.documents, fixture.catalogs, fixture.signer)
        listOf(null, listOf("1"), listOf("1.2.3-rc.1"), listOf("1.2.3.4+build.9")).forEach { values ->
            assertSame(fixture.current.document, query.documentForClient(values))
        }
        listOf(listOf("invalid"), listOf("9".repeat(65)), listOf("9.0.2", "9.0.2")).forEach { values ->
            assertEquals("INVALID_APP_VERSION", assertThrows(BadRequestException::class.java) { query.documentForClient(values) }.code)
            assertSame(fixture.current.document, query.documentMetaForClient(values))
            assertSame(fixture.current.manifest, query.manifestForClient(values))
        }
        fixture.pointer = null
        assertNull(query.documentForClient(null))
        assertNull(query.documentMetaForClient(null))
        assertNull(query.manifestForClient(null))
        verifyNoInteractions(fixture.signer)
    }

    @Test
    fun `configuration is copied and provider failures do not expose submitted values or causes`() {
        val versions = mutableListOf("9.0.2")
        val query = fixture.query(fixture.config().copy(currentVersions = versions))
        versions.clear()
        versions.add("9.0.3")
        assertSame(fixture.current.document, query.documentForClient(listOf("9.0.2")))
        assertSame(fixture.baseline.document, query.documentForClient(listOf("9.0.3")))
        `when`(fixture.documents.latestPointer()).thenThrow(IllegalStateException("synthetic_PRIVATE_database_cause"))
        assertUnavailable { query.documentForClient(listOf("synthetic_PRIVATE_input")) }
    }

    private fun assertUnavailable(read: () -> Unit) {
        val error = assertThrows(ServiceUnavailableException::class.java) { read() }
        assertEquals("CLIENT_CATALOG_UNAVAILABLE", error.code)
        assertEquals("signed client catalog is unavailable.", error.message)
        assertNull(error.cause)
    }
}
