package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleBodyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleException
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleFailure
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleCrypto
import me.manga.kira.backend.complaint.infrastructure.catalog.OfflineTrustBundleVerifier
import me.manga.kira.backend.complaint.parsing.catalog.OfflineTrustBundleParser
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.math.BigInteger
import java.security.MessageDigest
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.util.Base64

class OfflineTrustBundleVerifierTest {
    private val fixture = OfflineTrustBundleFixture

    @Test
    fun `unchanged old public vector proves only low-level frame hash and signature crypto`() {
        val rootSpki = Base64.getDecoder().decode(fixture.golden("root-spki.base64").decodeToString().trim())
        val bodyBytes = fixture.golden("body.json")
        val frame = fixture.golden("frame.hex").decodeToString().trim().chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val signature = Base64.getDecoder().decode(fixture.golden("signature.base64").decodeToString().trim())
        OfflineTrustBundleCrypto.verify(OfflineTrustBundleCrypto.publicKey(rootSpki), frame, signature)
        assertArrayEquals(frame, OfflineTrustBundleCrypto.signatureFrame(fixture.ROOT_ID, bodyBytes))
        assertArrayEquals(frame, fixture.independentFrame(fixture.ROOT_ID, bodyBytes))
        assertEquals(fixture.golden("body.sha256").decodeToString().trim(), Sha256.hex(bodyBytes))
        assertEquals(fixture.golden("frame.sha256").decodeToString().trim(), Sha256.hex(frame))
    }

    @Test
    fun `old key-only public envelope is rejected with no default bootstrap authority`() {
        val old = fixture.golden("envelope.json")
        val parseFailure = assertThrows(OfflineTrustBundleException::class.java) { OfflineTrustBundleParser.parse(old) }
        assertEquals(OfflineTrustBundleFailure.MALFORMED_INPUT, parseFailure.code)
        val oldRoot = Base64.getDecoder().decode(fixture.golden("root-spki.base64").decodeToString().trim())
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineTrustBundleVerifier.verify(old, fixture.policy(rootSpki = oldRoot)) }
        assertEquals(OfflineTrustBundleFailure.MALFORMED_INPUT, failure.code)
    }

    @Test
    fun `fresh independently generated synthetic root and catalog keys verify`() {
        val signed = fixture.signed()
        val result = OfflineTrustBundleVerifier.verify(fixture.bytes(signed), fixture.policy())
        assertEquals(signed.body, result.body)
        assertEquals(42L, result.body.minimumCatalogHeadGeneration) // Signed head floor, not an accepted head or historical-object floor.
    }

    @Test
    fun `policy input and result arrays and lists cannot mutate checked material`() {
        val rootSpki = fixture.root.public.encoded
        val locations = fixture.locations.toMutableList()
        val policy = fixture.policy(rootSpki = rootSpki, catalogLocations = locations)
        rootSpki.fill(0)
        locations.clear()
        policy.rootPublicKeySpki.fill(0)
        (policy.expectedCatalogLocations as MutableList<OfflineCatalogLocationV1>).clear()
        val input = fixture.bytes(fixture.signed())
        val result = OfflineTrustBundleVerifier.verify(input, policy)
        val original = input.copyOf()
        input.fill(0)
        result.canonicalBodyBytes.fill(0)
        result.canonicalEnvelopeBytes.fill(0)
        val returned = result.body
        (returned.signers as MutableList<OfflineCatalogSignerV1>)[0] = returned.signers[0].copy(keyId = "mutated")
        (returned.catalogLocations as MutableList<OfflineCatalogLocationV1>).clear()
        (returned.approvals as MutableList<OfflineTrustBundleApprovalV1>).clear()
        (returned.bootstrapAuthority.catalogApproverIds as MutableList<String>).clear()
        assertArrayEquals(original, result.canonicalEnvelopeBytes)
        assertArrayEquals(fixture.bodyBytes(fixture.body()), result.canonicalBodyBytes)
        assertEquals("catalog-old", result.body.signers[0].keyId)
        assertEquals(2, result.body.catalogLocations.size)
        assertEquals(2, result.body.approvals.size)
        assertEquals(listOf("catalog-approver-a", "catalog-approver-b"), result.body.bootstrapAuthority.catalogApproverIds)
    }

    @Test
    fun `valid older bundle fails independent version floor but current and newer versions pass`() {
        val signed = fixture.signed()
        reject(signed, OfflineTrustBundleFailure.VERSION_ROLLBACK, fixture.policy(minimumVersion = 8))
        OfflineTrustBundleVerifier.verify(fixture.bytes(signed), fixture.policy(minimumVersion = 7))
        OfflineTrustBundleVerifier.verify(fixture.bytes(fixture.signed(signed.body.copy(version = 8))), fixture.policy(minimumVersion = 7))
        reject(signed, OfflineTrustBundleFailure.INVALID_POLICY, fixture.policy(minimumVersion = 0))
    }

    @Test
    fun `wrong root pin or same-name different root is never accepted`() {
        val signed = fixture.signed()
        reject(signed, OfflineTrustBundleFailure.INVALID_POLICY, fixture.policy(rootFingerprint = "0".repeat(64)))
        reject(signed, OfflineTrustBundleFailure.INVALID_SIGNATURE, fixture.policy(rootSpki = fixture.secondSigner.public.encoded))
    }

    @Test
    fun `environment and all ordered catalog identities are bound to independent policy`() {
        val signed = fixture.signed()
        reject(signed, OfflineTrustBundleFailure.POLICY_MISMATCH, fixture.policy(environment = "other-environment"))
        val primary = fixture.locations[0]
        listOf(
            primary.copy(bucket = "other-catalog-primary"),
            primary.copy(accountId = "333333333333"),
            primary.copy(region = "eu-west-1"),
        ).forEach { replacement ->
            reject(signed, OfflineTrustBundleFailure.POLICY_MISMATCH, fixture.policy(catalogLocations = listOf(replacement, fixture.locations[1])))
        }
        reject(signed, OfflineTrustBundleFailure.INVALID_POLICY, fixture.policy(catalogLocations = fixture.locations.reversed()))
    }

    @Test
    fun `body and envelope schemas and canonicalizer are closed`() {
        val signed = fixture.signed()
        reject(signed.copy(schemaVersion = 2), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        reject(fixture.signed(signed.body.copy(schemaVersion = 2)), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
        reject(fixture.signed(signed.body.copy(canonicalizerId = "generic-json")), OfflineTrustBundleFailure.UNSUPPORTED_SCHEMA)
    }

    @Test
    fun `signed fields cannot be changed without resigning`() {
        val signed = fixture.signed()
        listOf(
            signed.body.copy(version = 8),
            signed.body.copy(minimumCatalogHeadGeneration = 43),
            signed.body.copy(issuedAtEpochSecond = signed.body.issuedAtEpochSecond + 1),
            signed.body.copy(approvals = signed.body.approvals.reversed()),
            signed.body.copy(signers = signed.body.signers.reversed()),
        ).forEach { changed -> reject(signed.copy(body = changed), OfflineTrustBundleFailure.INVALID_SIGNATURE) }
    }

    @Test
    fun `root ID is checked against policy and cryptographically bound even when both IDs change`() {
        val signed = fixture.signed()
        val renamed = signed.copy(signature = signed.signature.copy(keyId = "renamed-root"))
        reject(renamed, OfflineTrustBundleFailure.POLICY_MISMATCH)
        reject(renamed, OfflineTrustBundleFailure.INVALID_SIGNATURE, fixture.policy(rootId = "renamed-root"))
    }

    @Test
    fun `unimplemented root and catalog algorithms fail without provider-name fallback`() {
        val signed = fixture.signed()
        listOf("Ed25519", "SHA256withRSA", "RSASSA-PSS", "RSASSA_PSS_SHA_384").forEach { algorithm ->
            reject(signed.copy(signature = signed.signature.copy(algorithmId = algorithm)), OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM)
            reject(signed, OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM, fixture.policy(algorithm = algorithm))
            val changed = signed.body.copy(signers = listOf(signed.body.signers[0].copy(algorithmId = algorithm)))
            reject(fixture.signed(changed), OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM)
        }
    }

    @Test
    fun `wrong PSS salt MGF hash or double hashing fails actual signature verification`() {
        reject(fixture.signed(parameters = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 0, 1)), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        reject(fixture.signed(parameters = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA1, 32, 1)), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        reject(fixture.signed(transformFrame = { MessageDigest.getInstance("SHA-256").digest(it) }), OfflineTrustBundleFailure.INVALID_SIGNATURE)
        reject(fixture.signed(transformFrame = { it.copyOf().also { bytes -> bytes[4] = 'X'.code.toByte() } }), OfflineTrustBundleFailure.INVALID_SIGNATURE)
    }

    @Test
    fun `signature bytes and Base64 encodings are exact`() {
        val signed = fixture.signed()
        val signature = Base64.getDecoder().decode(signed.signature.signatureBase64)
        signature[0] = (signature[0].toInt() xor 1).toByte()
        listOf(
            Base64.getEncoder().encodeToString(signature),
            signed.signature.signatureBase64.dropLast(1),
            signed.signature.signatureBase64 + "=",
            " " + signed.signature.signatureBase64,
        ).forEach { changed -> reject(signed.copy(signature = signed.signature.copy(signatureBase64 = changed)), OfflineTrustBundleFailure.INVALID_SIGNATURE) }
    }

    @Test
    fun `duplicate signer IDs public keys and disguised key aliases are rejected`() {
        val body = fixture.body()
        rejectBody(body.copy(signers = listOf(body.signers[0], body.signers[1].copy(keyId = body.signers[0].keyId))))
        rejectBody(body.copy(signers = listOf(body.signers[0], body.signers[0].copy(keyId = "alias"))))
        val disguised = body.signers[0].copy(keyId = "alias", publicKeySha256 = "0".repeat(64))
        reject(fixture.signed(body.copy(signers = listOf(body.signers[0], disguised))), OfflineTrustBundleFailure.FINGERPRINT_MISMATCH)
    }

    @Test
    fun `root cannot be a catalog signer and public fingerprints cannot be substituted`() {
        reject(fixture.signed(fixture.body(listOf(fixture.signer(fixture.root, "root-as-catalog")))), OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
        val signer = fixture.body().signers[0]
        reject(fixture.signed(fixture.body(listOf(signer.copy(publicKeySha256 = "0".repeat(64))))), OfflineTrustBundleFailure.FINGERPRINT_MISMATCH)
        rejectBody(fixture.body(listOf(signer.copy(publicKeySha256 = "A".repeat(64)))))
    }

    @Test
    fun `weak RSA size nonstandard exponent and noncanonical SPKI are rejected`() {
        val weak = fixture.signer(fixture.newKey(bits = 2048), "weak")
        val exponentThree = fixture.signer(fixture.newKey(exponent = BigInteger.valueOf(3)), "exponent-three")
        val original = fixture.firstSigner.public.encoded
        val noncanonical = original.copyOf().also { it[17] = 0x04 } // rsaEncryption's NULL parameter must not become an OCTET STRING.
        val malformed = fixture.body().signers[0].copy(
            publicKeySpkiBase64 = Base64.getEncoder().encodeToString(noncanonical),
            publicKeySha256 = Sha256.hex(noncanonical),
        )
        listOf(weak, exponentThree, malformed).forEach { reject(fixture.signed(fixture.body(listOf(it))), OfflineTrustBundleFailure.INVALID_PUBLIC_KEY) }
    }

    @Test
    fun `SPKI Base64 requires padding and forbids whitespace or alternate encodings`() {
        val original = fixture.body().signers[0]
        listOf(original.publicKeySpkiBase64.dropLast(1), " " + original.publicKeySpkiBase64, original.publicKeySpkiBase64 + "=").forEach { changed ->
            reject(fixture.signed(fixture.body(listOf(original.copy(publicKeySpkiBase64 = changed)))), OfflineTrustBundleFailure.INVALID_PUBLIC_KEY)
        }
    }

    @Test
    fun `sixteen real distinct catalog keys are permitted but seventeenth and empty set fail`() {
        val signers = listOf(fixture.signer(fixture.firstSigner, "key-0"), fixture.signer(fixture.secondSigner, "key-1")) +
            (2 until 16).map { fixture.signer(fixture.newKey(), "key-$it") }
        val accepted = OfflineTrustBundleVerifier.verify(fixture.bytes(fixture.signed(fixture.body(signers))), fixture.policy())
        assertEquals(16, accepted.body.signers.size)
        reject(fixture.signed(fixture.body(signers + signers[0].copy(keyId = "key-16"))), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
        rejectBody(fixture.body(emptyList()))
    }

    @Test
    fun `approval claims require two distinct bounded identities and valid relative times`() {
        val body = fixture.body()
        listOf(
            body.copy(approvals = body.approvals.take(1)),
            body.copy(approvals = listOf(body.approvals[0], body.approvals[0])),
            body.copy(approvals = listOf(body.approvals[0].copy(approverId = "a".repeat(257)), body.approvals[1])),
            body.copy(approvals = listOf(body.approvals[0].copy(approverId = "with space"), body.approvals[1])),
            body.copy(approvals = listOf(body.approvals[0].copy(approvedAtEpochSecond = -1), body.approvals[1])),
            body.copy(approvals = listOf(body.approvals[0].copy(approvedAtEpochSecond = body.issuedAtEpochSecond + 1), body.approvals[1])),
            body.copy(issuedAtEpochSecond = 253402300800),
        ).forEach(::rejectBody)
    }

    @Test
    fun `zero head floor malformed identities and nonindependent locations fail`() {
        val body = fixture.body()
        listOf(
            body.copy(version = 0),
            body.copy(minimumCatalogHeadGeneration = 0),
            body.copy(environment = "bad environment"),
            body.copy(catalogLocations = body.catalogLocations.reversed()),
            body.copy(catalogLocations = listOf(body.catalogLocations[0], body.catalogLocations[0].copy(role = "REPLICA"))),
            body.copy(signers = listOf(body.signers[0].copy(keyId = "a".repeat(65)))),
        ).forEach(::rejectBody)
    }

    @Test
    fun `rejection diagnostics do not echo submitted values or retain parser causes`() {
        val marker = "private-marker-not-for-diagnostics"
        val invalid = fixture.bytes(fixture.signed()).decodeToString().replaceFirst("{", "{\"$marker\":0,").toByteArray()
        val failure = assertThrows(OfflineTrustBundleException::class.java) { OfflineTrustBundleVerifier.verify(invalid, fixture.policy()) }
        assertFalse(failure.toString().contains(marker))
        assertNull(failure.cause)
    }

    @Test
    fun `bootstrap writer hash and approver identities are bounded required claims`() {
        val body = fixture.body()
        val authority = body.bootstrapAuthority
        listOf(
            authority.copy(catalogWriterGenerationId = "00000000-0000-0000-0000-000000000000"),
            authority.copy(catalogWriterGenerationId = fixture.CATALOG_WRITER.replace("-4111-", "-3111-")),
            authority.copy(initialWriterRegistrySha256 = "A".repeat(64)),
            authority.copy(catalogApproverIds = emptyList()),
            authority.copy(catalogApproverIds = listOf("only-one")),
            authority.copy(catalogApproverIds = listOf("same", "same")),
            authority.copy(catalogApproverIds = listOf("with space", "other")),
            authority.copy(catalogApproverIds = listOf("a".repeat(257), "other")),
        ).forEach { rejectBody(body.copy(bootstrapAuthority = it)) }
        val sixteen = authority.copy(catalogApproverIds = (1..16).map { "approver-$it" })
        OfflineTrustBundleVerifier.verify(fixture.bytes(fixture.signed(body.copy(bootstrapAuthority = sixteen))), fixture.policy())
        val seventeen = sixteen.copy(catalogApproverIds = (1..17).map { "approver-$it" })
        reject(fixture.signed(body.copy(bootstrapAuthority = seventeen)), OfflineTrustBundleFailure.LIMIT_EXCEEDED)
    }

    @Test
    fun `bootstrap required signer must exactly name a verified allowlisted key and algorithm`() {
        val body = fixture.body()
        val authority = body.bootstrapAuthority
        rejectBody(body.copy(bootstrapAuthority = authority.copy(requiredSigner = authority.requiredSigner.copy(keyId = "unknown-key"))))
        val wrongAlgorithm = authority.copy(requiredSigner = authority.requiredSigner.copy(algorithmId = "Ed25519"))
        reject(fixture.signed(body.copy(bootstrapAuthority = wrongAlgorithm)), OfflineTrustBundleFailure.UNSUPPORTED_ALGORITHM)
    }

    @Test
    fun `root signature covers bootstrap claims instead of accepting caller supplied authority`() {
        val signed = fixture.signed()
        val authority = signed.body.bootstrapAuthority
        listOf(
            authority.copy(catalogWriterGenerationId = fixture.EVENT_WRITER),
            authority.copy(initialWriterRegistrySha256 = "0".repeat(64)),
            authority.copy(catalogApproverIds = authority.catalogApproverIds.reversed()),
            authority.copy(requiredSigner = authority.requiredSigner.copy(keyId = "catalog-new")),
        ).forEach { reject(signed.copy(body = signed.body.copy(bootstrapAuthority = it)), OfflineTrustBundleFailure.INVALID_SIGNATURE) }
    }

    private fun rejectBody(body: OfflineTrustBundleBodyV1) = reject(fixture.signed(body), OfflineTrustBundleFailure.INVALID_DOCUMENT)

    private fun reject(envelope: OfflineTrustBundleEnvelopeV1, code: OfflineTrustBundleFailure, policy: OfflineTrustBundlePolicy = fixture.policy()) {
        assertEquals(code, assertThrows(OfflineTrustBundleException::class.java) { OfflineTrustBundleVerifier.verify(fixture.bytes(envelope), policy) }.code)
    }
}
