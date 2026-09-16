package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogWriterV1
import me.manga.kira.backend.complaint.domain.catalog.InitialEventRoleV1
import me.manga.kira.backend.complaint.domain.catalog.InitialEventWriterV1
import me.manga.kira.backend.complaint.domain.catalog.InitialJournalLocationV1
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeV1
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveScopeV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.InitialSealHistoryV1
import me.manga.kira.backend.complaint.domain.catalog.InitialSignerPolicyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapAuthorityV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapRegistryV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogLocationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineRequiredSignerV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleApprovalV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleBodyV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundlePolicy
import me.manga.kira.backend.complaint.domain.catalog.OfflineTrustBundleSignatureV1
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.MGF1ParameterSpec
import java.security.spec.PSSParameterSpec
import java.security.spec.RSAKeyGenParameterSpec
import java.util.Base64

/** Synthetic test material only. Private keys are generated in memory, never read from disk or committed. */
internal object OfflineTrustBundleFixture {
    const val ROOT_ID = "synthetic-offline-root-1"
    const val ALGORITHM = "RSASSA_PSS_SHA_256"
    const val CATALOG_WRITER = "11111111-1111-4111-8111-111111111111"
    const val EVENT_WRITER = "22222222-2222-4222-8222-222222222222"
    const val DATABASE_ID = "33333333-3333-4333-8333-333333333333"
    const val RESTORE_ID = "44444444-4444-4444-8444-444444444444"
    val parameters = PSSParameterSpec("SHA-256", "MGF1", MGF1ParameterSpec.SHA256, 32, 1)
    val root: KeyPair by lazy { newKey() }
    val firstSigner: KeyPair by lazy { newKey() }
    val secondSigner: KeyPair by lazy { newKey() }
    val locations = listOf(
        OfflineCatalogLocationV1("PRIMARY", "kira-synthetic-catalog-primary", "111111111111", "us-east-1"),
        OfflineCatalogLocationV1("REPLICA", "kira-synthetic-catalog-replica", "222222222222", "us-west-2"),
    )

    fun newKey(bits: Int = 3072, exponent: java.math.BigInteger = RSAKeyGenParameterSpec.F4): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(RSAKeyGenParameterSpec(bits, exponent)) }.generateKeyPair()

    fun signer(key: KeyPair, id: String): OfflineCatalogSignerV1 =
        OfflineCatalogSignerV1(id, ALGORITHM, Base64.getEncoder().encodeToString(key.public.encoded), Sha256.hex(key.public.encoded))

    fun body(signers: List<OfflineCatalogSignerV1> = listOf(signer(firstSigner, "catalog-old"), signer(secondSigner, "catalog-new"))) =
        OfflineTrustBundleBodyV1(
            schemaVersion = 1,
            version = 7,
            environment = "synthetic-test",
            catalogLocations = locations,
            canonicalizerId = "kcj-1",
            minimumCatalogHeadGeneration = 42,
            issuedAtEpochSecond = 1720000000,
            approvals = listOf(
                OfflineTrustBundleApprovalV1("synthetic-approver-a", 1719999940),
                OfflineTrustBundleApprovalV1("synthetic-approver-b", 1719999970),
            ),
            signers = signers,
            bootstrapAuthority = OfflineBootstrapAuthorityV1(
                CATALOG_WRITER,
                OfflineRequiredSignerV1(signers.firstOrNull()?.keyId ?: "catalog-old", signers.firstOrNull()?.algorithmId ?: ALGORITHM),
                Sha256.hex(registryBytes(registry())),
                listOf("catalog-approver-a", "catalog-approver-b"),
            ),
        )

    fun registry(): OfflineBootstrapRegistryV1 = OfflineBootstrapRegistryV1(
        schemaVersion = 1,
        canonicalizerId = "kcj-1",
        databaseIdentity = DATABASE_ID,
        restoreIdentity = RESTORE_ID,
        catalogWriter = InitialCatalogWriterV1(
            CATALOG_WRITER,
            "ACTIVE",
            InitialSignerPolicyV1("SINGLE", "ALL_MEMBERS", listOf(OfflineRequiredSignerV1("catalog-old", ALGORITHM))),
            listOf("catalog-approver-a", "catalog-approver-b"),
            InitialCatalogPrincipalV1("catalog-put-role", policyReference("catalog-put-policy")),
            InitialCatalogPrincipalV1("catalog-sign-role", policyReference("catalog-sign-policy")),
        ),
        eventWriter = InitialEventWriterV1(
            EVENT_WRITER,
            DATABASE_ID,
            RESTORE_ID,
            "ACTIVE",
            InitialLiveRangeV1(
                scope = InitialLiveScopeV1("LIVE", "00000000-0000-0000-0000-000000000000"),
                state = "OPEN",
                journalLocation = InitialJournalLocationV1("kira-synthetic-journal", "333333333333", "eu-west-1"),
                ordinaryPrefix = "complaints/journal/v1/$EVENT_WRITER/live/00000000-0000-0000-0000-000000000000/ordinary/",
                sealTerminalPrefix = "complaints/journal/v1/$EVENT_WRITER/live/00000000-0000-0000-0000-000000000000/seal-terminal/",
                ordinaryAuthority = InitialEventRoleV1("journal-ordinary-role", "journal-ordinary-credential", policyReference("journal-ordinary-policy")),
                sealTerminalAuthority = InitialEventRoleV1(
                    "journal-terminal-role",
                    "journal-terminal-credential",
                    policyReference("journal-terminal-policy"),
                ),
                routingKeyId = "synthetic-routing-key",
                encryptionKeyId = "synthetic-encryption-key",
                configurationSha256 = Sha256.hexUtf8("synthetic-routing-and-encryption-config"),
                firstEpoch = 1,
                sealHistory = InitialSealHistoryV1(0, Sha256.hexUtf8("[]")),
            ),
        ),
    )

    private fun policyReference(id: String): InitialPolicyReferenceV1 = InitialPolicyReferenceV1(id, 1, Sha256.hexUtf8("synthetic-policy-reference:$id"))

    fun registryBytes(registry: OfflineBootstrapRegistryV1): ByteArray =
        CanonicalJson.canonicalize(OfflineBootstrapRegistryV1.serializer(), registry).toByteArray(Charsets.UTF_8)

    /** Rebind ONLY the registry hash, then genuinely sign: semantic-negative tests cannot stop at a stale hash. */
    fun signedForRegistry(registry: OfflineBootstrapRegistryV1): OfflineTrustBundleEnvelopeV1 {
        val original = body()
        val authority = original.bootstrapAuthority.copy(initialWriterRegistrySha256 = Sha256.hex(registryBytes(registry)))
        return signed(original.copy(bootstrapAuthority = authority))
    }

    fun policy(
        rootSpki: ByteArray = root.public.encoded,
        rootId: String = ROOT_ID,
        algorithm: String = ALGORITHM,
        rootFingerprint: String = Sha256.hex(rootSpki),
        minimumVersion: Long = 7,
        environment: String = "synthetic-test",
        catalogLocations: List<OfflineCatalogLocationV1> = locations,
    ) = OfflineTrustBundlePolicy(rootSpki, rootFingerprint, rootId, algorithm, environment, catalogLocations, minimumVersion)

    fun bodyBytes(body: OfflineTrustBundleBodyV1): ByteArray =
        CanonicalJson.canonicalize(OfflineTrustBundleBodyV1.serializer(), body).toByteArray(Charsets.UTF_8)

    fun bytes(envelope: OfflineTrustBundleEnvelopeV1): ByteArray =
        CanonicalJson.canonicalize(OfflineTrustBundleEnvelopeV1.serializer(), envelope).toByteArray(Charsets.UTF_8)

    fun signed(
        body: OfflineTrustBundleBodyV1 = body(),
        parameters: PSSParameterSpec = this.parameters,
        transformFrame: (ByteArray) -> ByteArray = { it },
    ): OfflineTrustBundleEnvelopeV1 {
        val signer = Signature.getInstance("RSASSA-PSS")
        signer.setParameter(parameters)
        signer.initSign(root.private)
        signer.update(transformFrame(independentFrame(ROOT_ID, bodyBytes(body))))
        return OfflineTrustBundleEnvelopeV1(1, body, OfflineTrustBundleSignatureV1(ROOT_ID, ALGORITHM, Base64.getEncoder().encodeToString(signer.sign())))
    }

    /** Independent framing code, not a call back into the production crypto helper. */
    fun independentFrame(rootId: String, canonicalBodyBytes: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use { framed ->
            listOf(
                "kira.complaints.offline-trust-bundle.v1".toByteArray(Charsets.UTF_8),
                "kcj-1".toByteArray(Charsets.UTF_8),
                rootId.toByteArray(Charsets.UTF_8),
                ALGORITHM.toByteArray(Charsets.UTF_8),
                MessageDigest.getInstance("SHA-256").digest(canonicalBodyBytes),
            ).forEach {
                framed.writeInt(it.size)
                framed.write(it)
            }
        }
        return output.toByteArray()
    }

    fun golden(name: String): ByteArray =
        requireNotNull(javaClass.getResourceAsStream("/fixtures/complaint-offline-trust-bundle-v1/$name")).use { it.readBytes() }
}
