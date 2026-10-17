package me.manga.kira.backend.sourceconfig.application

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.config.KiraClientCatalogProperties.SnapshotPin
import me.manga.kira.backend.sourceconfig.domain.PublishedDocument
import me.manga.kira.backend.sourceconfig.domain.PublishedSourceCatalog
import me.manga.kira.backend.sourceconfig.signing.DocumentSignatureCodec
import me.manga.kira.backend.sourceconfig.signing.DocumentSigner
import me.manga.kira.backend.sourceconfig.signing.DocumentSigningInput
import me.manga.kira.backend.sourceconfig.signing.SourceCatalogSignatureCodec
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

/** Read-only integrity check of original stored envelopes. Never signs, canonicalizes again or repairs historical bytes. */
@Component
class StoredCatalogPairVerifier(private val signer: DocumentSigner) {
    internal fun verify(pair: StoredCatalogPair, revision: Long, pin: SnapshotPin?) {
        val (document, manifest) = pair
        check(document.documentRevision == revision && manifest.catalogRevision == revision)
        check(document.canonVersion == CanonicalJson.CANON_VERSION && manifest.canonVersion == CanonicalJson.CANON_VERSION)
        check(document.schemaVersion == 1 && manifest.schemaVersion == 1 && manifest.sourceSchemaVersion == 1)
        check(document.createdAt == manifest.createdAt)
        check(document.checksum == Sha256.hexUtf8(document.documentJson) && manifest.checksum == Sha256.hexUtf8(manifest.manifestJson))
        if (pin != null) {
            check(pin.revision == revision && document.checksum == pin.documentChecksum && manifest.checksum == pin.manifestChecksum)
        }
        check(document.signatureFormat == DocumentSignatureCodec.FORMAT && manifest.signatureFormat == SourceCatalogSignatureCodec.MANIFEST_FORMAT)
        check(document.signatureAlgorithm == DocumentSignatureCodec.ALGORITHM && manifest.signatureAlgorithm == DocumentSignatureCodec.ALGORITHM)
        checkChain(revision, document.previousDocumentRevision, document.previousDocumentChecksum)
        checkChain(revision, manifest.previousCatalogRevision, manifest.previousCatalogChecksum)
        val keys = signer.publicKeys()
        verifySignature(
            keys,
            document.signingKeyId,
            document.signatureBase64,
            DocumentSignatureCodec.payload(
                DocumentSigningInput(
                    revision,
                    document.checksum,
                    document.createdAt,
                    document.previousDocumentRevision,
                    document.previousDocumentChecksum,
                    document.documentJson,
                ),
            ),
        )
        verifySignature(
            keys,
            manifest.signingKeyId,
            manifest.signatureBase64,
            SourceCatalogSignatureCodec.manifestPayload(
                revision,
                manifest.previousCatalogRevision,
                manifest.previousCatalogChecksum,
                manifest.checksum,
                manifest.createdAt,
                manifest.manifestJson,
            ),
        )
    }

    private fun verifySignature(keys: Map<String, String>, keyId: String?, signature: String?, payload: ByteArray) {
        check(keyId != null && KEY_ID.matches(keyId))
        check(signature != null && signature.length == 88)
        val bytes = Base64.getDecoder().decode(signature)
        check(bytes.size == 64 && Base64.getEncoder().encodeToString(bytes) == signature)
        val encodedKey = checkNotNull(keys[keyId])
        check(encodedKey.length <= 512)
        val key = KeyFactory.getInstance(DocumentSignatureCodec.ALGORITHM).generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(encodedKey)))
        val verifier = Signature.getInstance(DocumentSignatureCodec.ALGORITHM)
        verifier.initVerify(key)
        verifier.update(payload)
        check(verifier.verify(bytes))
    }

    private fun checkChain(revision: Long, previous: Long?, checksum: String?) {
        check((previous == null && checksum == null) || (previous != null && previous > 0 && previous < revision && checksum != null && HASH.matches(checksum)))
    }

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
        val HASH = Regex("[0-9a-f]{64}")
    }
}

internal data class StoredCatalogPair(val document: PublishedDocument, val manifest: PublishedSourceCatalog)
