package me.manga.kira.backend.complaint.infrastructure.admission

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcLifecycleOwner
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleActivation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceTimeBudget
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleTestScope
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.actualPool
import me.manga.kira.backend.common.infrastructure.persistence.ownedCutField
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.security.aws.AwsSecretVersionFixture
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.sql.SQLException
import java.util.HexFormat

internal class ComplaintDesiredProcessAssemblyV1Test {
    @Test
    fun `each selected D1 through D6 uses its genuine retained inventory with an independent fixed operator and every target actor cold`() {
        val hashes = mutableSetOf<String>()
        for (profile in listOf(
            DesiredProcessProfileV1.D1,
            DesiredProcessProfileV1.D2,
            DesiredProcessProfileV1.D3,
            DesiredProcessProfileV1.D4,
            DesiredProcessProfileV1.D5,
            DesiredProcessProfileV1.D6,
        )) {
            withAssembly(DesiredInstallationInputFixture.document(profile.name)) { assembly ->
                val process = assembly.target
                val encoded = Json.parseToJsonElement(process.canonicalBytes().decodeToString()).jsonObject
                assertEquals(profile.ordinal + 1, encoded.getValue("schemaVersion").jsonPrimitive.int)
                assertArrayEquals(process.configurationHashBytes(), process.desiredSettings().configurationHashBytes())
                hashes.add(HexFormat.of().formatHex(process.configurationHashBytes()))
                assertEquals(profile != DesiredProcessProfileV1.D1, process.catalogReadback != null)
                assertEquals(profile == DesiredProcessProfileV1.D5, process.catalogReadback?.projectedCurrent == true)
                assertEquals(
                    profile in setOf(DesiredProcessProfileV1.D3, DesiredProcessProfileV1.D4, DesiredProcessProfileV1.D6),
                    process.epochRotation != null,
                )
                assertEquals(profile in setOf(DesiredProcessProfileV1.D4, DesiredProcessProfileV1.D6), process.epochSealAcquisition != null)
                assertEquals(profile == DesiredProcessProfileV1.D6, process.liveCoverage != null)
                val targetOwner = ownedCutField(assembly, "targetOwner") as PersistenceJdbcLifecycleOwner
                val operatorOwner = ownedCutField(assembly, "operatorOwner") as PersistenceJdbcLifecycleOwner
                assertNotSame(targetOwner, operatorOwner)
                assertNotSame(process.pools, operatorOwner.versionBoundPools)
                assertSame(process.pools, targetOwner.versionBoundPools)
                assertFalse(targetOwner.desiredInstallationOperator)
                assertTrue(operatorOwner.desiredInstallationOperator)
                assertNull(operatorOwner.epochRotation)
                val operatorUsers = checkNotNull(operatorOwner.versionBoundPools).descriptors().flatMap { it.openings() }
                    .map { it.publicDriverProperties()["user"] }.toSet()
                val runtimeUsers = process.pools.descriptors().flatMap { it.openings() }.map { it.publicDriverProperties()["user"] }.toSet()
                assertEquals(setOf(VersionBoundPersistenceConfiguration.DESIRED_INSTALLATION_OPERATOR_USERNAME), operatorUsers)
                assertEquals(setOf("fixture_user"), runtimeUsers)
                assertTrue(PgLifecycleTestScope(targetOwner).actors().none { it.hasEntered() })
                assertTrue(PgLifecycleTestScope(operatorOwner).actors().none { it.hasEntered() })
                assertEquals(PersistenceLifecycleActivation.FAILED, process.pools.ordinary.start())
                assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, process.pools.deletion.prepareDeletion())
                assertEquals(PersistenceLifecycleObservation.UNAVAILABLE, process.pools.catalogCoordinator.prepare())
                for (pool in listOf(process.pools.ordinary, process.pools.deletion, process.pools.catalogCoordinator.dataSource)) {
                    assertThrows<SQLException> { pool.connection }
                    assertFalse(actualPool(pool).isRunning)
                }
                process.requireUnchangedConfiguration()
                requireConnectionFree()
            }
        }
        assertEquals(6, hashes.size)
    }

    @Test
    fun `D6 retains the selected genuine reader and independent policy before D with no G1 to projected retrofit`() {
        val initial = DesiredInstallationInputFixture.document("D6")
        val projected = initial.copy(catalog = checkNotNull(initial.catalog).copy(readerProfile = "PROJECTED_CURRENT"))
        val initialHash = computed(initial)
        withAssembly(projected) { assembly ->
            val process = assembly.target
            assertTrue(checkNotNull(process.catalogReadback).projectedCurrent)
            assertFalse(initialHash.contentEquals(process.configurationHashBytes()))
            val lanes = ownedCutField(assembly, "lanes") as me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
            checkNotNull(process.liveCoverage).requireRetained(process.consumers.journalRouting, checkNotNull(process.catalogReadback), lanes)
            val replacement = ComplaintDesiredDeploymentInputsV1.fromDecoded(projected)
            assertNotSame(replacement.livePolicy, process.liveCoverage?.deployment)
            assertThrows<IllegalArgumentException> {
                checkNotNull(process.liveCoverage).requireRetained(process.consumers.journalRouting, checkNotNull(replacement.catalog), lanes)
            }
            assertThrows<ComplaintDesiredInstallationExceptionV1> {
                ComplaintDesiredDeploymentInputsV1.fromDecoded(projected).requireBootstrapProfile()
            }
        }
        val policy = checkNotNull(initial.livePolicy)
        val changed = initial.copy(
            livePolicy = policy.copy(utcUncertainty = policy.utcUncertainty.copy(maximumMillis = policy.utcUncertainty.maximumMillis + 1)),
        )
        assertFalse(initialHash.contentEquals(computed(changed)))
    }

    @Test
    fun `target D changes with actual runtime version and settings but not the independent operator credential`() {
        val document = DesiredInstallationInputFixture.document()
        val baseline = computed(document)
        val newOperator = document.database.operatorPassword.copy(versionId = "65000000-0000-4000-8000-000000000011")
        assertArrayEquals(baseline, computed(document.copy(database = document.database.copy(operatorPassword = newOperator))))
        val newRuntime = document.database.runtimePassword.copy(versionId = "65000000-0000-4000-8000-000000000012")
        val changed = listOf(
            document.copy(database = document.database.copy(runtimePassword = newRuntime)),
            document.copy(desiredGeneration = 2),
            document.copy(jwt = document.jwt.copy(issuer = "different-synthetic-issuer")),
            document.copy(admission = document.admission.copy(concurrentLimit = document.admission.concurrentLimit + 1)),
        )
        changed.forEach { assertFalse(baseline.contentEquals(computed(it))) }
    }

    @Test
    fun `actual equal DB passwords and wrong acquired binding cannot become separate operator custody`() {
        val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(DesiredInstallationInputFixture.document())
        val same = "synthetic-identical-runtime-and-operator".toByteArray()
        val equal = DesiredInstallationInputFixture.acquired(inputs, same, same.copyOf())
        val assembly = ComplaintDesiredProcessAssemblyV1()
        try {
            val refused = assertThrows<ComplaintDesiredInstallationExceptionV1> { assembly.assemble(inputs, equal, null) }
            assertEquals(ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED, refused.code)
            assertNull(ownedCutField(assembly, "targetOwner"))
            assertNull(ownedCutField(assembly, "operatorOwner"))
        } finally {
            assembly.close()
            assembly.requireCleanup(PersistenceTimeBudget.start(2_000))
        }
        val missing = ComplaintDesiredProcessAssemblyV1()
        try {
            assertThrows<ComplaintDesiredInstallationExceptionV1> { missing.assemble(inputs, equal.dropLast(1), null) }
            assertNull(ownedCutField(missing, "targetOwner"))
        } finally {
            missing.close()
            missing.requireCleanup(PersistenceTimeBudget.start(2_000))
        }
    }

    @Test
    fun `a hand constructed attempt or target coordinator grants no original operator phase entry`() {
        withAssembly(DesiredInstallationInputFixture.document()) { assembly ->
            val unrelated = ComplaintDesiredInstallationOperatorV1.begin()
            try {
                val forged = ComplaintDesiredInstallAttemptV1(unrelated, assembly.coordinator, assembly.target, null, unrelated.budget)
                val refused = assertThrows<ComplaintDesiredInstallationExceptionV1> { assembly.coordinator.desiredInstallation.install(forged) }
                assertEquals(ComplaintDesiredInstallationFailureV1.PROCESS_REFUSED, refused.code)
                assertEquals(0, assembly.coordinator.activeSnapshotOwners())
                assertThrows<IllegalStateException> { assembly.target.pools.catalogCoordinator.desiredInstallation }
                assertFalse(actualPool(assembly.coordinator.dataSource).isRunning)
                requireConnectionFree()
            } finally {
                unrelated.close()
            }
        }
    }

    @Test
    fun `real SDK provider refusal close failure and elapsed original budget never reach pool assembly or revive the one shot owner`() {
        val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(DesiredInstallationInputFixture.document())
        val provider = DesiredInstallationInvocation(inputs)
        provider.http.respond = { error(AwsSecretVersionFixture.PRIVATE_TEXT) }
        val refused = assertThrows<ComplaintDesiredInstallationExceptionV1> { provider.execute() }
        assertEquals(ComplaintDesiredInstallationFailureV1.PROVIDER_REFUSED, refused.code)
        assertNull(refused.cause)
        assertFalse(refused.message.orEmpty().contains(AwsSecretVersionFixture.PRIVATE_TEXT))
        assertEquals(1, provider.http.requests.size)
        assertNull(provider.target)
        assertThrows<ComplaintDesiredInstallationExceptionV1> { provider.execute() }
        assertEquals(1, provider.http.requests.size)
        provider.fixtureCleanup()

        val cleanup = DesiredInstallationInvocation(inputs)
        cleanup.http.onClientClose = { error(AwsSecretVersionFixture.PRIVATE_TEXT) }
        val cleanupFailure = assertThrows<ComplaintDesiredInstallationExceptionV1> { cleanup.execute() }
        assertEquals(ComplaintDesiredInstallationFailureV1.CLEANUP_UNPROVEN, cleanupFailure.code)
        assertEquals(1, cleanup.http.closedClients)
        assertNull(cleanup.target)
        assertSame(cleanupFailure, assertThrows<ComplaintDesiredInstallationExceptionV1> { cleanup.operator.close() })
        cleanup.fixtureCleanup()
        assertEquals(1, cleanup.http.closedClients, "Unknown provider cleanup is retained, not retried as a fresh close.")

        val expired = DesiredInstallationInvocation(inputs)
        expired.http.onClientClose = { expired.clock.extraNanos += 60_000_000_000L }
        assertThrows<ComplaintDesiredInstallationExceptionV1> { expired.execute() }
        assertEquals(1, expired.http.requests.size)
        assertNull(expired.target)
        expired.fixtureCleanup()
    }

    private fun computed(document: ComplaintDesiredDeploymentDocumentV1): ByteArray = withAssembly(document) { it.target.configurationHashBytes() }

    private fun <T> withAssembly(document: ComplaintDesiredDeploymentDocumentV1, work: (ComplaintDesiredProcessAssemblyV1) -> T): T {
        val inputs = ComplaintDesiredDeploymentInputsV1.fromDecoded(document)
        val assembly = ComplaintDesiredProcessAssemblyV1()
        try {
            assembly.assemble(
                inputs,
                DesiredInstallationInputFixture.acquired(inputs),
                if (inputs.sealerMapping ==
                    null
                ) {
                    null
                } else {
                    AwsSecretVersionFixture.CREDENTIALS
                },
            )
            return work(assembly)
        } finally {
            assembly.close()
            assembly.requireCleanup(PersistenceTimeBudget.start(2_000))
        }
    }
}
