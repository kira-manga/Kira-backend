package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceNanoClock
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.catalog.FullTestCatalogInputs
import me.manga.kira.backend.security.ImmutableSecretVersion
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import java.nio.channels.SeekableByteChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.SQLException

/** No build-time network: raw Secrets Manager HTTP only, ordinary/STS/KMS/S3 owners stay cold. */
@EnabledOnOs(OS.LINUX, OS.MAC)
internal class ComplaintTestProcessAssemblyV1Test {
    @Test
    fun `protected file and real immutable SDK acquisition retain the native owner before D without opening runtime resources`() {
        val d = TestDeploymentInputFixture.document()
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, d)
        val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
            sts = { error("premature STS") }, kms = { error("premature KMS") }, s3 = { error("premature S3") })
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            try {
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                val target = assembly.target
                val owner = assembly.lifecycleOwner
                assertSame(target.pools, owner.versionBoundPools)
                assertFalse(owner.catalogTestRunActivation || owner.desiredInstallationOperator || owner.catalogGenesisAuthoring)
                assertEquals(ComplaintTestDeploymentInputsV1.fromDecoded(d).allBindings().size, http.requests.size)
                assertEquals(http.createdClients, http.closedClients)
                assertTrue(http.requests.all { it.fields().keys == setOf("SecretId", "VersionId") })
                assertTrue(PgLifecycleTestScope(owner).actors().none { it.hasEntered() })
                assertNull(ownedCutField(assembly, "channel"))
                assertNull(ownedCutField(assembly, "resolver"))
                val seal = checkNotNull(target.ordinarySeal)
                assertSame(seal, ownedCutField(assembly, "seal"))
                assertSame(target.publicationLanes, ownedCutField(assembly, "lanes"))
                val encoded = Json.parseToJsonElement(target.canonicalBytes().decodeToString()).jsonObject
                val native = encoded.getValue("ordinarySeal").jsonObject
                assertEquals("TEST_FIRST_ORDINARY_EPOCH_SEAL_INDEPENDENT_DECLARATIONS", native.getValue("profile").jsonPrimitive.content)
                assertEquals("INDEPENDENT_DECLARATIONS_EXTERNAL_VERIFICATION_REQUIRED", native.getValue("externalIntake").jsonPrimitive.content)
                // The exact same owners with ABSENT seal still have the original independent golden bytes.
                val absent = VersionBoundTestNamespaceProcessV1.fromRetained(target.consumers, target.pools, 1, 7,
                    target.databaseIdentity, target.restoreIdentity, target.publicationLanes, target.catalogReadback, target.catalogActivation)
                assertArrayEquals(FullTestCatalogInputs.goldenBytes(false), absent.canonicalBytes())
                assertEquals(Json.parseToJsonElement(absent.canonicalBytes().decodeToString()).jsonObject.toMap(), encoded.filterKeys { it != "ordinarySeal" })
                target.requireRegistrationTarget() // Checks only normal-resource identity; this is NOT registration.
                assertEquals(PersistenceLifecycleActivation.FAILED, target.pools.ordinary.start())
                assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, target.pools.catalogCoordinator.prepare())
                for (pool in listOf(target.pools.ordinary, target.pools.deletion, target.pools.catalogCoordinator.dataSource)) {
                    assertThrows<SQLException> { pool.connection }
                    assertFalse(actualPool(pool).isRunning)
                }
                requireConnectionFree()
            } finally { assembly.close() }
            assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
        }
    }

    @Test
    fun `changing independent retained retention changes D before activation but cannot retrofit a used assembly`() {
        val d = TestDeploymentInputFixture.document()
        fun hash(document: ComplaintTestDeploymentDocumentV1): ByteArray {
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.secrets(http, document)
            return TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(document)) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                    assembly.target.configurationHashBytes()
                }
            }
        }
        val before = hash(d)
        assertFalse(before.contentEquals(hash(d.copy(retention = d.retention.copy(lastPreRunRestoreHorizon = "2039-01-01T00:00:00Z")))))
        assertFalse(before.contentEquals(hash(d.copy(retention = d.retention.copy(utcUncertainty = d.retention.utcUncertainty.copy(maximumMillis = 999))))))
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, d)
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient)
            assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
            val old = assembly.target
            val calls = http.requests.size
            assertThrows<ComplaintTestDeploymentExceptionV1> {
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
            }
            assertEquals(calls, http.requests.size)
            assertThrows<RuntimeException> { old.requireRegistrationTarget() }
            assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
            assembly.close()
        }
    }

    @Test
    fun `file replacement while open and symlinks fail before the first secret lookup`() {
        val d = TestDeploymentInputFixture.document()
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            val http = AwsSecretVersionFixture()
            var selected: ComplaintTestProcessAssemblyV1? = null
            var replaced = false
            val clock = PersistenceNanoClock {
                if (!replaced && selected?.let { ownedCutField(it, "channel") as SeekableByteChannel? } != null) {
                    val replacement = path.resolveSibling("replacement.json")
                    Files.write(replacement, TestDeploymentInputFixture.bytes(d))
                    Files.move(replacement, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                    replaced = true
                }
                System.nanoTime()
            }
            val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient, clock).also { selected = it }
            assertEquals(
                ComplaintTestDeploymentFailureV1.INPUT_REFUSED,
                assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                }.code,
            )
            assertTrue(replaced)
            assertNull(ownedCutField(assembly, "channel"))
            assertEquals(0, http.createdClients)
            assembly.close()
            val link = path.resolveSibling("link.json")
            try {
                Files.createSymbolicLink(link, path.fileName)
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { linked ->
                    assertThrows<ComplaintTestDeploymentExceptionV1> {
                        linked.assemble(link, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                    }
                }
                assertEquals(0, http.createdClients)
            } finally { Files.deleteIfExists(link) }
        }
    }

    @Test
    fun `wrong immutable provider response and provider failure never return a partial target`() {
        val d = TestDeploymentInputFixture.document()
        for (wrongArn in listOf(false, true)) {
            val http = AwsSecretVersionFixture()
            http.respond = { AwsSecretVersionFixture.reply(ImmutableSecretVersion.awsSecretsManager(
                if (wrongArn) AwsSecretVersionFixture.ARN else d.database.runtimePassword.resourceArn,
                if (wrongArn) d.database.runtimePassword.versionId else AwsSecretVersionFixture.VERSION,
            )) }
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
                ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                    assertEquals(
                        ComplaintTestDeploymentFailureV1.PROVIDER_REFUSED,
                        assertThrows<ComplaintTestDeploymentExceptionV1> {
                            assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                        }.code,
                    )
                    assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
                    assertNull(ownedCutField(assembly, "owner"))
                }
            }
            assertEquals(1, http.requests.size)
            assertEquals(http.createdClients, http.closedClients)
        }
        val http = AwsSecretVersionFixture().apply { respond = { error(AwsSecretVersionFixture.PRIVATE_TEXT) } }
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                val refused = assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                }
                assertNull(refused.cause)
                assertFalse(refused.toString().contains(AwsSecretVersionFixture.PRIVATE_TEXT))
            }
        }
    }

    @Test
    fun `provider close failure is sticky and original setup budget expiry stops remaining acquisition`() {
        val d = TestDeploymentInputFixture.document()
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.secrets(http, d)
            http.onClientClose = { error(AwsSecretVersionFixture.PRIVATE_TEXT) }
            val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient)
            val refused = assertThrows<ComplaintTestDeploymentExceptionV1> {
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
            }
            assertEquals(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN, refused.code)
            assertSame(refused, assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.close() })
            assertEquals(1, http.closedClients)
            assertEquals(1, http.requests.size)
            assertNull(ownedCutField(assembly, "owner"))

            val timed = AwsSecretVersionFixture()
            TestDeploymentInputFixture.secrets(timed, d)
            var extra = 0L
            timed.onClientClose = { extra += 60_000_000_000L }
            val expired = ComplaintTestProcessAssemblyV1.withHttpFixture(timed::httpClient, PersistenceNanoClock { System.nanoTime() + extra })
            assertEquals(
                ComplaintTestDeploymentFailureV1.TIME_BUDGET_EXHAUSTED,
                assertThrows<ComplaintTestDeploymentExceptionV1> {
                    expired.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                }.code,
            )
            assertEquals(1, timed.requests.size)
            assertEquals(1, timed.closedClients)
            assertNull(ownedCutField(expired, "owner"))
            expired.close()
        }
    }

    @Test
    fun `post-pool seal construction refusal stops the retained partial normal root without opening native seal clients`() {
        val d = TestDeploymentInputFixture.document()
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, d)
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient,
                sts = { error("must stay cold") }, kms = { error("must stay cold") }, s3 = { error("must stay cold") })
            val malformedSession = AwsSessionCredentials.create("SYNTHETICACCESSKEY", "synthetic-test-key", "invalid\u007f")
            assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, malformedSession) }
            val owner = ownedCutField(assembly, "owner") as PersistenceJdbcLifecycleOwner
            assertTrue(checkNotNull(owner.versionBoundPools).shutdownRequested())
            assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
            assertTrue(PgLifecycleTestScope(owner).actors().none { it.hasEntered() })
            assertNull(ownedCutField(assembly, "seal"))
            assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
            assembly.close()
        }
    }

    @Test
    fun `shutdown owns a new runtime budget and even a failed shutdown clock cannot skip the retained closes`() {
        val d = TestDeploymentInputFixture.document()
        for (failShutdownClock in listOf(false, true)) {
            val http = AwsSecretVersionFixture()
            TestDeploymentInputFixture.secrets(http, d)
            var extra = 0L
            var refuseClock = false
            val clock = PersistenceNanoClock {
                check(!refuseClock) { AwsSecretVersionFixture.PRIVATE_TEXT }
                System.nanoTime() + extra
            }
            TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
                val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient, clock)
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                val target = assembly.target
                val owner = assembly.lifecycleOwner
                extra = 61_000_000_000L // Expiring setup must not silently shorten a later shutdown attempt.
                if (failShutdownClock) {
                    refuseClock = true
                    val failure = assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.close() }
                    assertEquals(ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN, failure.code)
                    refuseClock = false
                    assertSame(failure, assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.close() })
                } else {
                    assembly.close()
                }
                assertTrue(checkNotNull(target.ordinarySeal).stopped())
                assertTrue(target.pools.shutdownRequested())
                assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
                assertEquals(0L, target.publicationLanes.activeOwners().totalOwners)
                assertThrows<RuntimeException> { target.requireRegistrationTarget() }
                assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
            }
        }
    }

    @Test
    fun `same effective material across exact-version families is refused by the actual acquired consumers`() {
        val d = TestDeploymentInputFixture.document()
        val inputs = ComplaintTestDeploymentInputsV1.fromDecoded(d)
        val http = AwsSecretVersionFixture()
        TestDeploymentInputFixture.secrets(http, d)
        val normalReply = http.respond
        http.respond = { request ->
            val fields = request.fields()
            val binding = inputs.allBindings().single { it.version.resourceArn == fields["SecretId"] && it.version.versionId == fields["VersionId"] }
            if (binding === inputs.runtimePassword) normalReply(request) else AwsSecretVersionFixture.reply(binding.version, ByteArray(32) { 27 })
        }
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient).use { assembly ->
                assertEquals(
                    ComplaintTestDeploymentFailureV1.PROCESS_REFUSED,
                    assertThrows<ComplaintTestDeploymentExceptionV1> {
                        assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                    }.code,
                )
                assertNull(ownedCutField(assembly, "owner"))
                assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.target }
            }
        }
        assertEquals(inputs.allBindings().size, http.requests.size)
        assertEquals(http.createdClients, http.closedClients)
    }

    @Test
    fun `setup budget begins before file reads and provider close interruption is not converted to success`() {
        val d = TestDeploymentInputFixture.document()
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            val http = AwsSecretVersionFixture()
            var now = 0L
            val expired = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient, PersistenceNanoClock { now })
            now = 60_000_000_000L
            assertEquals(
                ComplaintTestDeploymentFailureV1.TIME_BUDGET_EXHAUSTED,
                assertThrows<ComplaintTestDeploymentExceptionV1> {
                    expired.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                }.code,
            )
            assertEquals(0, http.createdClients)
            assertNull(ownedCutField(expired, "channel"))
            expired.close()

            TestDeploymentInputFixture.secrets(http, d)
            http.onClientClose = { Thread.currentThread().interrupt() }
            val interrupted = ComplaintTestProcessAssemblyV1.withHttpFixture(http::httpClient)
            try {
                assertEquals(
                    ComplaintTestDeploymentFailureV1.INTERRUPTED,
                    assertThrows<ComplaintTestDeploymentExceptionV1> {
                        interrupted.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                    }.code,
                )
                assertTrue(Thread.currentThread().isInterrupted)
                assertEquals(1, http.requests.size)
                assertEquals(1, http.closedClients)
                assertNull(ownedCutField(interrupted, "owner"))
            } finally { Thread.interrupted() }
            assertEquals(ComplaintTestDeploymentFailureV1.INTERRUPTED, assertThrows<ComplaintTestDeploymentExceptionV1> { interrupted.close() }.code)
            assertThrows<ComplaintTestDeploymentExceptionV1> { interrupted.target }
        }
    }

    @Test
    fun `an unreturned provider construction stays ambiguous and cannot be retried through the same assembly`() {
        val d = TestDeploymentInputFixture.document()
        var constructions = 0
        val assembly = ComplaintTestProcessAssemblyV1.withHttpFixture({ constructions++; error(AwsSecretVersionFixture.PRIVATE_TEXT) })
        TestDeploymentInputFixture.withManifest(TestDeploymentInputFixture.bytes(d)) { path ->
            assertEquals(
                ComplaintTestDeploymentFailureV1.CLEANUP_UNPROVEN,
                assertThrows<ComplaintTestDeploymentExceptionV1> {
                    assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
                }.code,
            )
            assertThrows<ComplaintTestDeploymentExceptionV1> {
                assembly.assemble(path, AwsSecretVersionFixture.CREDENTIALS, AwsSecretVersionFixture.CREDENTIALS)
            }
            assertThrows<ComplaintTestDeploymentExceptionV1> { assembly.close() }
            assertEquals(1, constructions)
            assertNull(ownedCutField(assembly, "owner"))
        }
    }
}
