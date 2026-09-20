package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogRotationChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleProtocol
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogRotationChainVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.IOException

class OfflineCatalogRotationReaderTest {
    private val fixture = OfflineCatalogRotationFixture
    private val bundles = OfflineTrustBundleFixture
    private val chain by lazy { fixture.chain() }

    @Test
    fun `current reader lists and nested trust inputs are defensive without supplying database authority`() {
        val writers = mutableListOf(bundles.CATALOG_WRITER, bundles.EVENT_WRITER)
        val approvers = mutableListOf("catalog-approver-a", "catalog-approver-b")
        val root = bundles.root.public.encoded
        val trust = bundles.policy(rootSpki = root, minimumVersion = 9)
        val policy = OfflineCatalogChainReaderPolicy(trust, writers, approvers, fixture.limits())
        writers.clear()
        approvers.clear()
        root.fill(0)
        (policy.currentWriterGenerationIds as MutableList<String>).clear()
        (policy.currentApproverIds as MutableList<String>).clear()
        policy.trustBundlePolicy.rootPublicKeySpki.fill(0)
        assertEquals(listOf(bundles.CATALOG_WRITER, bundles.EVENT_WRITER), policy.currentWriterGenerationIds)
        assertEquals(listOf("catalog-approver-a", "catalog-approver-b"), policy.currentApproverIds)
        assertEquals("OfflineCatalogChainReaderPolicy(out-of-band,no-database-authority)", policy.toString())
        assertEquals(3L, verify(chain.bytes().asSequence(), policy).tail.generation)
    }

    @Test
    fun `current writer and approver lists are bounded canonical sorted distinct inputs with no default grant`() {
        val badWriters = listOf(
            emptyList(),
            List(17) { bundles.CATALOG_WRITER },
            listOf(bundles.CATALOG_WRITER, bundles.CATALOG_WRITER),
            listOf(bundles.EVENT_WRITER, bundles.CATALOG_WRITER),
            listOf("AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA"),
            listOf("00000000-0000-0000-0000-000000000000"),
        )
        badWriters.forEach { writers ->
            val failure = assertThrows(OfflineTrustBundleException::class.java) { fixture.policy(writers = writers) }
            assertEquals(OfflineTrustBundleFailure.INVALID_POLICY, failure.code)
        }
        val badApprovers = listOf(
            emptyList(),
            listOf("a"),
            List(17) { "a" },
            listOf("a", "a"),
            listOf("b", "a"),
            listOf(" ", "a"),
            listOf("a", "é"),
            listOf("a", "b".repeat(257)),
        )
        badApprovers.forEach { approvers ->
            val failure = assertThrows(OfflineTrustBundleException::class.java) { fixture.policy(approvers = approvers) }
            assertEquals(OfflineTrustBundleFailure.INVALID_POLICY, failure.code)
        }
    }

    @Test
    fun `configured limits may be lower but zero negative oversized and overflow sized ceilings cannot be constructed`() {
        val hard = fixture.limits()
        val invalid = listOf<() -> Unit>(
            { hard.copy(maximumEnvelopeBytes = 0) },
            { hard.copy(maximumEnvelopeBytes = hard.maximumEnvelopeBytes + 1) },
            { hard.copy(maximumManifestRecords = 0) },
            { hard.copy(maximumManifestRecords = hard.maximumManifestRecords + 1) },
            { hard.copy(maximumGenerations = -1) },
            { hard.copy(maximumGenerations = hard.maximumGenerations + 1) },
            { hard.copy(maximumEncodedBytes = 0) },
            { hard.copy(maximumEncodedBytes = hard.maximumEncodedBytes + 1) },
            { hard.copy(maximumEncodedBytes = Long.MAX_VALUE) },
        )
        invalid.forEach { make ->
            val failure = assertThrows(OfflineTrustBundleException::class.java) { make() }
            assertEquals(OfflineTrustBundleFailure.INVALID_POLICY, failure.code)
        }
        assertEquals(2147483648L, hard.maximumEncodedBytes)
    }

    @Test
    fun `small actual chain passes exact byte generation total and per manifest record limits but not one less`() {
        val bytes = chain.bytes()
        val exact = fixture.limits().copy(
            maximumEnvelopeBytes = bytes.maxOf { it.size },
            maximumGenerations = bytes.size,
            maximumEncodedBytes = bytes.sumOf { it.size.toLong() },
            maximumManifestRecords = bytes.maxOf(fixture::manifestRecords),
        )
        assertEquals(3L, verify(bytes.asSequence(), fixture.policy(exact)).tail.generation)
        listOf(
            exact.copy(maximumEnvelopeBytes = exact.maximumEnvelopeBytes - 1),
            exact.copy(maximumGenerations = exact.maximumGenerations - 1),
            exact.copy(maximumEncodedBytes = exact.maximumEncodedBytes - 1),
            exact.copy(maximumManifestRecords = exact.maximumManifestRecords - 1),
        ).forEach { lower -> reject(bytes.asSequence(), OfflineTrustBundleFailure.LIMIT_EXCEEDED, fixture.policy(lower)) }
    }

    @Test
    fun `manifest object budget includes the extra overlap member and is reset for each supplied envelope`() {
        val bytes = chain.bytes()
        val genesisRecords = fixture.manifestRecords(bytes[0])
        val overlapRecords = fixture.manifestRecords(bytes[1])
        assertEquals(genesisRecords + 1, overlapRecords)
        val enough = fixture.limits().copy(maximumManifestRecords = overlapRecords)
        verify(bytes.asSequence(), fixture.policy(enough))
        val initialOnly = enough.copy(maximumManifestRecords = genesisRecords)
        assertEquals(1L, verify(bytes.take(1).asSequence(), fixture.policy(initialOnly)).tail.generation)
        reject(bytes.asSequence(), OfflineTrustBundleFailure.LIMIT_EXCEEDED, fixture.policy(initialOnly))
    }

    @Test
    fun `generation budget rejects the next item before requesting it from the supplier`() {
        val first = chain.bytes().first()
        var nextCalls = 0
        val source = Sequence {
            object : Iterator<ByteArray> {
                override fun hasNext(): Boolean = nextCalls < 2

                override fun next(): ByteArray {
                    if (!hasNext()) throw NoSuchElementException()
                    nextCalls++
                    if (nextCalls > 1) error("Must not request an over-budget item")
                    return first
                }
            }
        }
        reject(source, OfflineTrustBundleFailure.LIMIT_EXCEEDED, fixture.policy(fixture.limits().copy(maximumGenerations = 1)))
        assertEquals(1, nextCalls)
    }

    @Test
    fun `byte and remaining total budgets reject malformed over budget input before parsing`() {
        val first = chain.bytes().first()
        val byteLimit = fixture.limits().copy(maximumEnvelopeBytes = first.size)
        reject(sequenceOf(ByteArray(first.size + 1)), OfflineTrustBundleFailure.LIMIT_EXCEEDED, fixture.policy(byteLimit))
        reject(sequenceOf(first, ByteArray(first.size + 1)), OfflineTrustBundleFailure.LIMIT_EXCEEDED, fixture.policy(byteLimit))
        val totalLimit = fixture.limits().copy(maximumEncodedBytes = first.size.toLong() + 1)
        reject(sequenceOf(first, ByteArray(2)), OfflineTrustBundleFailure.LIMIT_EXCEEDED, fixture.policy(totalLimit))
    }

    @Test
    fun `larger chain envelope ceiling never widens the original genesis bootstrap byte limit`() {
        val tooLargeForGenesis = ByteArray(OfflineTrustBundleProtocol.MAX_ENVELOPE_BYTES + 1)
        reject(sequenceOf(tooLargeForGenesis), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `reader traverses exactly one iterator and requests each supplied envelope only once`() {
        val bytes = chain.bytes()
        var iteratorCalls = 0
        var nextCalls = 0
        val source = Sequence {
            iteratorCalls++
            check(iteratorCalls == 1)
            object : Iterator<ByteArray> {
                private var index = 0

                override fun hasNext(): Boolean = index < bytes.size

                override fun next(): ByteArray {
                    if (index >= bytes.size) throw NoSuchElementException()
                    nextCalls++
                    return bytes[index++]
                }
            }
        }
        assertEquals(3L, verify(source).tail.generation)
        assertEquals(1, iteratorCalls)
        assertEquals(bytes.size, nextCalls)
    }

    @Test
    fun `iterator creation hasNext and next exceptions are sanitized and never return a partial result`() {
        val detail = "private supplier detail"
        val sources = listOf(
            Sequence<ByteArray> { throw IllegalStateException(detail) },
            Sequence {
                object : Iterator<ByteArray> {
                    override fun hasNext(): Boolean = throw IOException(detail)
                    override fun next(): ByteArray = throw NoSuchElementException("Not reached")
                }
            },
            Sequence {
                object : Iterator<ByteArray> {
                    private var attempted = false

                    override fun hasNext(): Boolean = !attempted

                    override fun next(): ByteArray {
                        if (attempted) throw NoSuchElementException()
                        attempted = true
                        throw IllegalArgumentException(detail)
                    }
                }
            },
        )
        sources.forEach { source ->
            val failure = assertThrows(OfflineTrustBundleException::class.java) { verify(source) }
            assertEquals(OfflineTrustBundleFailure.SUPPLIER_FAILURE, failure.code)
            assertFalse(failure.message.orEmpty().contains(detail))
            assertNull(failure.cause)
        }
    }

    @Test
    fun `supplier failure after a valid pending overlap does not return the verified prefix as success`() {
        val bytes = chain.bytes().take(2)
        val source = Sequence {
            object : Iterator<ByteArray> {
                private var index = 0

                override fun hasNext(): Boolean {
                    if (index == bytes.size) error("Supplier failed after valid prefix")
                    return true
                }

                override fun next(): ByteArray {
                    if (index >= bytes.size) throw NoSuchElementException()
                    return bytes[index++]
                }
            }
        }
        reject(source, OfflineTrustBundleFailure.SUPPLIER_FAILURE)
    }

    @Test
    fun `supplier interruption aborts and preserves the interrupt flag`() {
        val previouslyInterrupted = Thread.interrupted()
        try {
            reject(Sequence<ByteArray> { throw InterruptedException("private detail") }, OfflineTrustBundleFailure.SUPPLIER_FAILURE)
            assertTrue(Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
            if (previouslyInterrupted) Thread.currentThread().interrupt()
        }
    }

    @Test
    fun `supplier can overwrite previously yielded buffers without changing retained hashes or returned evidence`() {
        val bytes = chain.bytes()
        val expectedGenesis = Sha256.hex(bytes.first())
        val expectedTail = Sha256.hex(bytes.last())
        val expectedTotal = bytes.sumOf { it.size.toLong() }
        val initialBytes = bundles.bytes(chain.initial)
        val currentBytes = bundles.bytes(chain.current)
        val expectedCurrent = Sha256.hex(currentBytes)
        val source = Sequence {
            object : Iterator<ByteArray> {
                private var index = 0

                override fun hasNext(): Boolean = index < bytes.size

                override fun next(): ByteArray {
                    if (index >= bytes.size) throw NoSuchElementException()
                    if (index > 0) bytes[index - 1].fill(0)
                    return bytes[index++]
                }
            }
        }
        val checked = OfflineCatalogRotationChainVerifier.verifyRotationChain(source, initialBytes, currentBytes, fixture.policy())
        bytes.last().fill(0)
        initialBytes.fill(0)
        currentBytes.fill(0)
        assertEquals(expectedGenesis, checked.trust.genesisEnvelopeSha256)
        assertEquals(expectedTail, checked.tail.envelopeSha256)
        assertEquals(expectedCurrent, checked.trust.currentBundleEnvelopeSha256)
        assertEquals(expectedTotal, checked.encodedBytes)
        assertEquals(CatalogRotationState.Stable(fixture.member("catalog-new")), checked.rotation)
    }

    private fun verify(source: Sequence<ByteArray>, policy: OfflineCatalogChainReaderPolicy = fixture.policy()): CheckedOfflineCatalogRotationChain =
        OfflineCatalogRotationChainVerifier.verifyRotationChain(
            source,
            bundles.bytes(chain.initial),
            bundles.bytes(chain.current),
            policy,
        )

    private fun reject(source: Sequence<ByteArray>, code: OfflineTrustBundleFailure, policy: OfflineCatalogChainReaderPolicy = fixture.policy()) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { verify(source, policy) }
        assertEquals(code, failure.code)
    }
}
