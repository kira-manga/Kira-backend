package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.complaint.domain.catalog.CatalogBackupArtifactV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogInventoryDeltaV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalBundleV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalCopyV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryContext
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalInventoryReducer
import me.manga.kira.backend.complaint.domain.catalog.CatalogLogicalSourceV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogRestoreInventoryV1
import me.manga.kira.backend.complaint.domain.catalog.CatalogS3ObjectVersionV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainProtocol
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class CatalogLogicalInventoryReducerTest {
    private val context = CatalogLogicalInventoryContext(id(1), id(2), 100, 200, 4096)
    private val empty = CatalogRestoreInventoryV1(emptyList(), emptyList())
    private val noDelta = CatalogInventoryDeltaV1(emptyList(), emptyList())

    @Test
    fun `register source and append copies preserve the complete predecessor inventory`() {
        val first = inventory(source())
        assertEquals(first, register(empty, first))
        val another = copyFor(first.sources.single(), id(101), locationClass = "OFFSITE")
        val withCopy = first.copy(copies = first.copies + another)
        assertEquals(withCopy, addCopy(first, withCopy, another.copyId))
        val second = source(id(11), "next")
        val withSource = CatalogRestoreInventoryV1(withCopy.sources + second, withCopy.copies + copyFor(second, id(102)))
        assertEquals(withSource, register(withCopy, withSource, second.sourceId, id(102)))
    }

    @Test
    fun `both rotation operations require empty delta and exact unchanged inventory`() {
        val current = inventory(source())
        listOf(OfflineCatalogChainProtocol.ROTATION_OVERLAP, OfflineCatalogChainProtocol.ROTATION_ACTIVATE).forEach { operation ->
            assertEquals(current, reduce(current, current, operation, noDelta))
            reject { reduce(current, current, operation, CatalogInventoryDeltaV1(emptyList(), listOf(id(101)))) }
            val extra = current.copy(copies = current.copies + copyFor(current.sources.single(), id(101)))
            reject { reduce(current, extra, operation, noDelta) }
            val changed = current.copy(sources = listOf(current.sources.single().copy(restorePointEpochSecond = 151)))
            reject { reduce(current, changed, operation, noDelta) }
        }
        assertEquals(empty, reduce(empty, empty, OfflineCatalogChainProtocol.ROTATION_ACTIVATE, noDelta))
    }

    @Test
    fun `unsupported destructive and genesis operations are refused`() {
        listOf("REMOVE_COPY", "EXPIRE_SOURCE", "DESTROY_SOURCE", "GENESIS", "REGISTER_WRITER", "register_source", "").forEach {
            reject { reduce(empty, empty, it, noDelta) }
        }
    }

    @Test
    fun `delta cardinality and exact declared added IDs are mandatory`() {
        val proposed = inventory(source())
        listOf(
            noDelta,
            CatalogInventoryDeltaV1(listOf(id(10)), emptyList()),
            CatalogInventoryDeltaV1(emptyList(), listOf(id(100))),
            CatalogInventoryDeltaV1(listOf(id(10), id(10)), listOf(id(100))),
            CatalogInventoryDeltaV1(listOf(id(10)), listOf(id(100), id(101))),
            CatalogInventoryDeltaV1(listOf(id(11)), listOf(id(100))),
            CatalogInventoryDeltaV1(listOf(id(10)), listOf(id(101))),
        ).forEach { delta -> reject { reduce(empty, proposed, CatalogLogicalInventoryProtocol.REGISTER_SOURCE, delta) } }
        reject { reduce(empty, proposed, CatalogLogicalInventoryProtocol.ADD_COPY, CatalogInventoryDeltaV1(emptyList(), listOf(id(100)))) }
        reject { register(proposed, proposed) }
        reject { addCopy(proposed, proposed, id(100)) }
    }

    @Test
    fun `register cannot attach its only added copy to a different predecessor source`() {
        val first = inventory(source())
        val second = source(id(11), "next")
        val wrong = CatalogRestoreInventoryV1(first.sources + second, first.copies + copyFor(first.sources.single(), id(101)))
        reject { register(first, wrong, second.sourceId, id(101)) }
        val orphan = first.copy(copies = first.copies + copyFor(second, id(101)))
        reject { addCopy(first, orphan, id(101)) }
        reject { register(empty, CatalogRestoreInventoryV1(listOf(second), emptyList()), second.sourceId, id(101)) }
    }

    @Test
    fun `prior sources and copies cannot be silently omitted or replaced`() {
        val first = inventory(source())
        val second = source(id(11), "next")
        reject { register(first, inventory(second, id(101)), second.sourceId, id(101)) }
        val extra = copyFor(first.sources.single(), id(101))
        reject { addCopy(first, first.copy(copies = listOf(extra)), extra.copyId) }
        val changedSource = first.copy(sources = listOf(first.sources.single().copy(restorePointEpochSecond = 151)), copies = first.copies + extra)
        reject { addCopy(first, changedSource, extra.copyId) }
        val changedCopy = first.copies.single().copy(locationClass = "OPERATOR")
        reject { addCopy(first, first.copy(copies = listOf(changedCopy, extra)), extra.copyId) }
        val changedVersion = first.copies.single().copy(manifest = first.copies.single().manifest.copy(versionId = "replacement"))
        reject { addCopy(first, first.copy(copies = listOf(changedVersion, extra)), extra.copyId) }
    }

    @Test
    fun `canonical distinct sorted IDs are required in predecessor and successor`() {
        val a = source()
        val b = source(id(11), "next")
        val full = CatalogRestoreInventoryV1(listOf(a, b), listOf(copyFor(a), copyFor(b, id(101))))
        listOf(
            full.copy(sources = full.sources.reversed()),
            full.copy(copies = full.copies.reversed()),
            full.copy(sources = listOf(a, a)),
            full.copy(copies = listOf(full.copies.first(), full.copies.first())),
        ).forEach { invalid ->
            reject { reduce(full, invalid, OfflineCatalogChainProtocol.ROTATION_OVERLAP, noDelta) }
            reject { reduce(invalid, full, OfflineCatalogChainProtocol.ROTATION_OVERLAP, noDelta) }
        }
        listOf("00000000-0000-0000-0000-000000000000", "AAAAAAAA-AAAA-4AAA-8AAA-AAAAAAAAAAAA", "not-an-id").forEach { invalid ->
            val badSource = a.copy(sourceId = invalid)
            reject { register(empty, inventory(badSource), invalid) }
            val badCopy = copyFor(a).copy(copyId = invalid)
            reject { register(empty, CatalogRestoreInventoryV1(listOf(a), listOf(badCopy)), a.sourceId, invalid) }
        }
    }

    @Test
    fun `source and copy states are ACCEPTED claims only`() {
        val original = source()
        listOf("EXPIRED_PENDING_DESTRUCTION", "DESTROYED", "REGISTERED", "accepted", "").forEach { state ->
            reject { register(empty, inventory(original.copy(state = state))) }
            val current = inventory(original)
            reject { register(empty, current.copy(copies = listOf(current.copies.single().copy(state = state)))) }
        }
        reject { register(empty, inventory(original.copy(kind = "UNSUPPORTED_BACKUP_KIND"))) }
        val current = inventory(original)
        reject { register(empty, current.copy(copies = listOf(current.copies.single().copy(locationClass = "UNKNOWN")))) }
        listOf("PRIMARY", "REPLICA", "OPERATOR", "OFFSITE").forEach { locationClass ->
            val candidate = current.copy(copies = listOf(current.copies.single().copy(locationClass = locationClass)))
            assertEquals(candidate, register(empty, candidate))
        }
    }

    @Test
    fun `context identity and inclusive inherited restore floor and creation boundaries are enforced`() {
        listOf(context.oldestRestoreTimeEpochSecond, context.createdAtEpochSecond).forEach { time ->
            val candidate = inventory(source().copy(restorePointEpochSecond = time))
            assertEquals(candidate, register(empty, candidate))
        }
        listOf(
            source().copy(databaseIdentity = id(3)),
            source().copy(restoreIdentity = id(3)),
            source().copy(restorePointEpochSecond = context.oldestRestoreTimeEpochSecond - 1),
            source().copy(restorePointEpochSecond = context.createdAtEpochSecond + 1),
            source().copy(restorePointEpochSecond = Long.MAX_VALUE),
        ).forEach { invalid -> reject(OfflineTrustBundleFailure.POLICY_MISMATCH) { register(empty, inventory(invalid)) } }
        reject { register(empty, inventory(source().copy(databaseIdentity = "invalid"))) }
        reject { register(empty, inventory(source().copy(restoreIdentity = "invalid"))) }
    }

    @Test
    fun `invalid context and budgets cannot lower type or time requirements`() {
        listOf(
            context.copy(databaseIdentity = "invalid"),
            context.copy(restoreIdentity = "invalid"),
            context.copy(oldestRestoreTimeEpochSecond = -1),
            context.copy(oldestRestoreTimeEpochSecond = 201),
            context.copy(createdAtEpochSecond = -1),
            context.copy(createdAtEpochSecond = CatalogLogicalInventoryProtocol.LAST_EPOCH_SECOND + 1),
            context.copy(maximumRecords = 0),
            context.copy(maximumRecords = -1),
            context.copy(maximumRecords = 4097),
            context.copy(maximumRecords = Int.MAX_VALUE),
        ).forEach { invalid ->
            reject(OfflineTrustBundleFailure.INVALID_POLICY) { reduce(empty, empty, OfflineCatalogChainProtocol.ROTATION_OVERLAP, noDelta, invalid) }
        }
    }

    @Test
    fun `raw backup manifest pin stays distinct from the syntax-only catalog bundle commitment`() {
        val proposed = inventory(source())
        val actual = register(empty, proposed)
        assertEquals("a".repeat(64), actual.sources.single().bundle.manifest.sha256)
        assertEquals("d".repeat(64), actual.sources.single().bundleSha256)
        // This is deliberately a semantic fixture, not computed or authenticated cryptographic evidence.
        assertEquals(proposed, actual)
        listOf("D".repeat(64), "d".repeat(63), "not-a-hash").forEach { invalid ->
            reject { register(empty, inventory(source().copy(bundleSha256 = invalid))) }
        }
        val copyMismatch = proposed.copies.single().copy(bundleSha256 = "e".repeat(64))
        reject { register(empty, proposed.copy(copies = listOf(copyMismatch))) }
    }

    @Test
    fun `backup schema and fixed same-stem roles reject diagnostic sidecars and path instructions`() {
        val original = source()
        val bundle = original.bundle
        listOf(
            bundle.copy(schema = "unknown"),
            bundle.copy(manifest = bundle.manifest.copy(name = "backup.dump.manifest")),
            bundle.copy(manifest = bundle.manifest.copy(name = "backup.bundle.sha256")),
            bundle.copy(manifest = bundle.manifest.copy(name = "/backup.bundle.json")),
            bundle.copy(dump = bundle.dump.copy(name = "other.dump")),
            bundle.copy(dump = bundle.dump.copy(name = "../backup.dump")),
            bundle.copy(media = bundle.media.copy(name = "other.media.tar.gz")),
            bundle.copy(media = bundle.media.copy(name = "backup.media.tar.gz.sha256")),
        ).forEach { invalid -> reject { register(empty, inventory(original.copy(bundle = invalid))) } }
        listOf("x", "a".repeat(96), "A_backup-09").forEach { name ->
            val valid = inventory(source(name = name))
            assertEquals(valid, register(empty, valid))
        }
        listOf("", "a".repeat(97), "_backup", "back.up", "back up", "é").forEach { name ->
            reject { register(empty, inventory(source(name = name))) }
        }
    }

    @Test
    fun `artifact length and digest bounds include every fixed role`() {
        val original = source()
        val bundle = original.bundle
        val invalid = listOf(
            bundle.copy(manifest = bundle.manifest.copy(bytes = 0)),
            bundle.copy(manifest = bundle.manifest.copy(bytes = 4097)),
            bundle.copy(dump = bundle.dump.copy(bytes = -1)),
            bundle.copy(media = bundle.media.copy(bytes = 0)),
            bundle.copy(manifest = bundle.manifest.copy(sha256 = "A".repeat(64))),
            bundle.copy(dump = bundle.dump.copy(sha256 = "b".repeat(63))),
            bundle.copy(media = bundle.media.copy(sha256 = "c".repeat(65))),
        )
        invalid.forEach { value -> reject { register(empty, inventory(original.copy(bundle = value))) } }
        val exact = original.copy(bundle = bundle.copy(manifest = bundle.manifest.copy(bytes = 4096), dump = bundle.dump.copy(bytes = Long.MAX_VALUE)))
        assertEquals(inventory(exact), register(empty, inventory(exact)))
    }

    @Test
    fun `each copied role must match the exact source name bytes and hash`() {
        val proposed = inventory(source())
        listOf<(CatalogS3ObjectVersionV1) -> CatalogS3ObjectVersionV1>(
            { it.copy(bytes = it.bytes + 1) },
            { it.copy(sha256 = "e".repeat(64)) },
            { it.copy(key = it.key.replace("backup", "other")) },
        ).forEach { change ->
            (0..2).forEach { role ->
                val copy = changeRole(proposed.copies.single(), role, change)
                reject { register(empty, proposed.copy(copies = listOf(copy))) }
            }
        }
        val mixed = proposed.copies.single().copy(media = copyFor(source(name = "other"), id(101)).media)
        reject { register(empty, proposed.copy(copies = listOf(mixed))) }
    }

    @Test
    fun `copy roles require one coherent account region bucket and nonempty key prefix`() {
        val proposed = inventory(source())
        listOf<(CatalogS3ObjectVersionV1) -> CatalogS3ObjectVersionV1>(
            { it.copy(accountId = "999999999999") },
            { it.copy(region = "us-east-1") },
            { it.copy(bucket = "other-backups") },
            { it.copy(key = "other/backup.dump") },
        ).forEach { change ->
            reject { register(empty, proposed.copy(copies = listOf(proposed.copies.single().copy(dump = change(proposed.copies.single().dump))))) }
        }
        listOf("backup.dump", "/backup.dump", "a//backup.dump", "a/./backup.dump", "a/../backup.dump", "a/backup.dump/", "a?/backup.dump").forEach { key ->
            val bad = proposed.copies.single().copy(dump = proposed.copies.single().dump.copy(key = key))
            reject { register(empty, proposed.copy(copies = listOf(bad))) }
        }
    }

    @Test
    fun `provider identity key and version syntax is bounded without provider validation`() {
        val proposed = inventory(source())
        listOf<(CatalogS3ObjectVersionV1) -> CatalogS3ObjectVersionV1>(
            { it.copy(accountId = "123") },
            { it.copy(region = "EU-WEST-1") },
            { it.copy(bucket = "bad..bucket") },
            { it.copy(versionId = "") },
            { it.copy(versionId = "null") },
            { it.copy(versionId = "has space") },
            { it.copy(versionId = "has\nnewline") },
            { it.copy(versionId = "é") },
            { it.copy(versionId = "v".repeat(1025)) },
            { it.copy(key = "a".repeat(1025)) },
            { it.copy(key = "é/backup.dump") },
        ).forEach { change ->
            val bad = proposed.copies.single().copy(dump = change(proposed.copies.single().dump))
            reject { register(empty, proposed.copy(copies = listOf(bad))) }
        }
        val source = proposed.sources.single()
        val longestName = maxOf(source.bundle.manifest.name.length, source.bundle.dump.name.length, source.bundle.media.name.length)
        val prefix = "a".repeat(1024 - longestName - 1)
        val exact = CatalogRestoreInventoryV1(listOf(source), listOf(copyFor(source, prefix = prefix, version = "v".repeat(1024))))
        assertEquals(exact, register(empty, exact))
        val tooLong = exact.copy(copies = listOf(copyFor(source, prefix = prefix + "a")))
        reject { register(empty, tooLong) }
    }

    @Test
    fun `coordinate aliases cannot mint extra copy identities but replica version IDs may match`() {
        val before = inventory(source())
        val first = before.copies.single()
        val alias = first.copy(copyId = id(101), locationClass = "OPERATOR")
        reject { addCopy(before, before.copy(copies = listOf(first, alias)), alias.copyId) }
        val replica = first.copy(
            copyId = id(101),
            locationClass = "REPLICA",
            manifest = first.manifest.copy(bucket = "replica-backups"),
            dump = first.dump.copy(bucket = "replica-backups"),
            media = first.media.copy(bucket = "replica-backups"),
        )
        val after = before.copy(copies = listOf(first, replica))
        assertEquals(after, addCopy(before, after, replica.copyId))
        val second = source(id(11))
        val aliasAcrossSources = first.copy(copyId = id(101), sourceId = second.sourceId)
        val bad = CatalogRestoreInventoryV1(before.sources + second, before.copies + aliasAcrossSources)
        reject { register(before, bad, second.sourceId, aliasAcrossSources.copyId) }
    }

    @Test
    fun `record budget counts nested source bundle and three-role reference objects exactly`() {
        val proposed = inventory(source())
        assertEquals(proposed, register(empty, proposed, policy = context.copy(maximumRecords = 10)))
        reject(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { register(empty, proposed, policy = context.copy(maximumRecords = 9)) }
        assertEquals(empty, reduce(empty, empty, OfflineCatalogChainProtocol.ROTATION_OVERLAP, noDelta, context.copy(maximumRecords = 1)))
        val added = proposed.copy(copies = proposed.copies + copyFor(proposed.sources.single(), id(101)))
        assertEquals(added, addCopy(proposed, added, id(101), context.copy(maximumRecords = 14)))
        reject(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { addCopy(proposed, added, id(101), context.copy(maximumRecords = 13)) }
    }

    @Test
    fun `4096 inventory objects fit but one lower configured inventory budget refuses`() {
        // The raw parser separately counts inherited manifest objects; this checks only the reducer's inventory budget.
        val sources = (0 until 455).map { source(id(1000 + it), "backup$it") }
        val copies = sources.mapIndexed { index, source -> copyFor(source, id(10000 + index)) }
        val after = CatalogRestoreInventoryV1(sources, copies)
        val before = CatalogRestoreInventoryV1(sources.dropLast(1), copies.dropLast(1))
        assertEquals(after, register(before, after, sources.last().sourceId, copies.last().copyId))
        reject(OfflineTrustBundleFailure.LIMIT_EXCEEDED) {
            register(before, after, sources.last().sourceId, copies.last().copyId, context.copy(maximumRecords = 4095))
        }
    }

    @Test
    fun `oversized claimed list counts are rejected before access or copying without integer overflow`() {
        var accessed = false
        val huge = object : AbstractList<CatalogLogicalSourceV1>() {
            override val size: Int = Int.MAX_VALUE

            override fun get(index: Int): CatalogLogicalSourceV1 {
                accessed = true
                error("Oversized inventory must not be traversed")
            }
        }
        val oversized = CatalogRestoreInventoryV1(huge, emptyList())
        reject(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { reduce(empty, oversized, OfflineCatalogChainProtocol.ROTATION_OVERLAP, noDelta) }
        reject(OfflineTrustBundleFailure.LIMIT_EXCEEDED) { reduce(oversized, empty, OfflineCatalogChainProtocol.ROTATION_OVERLAP, noDelta) }
        assertFalse(accessed)
    }

    @Test
    fun `result snapshots both caller-owned collections and keeps scalar evidence independent`() {
        val source = source()
        val sources = mutableListOf(source)
        val copies = mutableListOf(copyFor(source))
        val proposed = CatalogRestoreInventoryV1(sources, copies)
        val sourceIds = mutableListOf(source.sourceId)
        val copyIds = mutableListOf(copies.single().copyId)
        val result = reduce(empty, proposed, CatalogLogicalInventoryProtocol.REGISTER_SOURCE, CatalogInventoryDeltaV1(sourceIds, copyIds))
        assertNotSame(sources, result.sources)
        assertNotSame(copies, result.copies)
        sources.clear()
        copies.clear()
        sourceIds.clear()
        copyIds.clear()
        assertEquals(inventory(source), result)
    }

    @Test
    fun `rejection carries only a fixed failure code and never submitted record contents`() {
        val secretMarker = "SYNTHETIC_DO_NOT_ECHO"
        val failure = reject { register(empty, inventory(source().copy(kind = secretMarker))) }
        assertFalse(failure.message.orEmpty().contains(secretMarker))
        assertNull(failure.cause)
    }

    private fun register(
        previous: CatalogRestoreInventoryV1,
        proposed: CatalogRestoreInventoryV1,
        sourceId: String = id(10),
        copyId: String = id(100),
        policy: CatalogLogicalInventoryContext = context,
    ): CatalogRestoreInventoryV1 = reduce(
        previous,
        proposed,
        CatalogLogicalInventoryProtocol.REGISTER_SOURCE,
        CatalogInventoryDeltaV1(listOf(sourceId), listOf(copyId)),
        policy,
    )

    private fun addCopy(
        previous: CatalogRestoreInventoryV1,
        proposed: CatalogRestoreInventoryV1,
        copyId: String,
        policy: CatalogLogicalInventoryContext = context,
    ): CatalogRestoreInventoryV1 = reduce(
        previous,
        proposed,
        CatalogLogicalInventoryProtocol.ADD_COPY,
        CatalogInventoryDeltaV1(emptyList(), listOf(copyId)),
        policy,
    )

    private fun reduce(
        previous: CatalogRestoreInventoryV1,
        proposed: CatalogRestoreInventoryV1,
        operation: String,
        delta: CatalogInventoryDeltaV1,
        policy: CatalogLogicalInventoryContext = context,
    ): CatalogRestoreInventoryV1 = CatalogLogicalInventoryReducer.reduce(previous, proposed, operation, delta, policy)

    private fun reject(expected: OfflineTrustBundleFailure = OfflineTrustBundleFailure.INVALID_DOCUMENT, action: () -> Unit): OfflineTrustBundleException {
        val failure = assertThrows(OfflineTrustBundleException::class.java) { action() }
        assertEquals(expected, failure.code)
        return failure
    }

    private fun source(sourceId: String = id(10), name: String = "backup"): CatalogLogicalSourceV1 = CatalogLogicalSourceV1(
        sourceId,
        CatalogLogicalInventoryProtocol.SOURCE_KIND,
        context.databaseIdentity,
        context.restoreIdentity,
        150,
        CatalogLogicalInventoryProtocol.CLAIMED_STATE,
        "d".repeat(64),
        CatalogLogicalBundleV1(
            CatalogLogicalInventoryProtocol.BACKUP_SCHEMA,
            CatalogBackupArtifactV1("$name.bundle.json", 256, "a".repeat(64)),
            CatalogBackupArtifactV1("$name.dump", 1024, "b".repeat(64)),
            CatalogBackupArtifactV1("$name.media.tar.gz", 128, "c".repeat(64)),
        ),
    )

    private fun inventory(source: CatalogLogicalSourceV1, copyId: String = id(100)): CatalogRestoreInventoryV1 =
        CatalogRestoreInventoryV1(listOf(source), listOf(copyFor(source, copyId)))

    private fun copyFor(
        source: CatalogLogicalSourceV1,
        copyId: String = id(100),
        locationClass: String = "PRIMARY",
        prefix: String = "backups/$copyId",
        version: String = "version-a",
    ): CatalogLogicalCopyV1 {
        fun objectVersion(artifact: CatalogBackupArtifactV1): CatalogS3ObjectVersionV1 = CatalogS3ObjectVersionV1(
            "123456789012",
            "eu-west-1",
            "primary-backups",
            "$prefix/${artifact.name}",
            version,
            artifact.bytes,
            artifact.sha256,
        )
        return CatalogLogicalCopyV1(
            copyId,
            source.sourceId,
            locationClass,
            CatalogLogicalInventoryProtocol.CLAIMED_STATE,
            source.bundleSha256,
            objectVersion(source.bundle.manifest),
            objectVersion(source.bundle.dump),
            objectVersion(source.bundle.media),
        )
    }

    private fun changeRole(copy: CatalogLogicalCopyV1, role: Int, change: (CatalogS3ObjectVersionV1) -> CatalogS3ObjectVersionV1): CatalogLogicalCopyV1 =
        when (role) {
            0 -> copy.copy(manifest = change(copy.manifest))
            1 -> copy.copy(dump = change(copy.dump))
            else -> copy.copy(media = change(copy.media))
        }

    private fun id(value: Int): String = "00000000-0000-4000-8000-${value.toString(16).padStart(12, '0')}"
}
