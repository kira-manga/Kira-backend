package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenMutation
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenProjection
import me.manga.kira.backend.complaint.domain.catalog.CatalogFrozenSignatureSlot
import me.manga.kira.backend.complaint.domain.catalog.CatalogGetRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListCursor
import me.manga.kira.backend.complaint.domain.catalog.CatalogListPage
import me.manga.kira.backend.complaint.domain.catalog.CatalogListRequest
import me.manga.kira.backend.complaint.domain.catalog.CatalogListedVersion
import me.manga.kira.backend.complaint.domain.catalog.CatalogLocalHead
import me.manga.kira.backend.complaint.domain.catalog.CatalogObjectMetadata
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPolicy
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackPort
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackProtocol
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackResult
import me.manga.kira.backend.complaint.domain.catalog.CatalogVersionBody
import me.manga.kira.backend.complaint.domain.catalog.LocalCatalogSnapshot
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogChainLimits
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogInventoryEnvelopeV2
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogDualLocationVerifier
import java.util.Base64

/** Existing genuine in-memory signatures plus synthetic provider observations; no real AWS or restore evidence. */
internal class CatalogReadbackFixture {
    val chain = OfflineCatalogInventoryFixture.chain()
    val bytes = chain.bytes()
    val initial = OfflineTrustBundleFixture.bytes(chain.base.initial)
    val current = OfflineTrustBundleFixture.bytes(chain.base.current)

    fun policy(
        limits: OfflineCatalogChainLimits = OfflineCatalogRotationFixture.limits(),
        pageSize: Int = 1,
        maximumPages: Int = 65536,
    ): CatalogReadbackPolicy = CatalogReadbackPolicy(
        OfflineCatalogRotationFixture.policy(limits),
        Sha256.hex(bytes.first()),
        EVALUATED_AT,
        RETAIN_UNTIL,
        pageSize,
        maximumPages,
    )

    fun head(generation: Int = bytes.size): CatalogLocalHead = CatalogLocalHead(generation.toLong(), Sha256.hex(bytes[generation - 1]))

    fun mutation(
        envelope: OfflineCatalogInventoryEnvelopeV2 = chain.generations.last(),
        signed: Boolean = true,
        schema: Int = envelope.schemaVersion,
        token: String = envelope.manifest.operationToken,
        manifestBytes: ByteArray = OfflineCatalogInventoryFixture.manifestBytes(envelope.manifest),
        manifestHash: String = Sha256.hex(manifestBytes),
        signedBytes: ByteArray? = if (signed) OfflineCatalogInventoryFixture.bytes(envelope) else null,
        signedHash: String? = signedBytes?.let(Sha256::hex),
        signatureSlots: List<CatalogFrozenSignatureSlot> = envelope.manifest.requiredSignerPolicy.members.map { member ->
            val signature = if (signed) envelope.signatures.firstOrNull { it.keyId == member.keyId && it.algorithmId == member.algorithmId } else null
            CatalogFrozenSignatureSlot(member.keyId, member.algorithmId, signature?.let { Base64.getDecoder().decode(it.signatureBase64) })
        },
    ): CatalogFrozenMutation = CatalogFrozenMutation(schema, token, manifestBytes, manifestHash, signedBytes, signedHash, signatureSlots)

    fun prepared(signed: Boolean = true, generation: Int = bytes.size): LocalCatalogSnapshot.Prepared =
        LocalCatalogSnapshot.Prepared(head(generation - 1), mutation(chain.generations[generation - 2], signed))

    fun preparedGenesis(
        envelope: OfflineCatalogGenesisEnvelopeV1 = chain.base.genesis,
        signed: Boolean = true,
        signatureSlots: List<CatalogFrozenSignatureSlot> = envelope.manifest.requiredSignerPolicy.members.map { member ->
            val signature = if (signed) envelope.signatures.firstOrNull { it.keyId == member.keyId && it.algorithmId == member.algorithmId } else null
            CatalogFrozenSignatureSlot(member.keyId, member.algorithmId, signature?.let { Base64.getDecoder().decode(it.signatureBase64) })
        },
    ): LocalCatalogSnapshot.PreparedGenesis {
        val manifest = OfflineCatalogGenesisFixture.manifestBytes(envelope.manifest)
        val bytes = if (signed) OfflineCatalogGenesisFixture.bytes(envelope) else null
        val mutation = CatalogFrozenMutation(
            envelope.schemaVersion,
            envelope.manifest.operationToken,
            manifest,
            Sha256.hex(manifest),
            bytes,
            bytes?.let(Sha256::hex),
            signatureSlots,
        )
        return LocalCatalogSnapshot.PreparedGenesis(mutation)
    }

    fun projection(generation: Int = bytes.size): LocalCatalogSnapshot.ProjectionPending {
        val token = if (generation == 1) chain.base.genesis.manifest.operationToken else chain.generations[generation - 2].manifest.operationToken
        return LocalCatalogSnapshot.ProjectionPending(head(generation), CatalogFrozenProjection(token, bytes[generation - 1], head(generation).envelopeSha256))
    }

    fun verify(
        provider: CatalogReadbackPort,
        local: LocalCatalogSnapshot = LocalCatalogSnapshot.Accepted(head()),
        policy: CatalogReadbackPolicy = policy(),
        initialBytes: ByteArray = initial,
        currentBytes: ByteArray = current,
    ): CatalogReadbackResult = CatalogDualLocationVerifier.verifyReadback(provider, initialBytes, currentBytes, policy, local)

    companion object {
        const val EVALUATED_AT = 1720010000L
        const val RETAIN_UNTIL = EVALUATED_AT + 86400
    }
}

/** Mutable fault-injection fixture only. Production never receives these collections as accepted evidence. */
internal class SyntheticCatalogReadbackPort(primary: List<ByteArray>, replica: List<ByteArray> = primary) : CatalogReadbackPort {
    val listRequests = mutableListOf<CatalogListRequest>()
    val getRequests = mutableListOf<CatalogGetRequest>()
    val primaryVersions = mutableListOf<CatalogListedVersion>()
    val replicaVersions = mutableListOf<CatalogListedVersion>()
    private val contents = mutableMapOf<CatalogGetRequest, ByteArray>()
    var transformPage: (CatalogListPage) -> CatalogListPage = { it }
    var transformMetadata: (CatalogObjectMetadata) -> CatalogObjectMetadata = { it }
    var onList: (CatalogListRequest) -> Unit = {}
    var onOpen: (CatalogGetRequest) -> Unit = {}
    var wrapBody: (CatalogGetRequest, CatalogVersionBody) -> CatalogVersionBody = { _, body -> body }
    var chunkSize: Int = Int.MAX_VALUE
    var openBodies = 0
        private set
    var closedBodies = 0
        private set
    var eofProbes = 0
        private set

    init {
        register("PRIMARY", primary, primaryVersions)
        register("REPLICA", replica, replicaVersions)
    }

    override fun listVersions(request: CatalogListRequest): CatalogListPage {
        check(openBodies == 0)
        listRequests.add(request)
        onList(request)
        val versions = if (request.location.role == "PRIMARY") primaryVersions else replicaVersions
        val offset = request.cursor?.let { cursor ->
            val index = versions.indexOfFirst { it.key == cursor.keyMarker && it.versionId == cursor.versionIdMarker }
            check(index >= 0)
            index + 1
        } ?: 0
        val page = versions.drop(offset).take(request.maxKeys)
        val truncated = offset + page.size < versions.size
        val next = if (truncated) page.last().let { CatalogListCursor(it.key, it.versionId ?: "missing") } else null
        return transformPage(CatalogListPage(request, page, emptyList(), truncated, next))
    }

    override fun openVersion(request: CatalogGetRequest): CatalogVersionBody {
        check(openBodies == 0)
        getRequests.add(request)
        onOpen(request)
        val bytes = contents.getValue(request).copyOf()
        val metadata = CatalogObjectMetadata(
            request,
            bytes.size.toLong(),
            "COMPLIANCE",
            CatalogReadbackFixture.RETAIN_UNTIL,
            if (request.location.role == "PRIMARY") "COMPLETED" else "REPLICA",
        )
        openBodies++
        val body = object : CatalogVersionBody {
            private var offset = 0
            private var closed = false

            override fun metadata(): CatalogObjectMetadata = transformMetadata(metadata)

            override fun read(destination: ByteArray, offset: Int, length: Int): Int {
                check(!closed)
                if (this.offset == bytes.size) {
                    eofProbes++
                    return -1
                }
                val count = minOf(length, chunkSize, bytes.size - this.offset)
                bytes.copyInto(destination, offset, this.offset, this.offset + count)
                this.offset += count
                return count
            }

            override fun close() {
                check(!closed)
                closed = true
                openBodies--
                closedBodies++
            }
        }
        return wrapBody(request, body)
    }

    fun replaceBytes(role: String, generation: Int, bytes: ByteArray) {
        val request = request(role, generation)
        contents[request] = bytes.copyOf()
        val versions = if (role == "PRIMARY") primaryVersions else replicaVersions
        versions[generation - 1] = versions[generation - 1].copy(contentLength = bytes.size.toLong())
    }

    private fun register(role: String, bytes: List<ByteArray>, versions: MutableList<CatalogListedVersion>) {
        bytes.forEachIndexed { index, value ->
            val request = request(role, index + 1)
            versions.add(CatalogListedVersion(request.key, request.versionId, value.size.toLong()))
            contents[request] = value.copyOf()
        }
    }

    private fun request(role: String, generation: Int): CatalogGetRequest = CatalogGetRequest(
        OfflineTrustBundleFixture.locations.single { it.role == role },
        CatalogReadbackProtocol.key(generation.toLong()),
        "catalog-version-$generation",
    )
}
