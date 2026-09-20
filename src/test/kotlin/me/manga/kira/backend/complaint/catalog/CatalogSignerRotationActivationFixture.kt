package me.manga.kira.backend.complaint.catalog

import kotlinx.serialization.json.Json
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogGenesisEnvelopeV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineCatalogRotationManifestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationFreezeRequestV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogSignerRotationReleaseLeafV1
import me.manga.kira.backend.security.aws.AwsJournalKmsFixture
import me.manga.kira.backend.security.aws.JournalKmsHttpReply
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.springframework.transaction.support.TransactionSynchronizationManager
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.Signature
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal fun withCatalogSignerRotationActivation(tls: VersionBoundPersistenceConnectedFixture, test: (CatalogSignerRotationActivationFixture) -> Unit) =
    CatalogSignerRotationActivationFixture(tls).use { fixture ->
        fixture.prepare()
        test(fixture)
    }

/** Actual G1 -> D7 -> overlap2 -> projected2 prefix; owns only its subsequent operation3 and explicitly negative rows. */
internal class CatalogSignerRotationActivationFixture(tls: VersionBoundPersistenceConnectedFixture) : AutoCloseable {
    val delivery = CatalogSignerRotationDeliveryFixture(tls)
    val freeze get() = delivery.freeze
    val observer get() = delivery.observer
    val core get() = delivery.core
    val signing = AwsJournalKmsFixture()
    val signatures = mutableListOf<ByteArray>()
    var beforeSign: () -> Unit = {}
    private val roots = mutableListOf<CatalogSignerRotationActivationRoot>()
    private val negativeTokens = mutableListOf<UUID>()
    private val assertion = AtomicReference<AssertionError?>()
    private var ownsToken = false
    lateinit var manifest: OfflineCatalogRotationManifestV1
        private set
    lateinit var request: CatalogSignerRotationFreezeRequestV1
        private set
    lateinit var http: CatalogGenesisPublishHttpFixture
        private set
    lateinit var projectedRoot: CatalogSignerRotationDeliveryRoot
        private set
    lateinit var projectedLeaves: Map<Path, Pair<Map<String, Any>, ByteArray>>
        private set
    val token: UUID get() = UUID.fromString(manifest.operationToken)
    val intent: ByteArray get() = OfflineCatalogRotationFixture.manifestBytes(manifest)
    val allocation: Path get() = freeze.releaseRoot.resolve("rotation-activation-3")

    fun prepare() {
        delivery.prepare()
        projectedRoot = CatalogSignerRotationColdRecoveryCases(delivery).retiredKnownPrefix(projected = true).first
        assertTrue(projectedRoot.cleanupVerified)
        projectedLeaves = freeze.snapshotLeaves()
        val genesis = Json.decodeFromString(OfflineCatalogGenesisEnvelopeV1.serializer(), freeze.d7.envelope.decodeToString())
        manifest = OfflineCatalogRotationFixture.manifest(genesis, 3, delivery.envelope, "SINGLE", listOf("catalog-new"))
        assertEquals(
            0L,
            observer.queryForObject("SELECT count(*) FROM complaint_catalog_mutations WHERE operation_token = ?", Long::class.java, token),
        )
        ownsToken = true
        request = freeze.request(manifest)
        http = CatalogGenesisPublishHttpFixture(
            ByteArray(0), // No signed activation envelope exists before the real Sign and actual PUT.
            manifest.creation.createdAtEpochSecond,
            predecessorBytes = freeze.d7.envelope,
            predecessorRetainUntil = freeze.d7.retainUntil.epochSecond,
            overlapBytes = delivery.envelope,
            overlapRetainUntil = delivery.http.retainUntil,
        )
        assertNull(http.primaryVersion)
        assertNull(http.replicaVersion)
        assertTrue(http.primaryBytes.isEmpty() && http.replicaBytes.isEmpty())
        assertFalse(Files.exists(allocation, NOFOLLOW_LINKS))
        configureSigning()
    }

    private fun configureSigning() {
        signing.respond = { request ->
            observed {
                requireConnectionFree()
                assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
                assertTrue(signatures.isEmpty(), "Exactly one new-key activation Sign; never an old-key Sign or a retry.")
                val fields = request.fields()
                assertEquals("TrentService.Sign", request.target())
                assertEquals(setOf("KeyId", "Message", "MessageType", "SigningAlgorithm"), fields.fieldNames().asSequence().toSet())
                assertEquals(CatalogSignerRotationD7Inputs.NEW_KEY_ARN, fields["KeyId"].textValue())
                assertEquals("RAW", fields["MessageType"].textValue())
                assertEquals("RSASSA_PSS_SHA_256", fields["SigningAlgorithm"].textValue())
                assertEquals("https", request.http.protocol())
                assertEquals("kms.us-east-1.amazonaws.com", request.http.host())
                val authorization = request.http.firstMatchingHeader("Authorization").orElseThrow()
                assertTrue(authorization.contains("/us-east-1/kms/aws4_request"))
                assertTrue(authorization.contains("Credential=${SIGNING_CREDENTIALS.accessKeyId()}/"))
                assertEquals(SIGNING_CREDENTIALS.sessionToken(), request.http.firstMatchingHeader("x-amz-security-token").orElseThrow())
                val frame = Base64.getDecoder().decode(fields["Message"].textValue())
                assertArrayEquals(OfflineCatalogGenesisFixture.independentFrame("catalog-new", intent), frame)
                beforeSign()
                val signature = Signature.getInstance("RSASSA-PSS").run {
                    setParameter(OfflineTrustBundleFixture.parameters)
                    initSign(OfflineTrustBundleFixture.secondSigner.private)
                    update(frame)
                    sign()
                }
                signatures.add(signature.copyOf())
                val encoded = Base64.getEncoder().encodeToString(signature)
                JournalKmsHttpReply(
                    """{"KeyId":"${CatalogSignerRotationD7Inputs.NEW_KEY_ARN}","SigningAlgorithm":"RSASSA_PSS_SHA_256","Signature":"$encoded"}""",
                ).apply {
                    beforeCall = { observed(::requireConnectionFree) }
                    beforeRead = { observed(::requireConnectionFree) }
                    onAbort = { observed(::requireConnectionFree) }
                    onClose = { observed(::requireConnectionFree) }
                }
            }
        }
    }

    fun retainRoot(root: CatalogSignerRotationActivationRoot) {
        roots.add(root) // Before preparation can launch any original actor, including failed preparation.
    }

    fun retainNegativeToken(value: UUID) {
        assertFalse(value == token || value == freeze.token)
        negativeTokens.add(value)
    }

    fun row(): Map<String, Any?> = observer.queryForMap("SELECT * FROM complaint_catalog_mutations WHERE operation_token = ?", token)

    fun mutationJson(): String = checkNotNull(
        observer.queryForObject(
            "SELECT to_jsonb(m)::text FROM complaint_catalog_mutations m WHERE operation_token = ?",
            String::class.java,
            token,
        ),
    )

    fun rowVersion(): String = checkNotNull(
        observer.queryForObject("SELECT xmin::text FROM complaint_catalog_mutations WHERE operation_token = ?", String::class.java, token),
    )

    fun path(leaf: CatalogSignerRotationReleaseLeafV1): Path = allocation.resolve(leaf.fileName)
    fun exists(leaf: CatalogSignerRotationReleaseLeafV1): Boolean = Files.exists(path(leaf), NOFOLLOW_LINKS)
    fun read(leaf: CatalogSignerRotationReleaseLeafV1): ByteArray = Files.readAllBytes(path(leaf))

    fun complete(leaf: CatalogSignerRotationReleaseLeafV1): Boolean {
        val marker = path(leaf).resolveSibling("${leaf.fileName}.complete")
        if (!exists(leaf) || !Files.isRegularFile(marker, NOFOLLOW_LINKS)) return false
        val bytes = read(leaf)
        val expected = ByteBuffer.allocate(68).putInt(bytes.size).put(Sha256.hex(bytes).toByteArray(Charsets.US_ASCII)).array()
        return Files.readAllBytes(marker).contentEquals(expected)
    }

    fun snapshotLeaves(): Map<Path, Pair<Map<String, Any>, ByteArray>> {
        if (!Files.exists(allocation, NOFOLLOW_LINKS)) return emptyMap()
        return Files.walk(allocation).use { paths ->
            paths.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.toList().associateWith { path ->
                Files.readAttributes(path, "unix:dev,ino,uid,gid,mode,nlink", NOFOLLOW_LINKS) to Files.readAllBytes(path)
            }
        }
    }

    fun assertLeavesUnchanged(snapshot: Map<Path, Pair<Map<String, Any>, ByteArray>>, appended: Set<CatalogSignerRotationReleaseLeafV1> = emptySet()) {
        val current = snapshotLeaves()
        val added = appended.flatMap { listOf(path(it), path(it).resolveSibling("${it.fileName}.complete")) }.toSet()
        assertEquals(snapshot.keys + added, current.keys)
        snapshot.forEach { (path, previous) ->
            assertEquals(previous.first, current.getValue(path).first)
            assertArrayEquals(previous.second, current.getValue(path).second)
        }
        appended.forEach { assertTrue(complete(it), it.name) }
    }

    fun assertNoLostAssertions() {
        assertion.get()?.let { throw it }
        if (::http.isInitialized) http.assertNoLostAssertions()
    }

    override fun close() {
        val ready = runCatching {
            requireConnectionFree()
            assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty())
            assertTrue(roots.all { it.cleanupVerified }, "All original activation roots must physically retire before operation3 isolation cleanup.")
        }
        val removed = runCatching {
            ready.getOrThrow()
            if (ownsToken) {
                // Post-assertion isolation only, never PROJECT or lease/head/time mutation, slot clearing or an UNKNOWN cleanup receipt.
                observer.update(
                    "UPDATE complaint_journal_control SET pending_projection_token = NULL WHERE data_scope_id = ? AND pending_projection_token = ?",
                    ComplaintDataScope.LIVE.id,
                    token,
                )
                (negativeTokens + token).forEach { observer.update("DELETE FROM complaint_catalog_mutations WHERE operation_token = ?", it) }
            }
        }
        val closed = runCatching {
            ready.getOrThrow()
            removed.getOrThrow()
            delivery.close() // Existing correction3 disposal and predecessor ownership guards remain unchanged.
        }
        rethrowSignerRotationFixtureFailures(listOf(ready, removed, closed, runCatching(::assertNoLostAssertions)))
    }

    private fun <T> observed(action: () -> T): T = try {
        action()
    } catch (failure: AssertionError) {
        assertion.compareAndSet(null, failure)
        throw failure
    }

    companion object {
        val SIGNING_CREDENTIALS: AwsSessionCredentials = AwsSessionCredentials.create(
            "SYNTHETICACTIVATIONKMS",
            "synthetic-activation-kms-secret",
            "synthetic-activation-kms-session",
        )
    }
}
