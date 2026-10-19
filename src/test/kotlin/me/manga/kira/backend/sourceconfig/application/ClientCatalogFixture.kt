package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.config.KiraClientCatalogProperties
import me.manga.kira.backend.config.KiraClientCatalogProperties.SnapshotPin
import me.manga.kira.backend.config.KiraClientCatalogProperties.VersionPin
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceArtifact
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalogRepository
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogEntry
import me.manga.kira.backend.sourceconfig.domain.model.SourceCatalogManifest
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.signing.DocumentSignatureCodec
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import me.manga.kira.backend.sourceconfig.signing.DocumentSigningInput
import me.manga.kira.backend.sourceconfig.signing.SourceCatalogSignatureCodec
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.security.KeyPairGenerator
import java.security.Signature
import java.time.Instant
import java.util.Base64
import java.util.UUID

/** Ephemeral signatures and synthetic stored generations only; not operational versions, pins or real persistence. */
internal class ClientCatalogFixture {
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    val documents = mock(PublishedDocumentRepository::class.java)
    val catalogs = mock(PublishedSourceCatalogRepository::class.java)
    val signer = mock(DocumentSigner::class.java)
    val documentRows = mutableMapOf<Long, PublishedDocument>()
    val manifestRows = mutableMapOf<Long, PublishedSourceCatalog>()
    var pointer: Long? = 102
    val source = SourceConfigFixtures.validGenericSource("ClientFixture").copy(displayName = "Synthetic café 漫画")
    val sourceJson = CanonicalJson.canonicalize(SourceConfig.serializer(), source)
    val artifact = PublishedSourceArtifact(source.api, 1, sourceJson, Sha256.hexUtf8(sourceJson), CanonicalJson.CANON_VERSION)
    val baseline = pair(100, null)
    val pinned = pair(101, baseline)
    val current = pair(102, pinned)

    init {
        listOf(baseline, pinned, current).forEach { pair ->
            documentRows[pair.document.documentRevision] = pair.document
            manifestRows[pair.manifest.catalogRevision] = pair.manifest
        }
        `when`(documents.latestPointer()).thenAnswer { pointer }
        `when`(documents.findByRevision(anyLong())).thenAnswer { documentRows[it.getArgument<Long>(0)] }
        `when`(catalogs.findByRevision(anyLong())).thenAnswer { manifestRows[it.getArgument<Long>(0)] }
        `when`(catalogs.findPublishedArtifact(artifact.api, artifact.sourceRevision)).thenReturn(artifact)
        `when`(signer.publicKeys()).thenReturn(mapOf(KEY_ID to Base64.getEncoder().encodeToString(keys.public.encoded)))
    }

    fun config() = KiraClientCatalogProperties(
        enabled = true,
        baseline = pin(baseline),
        currentVersions = listOf("9.0.2"),
        pinnedVersions = listOf(VersionPin("9.0.1", pin(pinned))),
    )

    fun query(properties: KiraClientCatalogProperties = config()) =
        ClientCatalogQueryService(properties, documents, catalogs, StoredCatalogPairVerifier(signer))

    fun pin(pair: StoredCatalogPair) = SnapshotPin(pair.document.documentRevision, pair.document.checksum, pair.manifest.checksum)

    private fun pair(revision: Long, previous: StoredCatalogPair?): StoredCatalogPair {
        val time = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(revision)
        val body = CanonicalJson.canonicalize(SourceConfigDocument.serializer(), SourceConfigDocument(1, time.toString(), revision, listOf(source)))
        val checksum = Sha256.hexUtf8(body)
        val priorRevision = previous?.document?.documentRevision
        val document = PublishedDocument(
            id = UUID(0, revision),
            documentRevision = revision,
            schemaVersion = 1,
            documentJson = body,
            checksum = checksum,
            canonVersion = CanonicalJson.CANON_VERSION,
            sourceCount = 1,
            createdBy = UUID(0, 1),
            createdAt = time,
            notes = null,
            signatureFormat = DocumentSignatureCodec.FORMAT,
            signatureAlgorithm = DocumentSignatureCodec.ALGORITHM,
            signingKeyId = KEY_ID,
            signatureBase64 = sign(
                DocumentSignatureCodec.payload(DocumentSigningInput(revision, checksum, time, priorRevision, previous?.document?.checksum, body)),
            ),
            previousDocumentRevision = priorRevision,
            previousDocumentChecksum = previous?.document?.checksum,
        )
        return StoredCatalogPair(document, manifest(document, previous?.manifest))
    }

    private fun manifest(document: PublishedDocument, previous: PublishedSourceCatalog?): PublishedSourceCatalog {
        val entry = SourceCatalogEntry(
            source.api,
            1,
            artifact.checksum,
            0,
            "active",
            "generic",
            KEY_ID,
            sign(SourceCatalogSignatureCodec.sourcePayload(source.api, 1, artifact.checksum, sourceJson)),
        )
        val body = CanonicalJson.canonicalize(
            SourceCatalogManifest.serializer(),
            SourceCatalogManifest(1, 1, document.documentRevision, document.createdAt.toString(), listOf(entry)),
        )
        val checksum = Sha256.hexUtf8(body)
        return PublishedSourceCatalog(
            id = UUID(1, document.documentRevision),
            catalogRevision = document.documentRevision,
            schemaVersion = 1,
            sourceSchemaVersion = 1,
            manifestJson = body,
            checksum = checksum,
            canonVersion = CanonicalJson.CANON_VERSION,
            sourceCount = 1,
            createdBy = document.createdBy,
            createdAt = document.createdAt,
            signatureFormat = SourceCatalogSignatureCodec.MANIFEST_FORMAT,
            signatureAlgorithm = DocumentSignatureCodec.ALGORITHM,
            signingKeyId = KEY_ID,
            signatureBase64 = sign(
                SourceCatalogSignatureCodec.manifestPayload(
                    document.documentRevision,
                    previous?.catalogRevision,
                    previous?.checksum,
                    checksum,
                    document.createdAt,
                    body,
                ),
            ),
            previousCatalogRevision = previous?.catalogRevision,
            previousCatalogChecksum = previous?.checksum,
        )
    }

    private fun sign(payload: ByteArray): String {
        val signature = Signature.getInstance(DocumentSignatureCodec.ALGORITHM)
        signature.initSign(keys.private)
        signature.update(payload)
        return Base64.getEncoder().encodeToString(signature.sign())
    }

    private companion object {
        const val KEY_ID = "client-catalog-synthetic"
    }
}
