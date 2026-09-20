package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRotationState
import me.manga.kira.backend.complaint.domain.catalog.CatalogSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.CheckedOfflineCatalogInventoryChain
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainReaderPolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryEnvelopeV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryManifestV2
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogInventoryChainVerifier
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineCatalogRotationChainVerifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.Base64

class OfflineCatalogInventoryChainVerifierTest {
    private val fixture = OfflineCatalogInventoryFixture
    private val rotations = OfflineCatalogRotationFixture
    private val bundles = OfflineTrustBundleFixture
    private val chain by lazy { fixture.chain() }
    private val register get() = chain.generations.first()
    private val add get() = chain.generations.last()

    @Test
    fun `genuine register and add copy signatures bind the full inventory and exact role metadata without granting acceptance`() {
        val bytes = chain.bytes()
        val checked = verify(bytes)
        assertEquals(3L, checked.tail.generation)
        assertEquals(Sha256.hex(bytes.last()), checked.tail.envelopeSha256)
        assertEquals(Sha256.hex(fixture.manifestBytes(add.manifest)), checked.tail.manifestSha256)
        assertEquals(add.manifest.restoreInventory, checked.inventory)
        assertEquals(CatalogRotationState.Stable(rotations.member("catalog-old")), checked.rotation)
        assertEquals(bytes.sumOf { it.size.toLong() }, checked.encodedBytes)
        val source = checked.inventory.sources.single()
        assertNotEquals(source.bundle.manifest.sha256, source.bundleSha256)
        assertEquals(fixture.bundleHash(source.bundle), source.bundleSha256)
        val copies = checked.inventory.copies
        assertEquals(copies[0].manifest.versionId, copies[1].manifest.versionId)
        assertNotEquals(copies[0].manifest.bucket, copies[1].manifest.bucket)
        assertEquals("CheckedOfflineCatalogInventoryChain(no-accepted-head,no-provider-or-restore-authority)", checked.toString())
    }

    @Test
    fun `existing empty schema one rotation prefix can precede inventory without changing its bytes or API`() {
        val prefixed = fixture.chain(emptyRotationPrefix = true)
        val checked = verify(prefixed.bytes(), prefixed)
        assertEquals(5L, checked.tail.generation)
        assertEquals(CatalogRotationState.Stable(rotations.member("catalog-new")), checked.rotation)
        val old = OfflineCatalogRotationChainVerifier.verifyRotationChain(
            prefixed.prefix.asSequence(),
            bundles.bytes(prefixed.base.initial),
            bundles.bytes(prefixed.base.current),
            rotations.policy(),
        )
        assertEquals(3L, old.tail.generation)
        assertThrows(OfflineTrustBundleException::class.java) {
            OfflineCatalogRotationChainVerifier.verifyRotationChain(
                prefixed.bytes().asSequence(),
                bundles.bytes(prefixed.base.initial),
                bundles.bytes(prefixed.base.current),
                rotations.policy(),
            )
        }
    }

    @Test
    fun `inventory persists through real dual overlap and immediate single activation with explicit pending tail`() {
        val overlap = rotation(4, fixture.bytes(add), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new"))
        val pending = verify(chain.bytes() + fixture.bytes(overlap))
        assertEquals(CatalogRotationState.AwaitingActivation(rotations.member("catalog-old"), rotations.member("catalog-new")), pending.rotation)
        assertEquals(add.manifest.restoreInventory, pending.inventory)
        val activate = rotation(5, fixture.bytes(overlap), "SINGLE", listOf("catalog-new"))
        val active = verify(chain.bytes() + listOf(fixture.bytes(overlap), fixture.bytes(activate)))
        assertEquals(CatalogRotationState.Stable(rotations.member("catalog-new")), active.rotation)
        assertEquals(pending.inventory, active.inventory)
    }

    @Test
    fun `copy registration may follow activation only under the new active key`() {
        val overlap = rotation(4, fixture.bytes(add), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new"))
        val activate = rotation(5, fixture.bytes(overlap), "SINGLE", listOf("catalog-new"))
        val extra = fixture.copy(add.manifest.restoreInventory.sources.single(), 3)
        val next = fixture.manifest(
            chain.base.genesis,
            6,
            fixture.bytes(activate),
            "ADD_COPY",
            add.manifest.restoreInventory.copy(copies = add.manifest.restoreInventory.copies + extra),
            CatalogInventoryDeltaV1(emptyList(), listOf(extra.copyId)),
            "catalog-new",
        )
        val prefix = chain.bytes() + listOf(fixture.bytes(overlap), fixture.bytes(activate))
        assertEquals(3, verify(prefix + fixture.bytes(fixture.signed(next))).inventory.copies.size)
        val old = next.copy(requiredSignerPolicy = next.requiredSignerPolicy.copy(members = listOf(rotations.member("catalog-old"))))
        reject(prefix + fixture.bytes(fixture.signed(old)))
    }

    @Test
    fun `an inventory operation cannot interleave a pending overlap or skip activation`() {
        val overlap = rotation(4, fixture.bytes(add), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new"))
        val extra = fixture.copy(add.manifest.restoreInventory.sources.single(), 3)
        val next = fixture.manifest(
            chain.base.genesis,
            5,
            fixture.bytes(overlap),
            "ADD_COPY",
            add.manifest.restoreInventory.copy(copies = add.manifest.restoreInventory.copies + extra),
            CatalogInventoryDeltaV1(emptyList(), listOf(extra.copyId)),
        )
        reject(chain.bytes() + listOf(fixture.bytes(overlap), fixture.bytes(fixture.signed(next))))
    }

    @Test
    fun `both overlap signatures are real and all historical required keys must remain in current trust`() {
        val overlap = rotation(4, fixture.bytes(add), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new"))
        overlap.signatures.indices.forEach { index ->
            val signatures = overlap.signatures.toMutableList()
            signatures[index] = signatures[index].copy(signatureBase64 = corrupt(signatures[index].signatureBase64))
            reject(chain.bytes() + fixture.bytes(overlap.copy(signatures = signatures)), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        }
        val noNewKey = bundles.signed(chain.base.current.body.copy(signers = chain.base.current.body.signers.filterNot { it.keyId == "catalog-new" }))
        reject(chain.bytes() + fixture.bytes(overlap), OfflineTrustBundleFailure.POLICY_MISMATCH, current = noNewKey)
    }

    @Test
    fun `schema one is never a downgrade after any schema two inventory generation`() {
        val oldManifest = rotations.manifest(chain.base.genesis, 4, fixture.bytes(add), "ROTATION_OVERLAP", listOf("catalog-old", "catalog-new"))
        reject(chain.bytes() + rotations.bytes(rotations.signed(oldManifest)))
    }

    @Test
    fun `every inventory writer and actual approver needs independent current authority as well as the predecessor`() {
        reject(chain.bytes(), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = rotations.policy(writers = listOf(bundles.EVENT_WRITER)))
        val currentOnly = rotations.policy(approvers = listOf("catalog-approver-a", "current-only"))
        reject(chain.bytes(), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = currentOnly)
        val nominated = register.manifest.copy(
            approvals = register.manifest.approvals.mapIndexed { index, approval ->
                if (index == 1) approval.copy(approverId = "current-only") else approval
            },
        )
        reject(chain.prefix + fixture.bytes(fixture.signed(nominated)), OfflineTrustBundleFailure.POLICY_MISMATCH, policy = currentOnly)
    }

    @Test
    fun `bundle commitment is recomputed rather than trusting a mutually matching source and copy hash claim`() {
        val inventory = register.manifest.restoreInventory
        val forged = inventory.copy(
            sources = inventory.sources.map { it.copy(bundleSha256 = "1".repeat(64)) },
            copies = inventory.copies.map { it.copy(bundleSha256 = "1".repeat(64)) },
        )
        rejectRegister(register.manifest.copy(restoreInventory = forged), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
    }

    @Test
    fun `genuinely signed omissions or retargeted predecessor copies are rejected by the integrated inventory fold`() {
        val inventory = add.manifest.restoreInventory
        val missing = inventory.copy(copies = inventory.copies.takeLast(1))
        reject(chain.prefix + listOf(fixture.bytes(register), fixture.bytes(fixture.signed(add.manifest.copy(restoreInventory = missing)))))
        val changed = inventory.copies.first().let { it.copy(media = it.media.copy(versionId = "changed-version")) }
        val retargeted = inventory.copy(copies = listOf(changed, inventory.copies.last()))
        reject(chain.prefix + listOf(fixture.bytes(register), fixture.bytes(fixture.signed(add.manifest.copy(restoreInventory = retargeted)))))
    }

    @Test
    fun `closed logical profile refuses physical WAL expiry destructive operations and nonempty history`() {
        val manifest = register.manifest
        val inventory = manifest.restoreInventory
        listOf("PHYSICAL_SNAPSHOT", "WAL_INTERVAL", "FIRESTORE_EXPORT").forEach { kind ->
            rejectRegister(manifest.copy(restoreInventory = inventory.copy(sources = inventory.sources.map { it.copy(kind = kind) })))
        }
        rejectRegister(manifest.copy(restoreInventory = inventory.copy(sources = inventory.sources.map { it.copy(state = "EXPIRED_PENDING_DESTRUCTION") })))
        rejectRegister(manifest.copy(operation = "REMOVE_SOURCE"))
        rejectRegister(manifest.copy(history = manifest.history.copy(epochSeals = manifest.history.epochSeals.copy(count = 1))))
        rejectRegister(manifest.copy(oldestRestoreTimeEpochSecond = manifest.oldestRestoreTimeEpochSecond + 1))
    }

    @Test
    fun `inventory schema profile canonicalizer exact predecessor and historical T0 bindings cannot be substituted`() {
        reject(chain.prefix + fixture.bytes(register.copy(schemaVersion = 3)), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectRegister(register.manifest.copy(schemaVersion = 1), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectRegister(register.manifest.copy(profile = "GENERIC_BACKUP"), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectRegister(register.manifest.copy(canonicalizerId = "generic-json"), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        rejectRegister(register.manifest.copy(generation = 3))
        rejectRegister(register.manifest.copy(previousEnvelopeSha256 = Sha256.hex(OfflineCatalogGenesisFixture.manifestBytes(chain.base.genesis.manifest))))
        rejectRegister(register.manifest.copy(initialTrustBundleEnvelopeSha256 = "1".repeat(64)), OfflineTrustBundleFailure.BUNDLE_HASH_MISMATCH)
    }

    @Test
    fun `raw entry still authenticates current root version and enforces the signed floor only at EOF`() {
        val current = bundles.signed(chain.base.current.body.copy(minimumCatalogHeadGeneration = 3))
        assertEquals(3L, verify(chain.bytes(), current = current).tail.generation)
        reject(chain.bytes().take(2), OfflineTrustBundleFailure.POLICY_MISMATCH, current = current)
        reject(chain.bytes(), OfflineTrustBundleFailure.VERSION_ROLLBACK, current = bundles.signed(current.body.copy(version = 8)))
        val tampered = current.copy(body = current.body.copy(issuedAtEpochSecond = current.body.issuedAtEpochSecond + 1))
        reject(chain.bytes(), OfflineTrustBundleFailure.INVALID_SIGNATURE, current = tampered)
    }

    @Test
    fun `signature tuples and complete signed inventory bytes are verified not merely parsed or hashed`() {
        reject(chain.prefix + fixture.bytes(register.copy(signatures = emptyList())))
        reject(chain.prefix + fixture.bytes(register.copy(signatures = register.signatures + register.signatures)))
        val signature = register.signatures.single()
        val altered = register.copy(signatures = listOf(signature.copy(signatureBase64 = corrupt(signature.signatureBase64))))
        reject(chain.prefix + fixture.bytes(altered), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        val tampered = register.copy(manifest = register.manifest.copy(operationToken = bundles.EVENT_WRITER))
        reject(chain.prefix + fixture.bytes(tampered), OfflineTrustBundleFailure.INVALID_SIGNATURE)
    }

    @Test
    fun `returned inventory snapshots cannot mutate evidence and borrowed input bytes are not retained`() {
        val bytes = chain.bytes()
        val expected = Sha256.hex(bytes.last())
        var requested = 0
        val source = sequence {
            bytes.forEach { input ->
                requested++
                yield(input)
                input.fill(0)
            }
        }.constrainOnce()
        val checked = OfflineCatalogInventoryChainVerifier.verifyInventoryChain(
            source,
            bundles.bytes(chain.base.initial),
            bundles.bytes(chain.base.current),
            rotations.policy(),
        )
        (checked.inventory.copies as MutableList<*>).clear()
        assertEquals(bytes.size, requested)
        assertEquals(expected, checked.tail.envelopeSha256)
        assertEquals(2, checked.inventory.copies.size)
        assertEquals(add.manifest.restoreInventory.sources, checked.inventory.sources)
    }

    @Test
    fun `a late supplier failure cannot turn the authenticated inventory prefix into success or expose private errors`() {
        val source = sequence {
            chain.bytes().forEach { yield(it) }
            throw IOException("private inventory listing detail")
        }
        val failure = assertThrows(OfflineTrustBundleException::class.java) {
            OfflineCatalogInventoryChainVerifier.verifyInventoryChain(
                source,
                bundles.bytes(chain.base.initial),
                bundles.bytes(chain.base.current),
                rotations.policy(),
            )
        }
        assertEquals(OfflineTrustBundleFailure.SUPPLIER_FAILURE, failure.code)
        assertNull(failure.cause)
        assertFalse(failure.message.orEmpty().contains("private inventory"))
    }

    @Test
    fun `small actual inventory chain obeys exact generation envelope aggregate and nested record budgets`() {
        val bytes = chain.bytes()
        val limits = rotations.limits().copy(
            maximumGenerations = bytes.size,
            maximumEnvelopeBytes = bytes.maxOf { it.size },
            maximumEncodedBytes = bytes.sumOf { it.size.toLong() },
            maximumManifestRecords = bytes.maxOf(rotations::manifestRecords),
        )
        assertEquals(3L, verify(bytes, policy = rotations.policy(limits)).tail.generation)
        listOf(
            limits.copy(maximumGenerations = limits.maximumGenerations - 1),
            limits.copy(maximumEnvelopeBytes = limits.maximumEnvelopeBytes - 1),
            limits.copy(maximumEncodedBytes = limits.maximumEncodedBytes - 1),
            limits.copy(maximumManifestRecords = limits.maximumManifestRecords - 1),
        ).forEach { lower -> reject(bytes, OfflineTrustBundleFailure.LIMIT_EXCEEDED, policy = rotations.policy(lower)) }
    }

    private fun rotation(generation: Long, previous: ByteArray, mode: String, keys: List<String>): OfflineCatalogInventoryEnvelopeV2 {
        val manifest = fixture.manifest(
            chain.base.genesis,
            generation,
            previous,
            if (mode == "SINGLE") "ROTATION_ACTIVATE" else "ROTATION_OVERLAP",
            add.manifest.restoreInventory,
            CatalogInventoryDeltaV1(emptyList(), emptyList()),
        )
        return fixture.signed(manifest.copy(requiredSignerPolicy = CatalogSignerPolicyV1(mode, "ALL_MEMBERS", keys.map(rotations::member))))
    }

    private fun corrupt(value: String): String {
        val bytes = Base64.getDecoder().decode(value)
        bytes[0] = (bytes[0].toInt() xor 1).toByte()
        return Base64.getEncoder().encodeToString(bytes)
    }

    private fun verify(
        bytes: List<ByteArray>,
        source: InventoryChainFixture = chain,
        current: OfflineTrustBundleEnvelopeV1 = source.base.current,
        policy: OfflineCatalogChainReaderPolicy = rotations.policy(),
    ): CheckedOfflineCatalogInventoryChain = OfflineCatalogInventoryChainVerifier.verifyInventoryChain(
        bytes.asSequence(),
        bundles.bytes(source.base.initial),
        bundles.bytes(current),
        policy,
    )

    private fun reject(
        bytes: List<ByteArray>,
        code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT,
        policy: OfflineCatalogChainReaderPolicy = rotations.policy(),
        current: OfflineTrustBundleEnvelopeV1 = chain.base.current,
    ) {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { verify(bytes, current = current, policy = policy) }
        assertEquals(code, failure.code)
    }

    private fun rejectRegister(manifest: OfflineCatalogInventoryManifestV2, code: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT) {
        reject(chain.prefix + fixture.bytes(fixture.signed(manifest)), code)
    }
}
