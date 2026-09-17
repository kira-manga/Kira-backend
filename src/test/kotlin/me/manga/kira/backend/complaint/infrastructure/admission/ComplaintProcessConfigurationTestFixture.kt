package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceJdbcDriverRoot
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceLifecycleObservation
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePublicTrustRelease
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConfiguration
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistencePools
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceTestInputs
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.security.AcquiredVersionedSecret
import me.manga.kira.backend.security.BoundComplaintConsumerFixture
import me.manga.kira.backend.security.JwtKeyProvider
import me.manga.kira.backend.security.SecretMaterialFamily
import me.manga.kira.backend.security.VersionBoundComplaintConsumerConfiguration
import me.manga.kira.backend.security.VersionBoundComplaintConsumerSettings
import me.manga.kira.backend.security.VersionBoundComplaintJournalRouting
import me.manga.kira.backend.security.VersionBoundInstallationJwtConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import java.nio.file.Path
import java.util.UUID

/** Real cold configuration/root/all-three-pool custody; no provider, trust-file write, connection or pool start. */
internal class ComplaintProcessPoolFixture(
    password: AcquiredVersionedSecret = VersionBoundPersistenceTestInputs.acquired(),
    host: String = "db.invalid",
    port: Int = 5432,
    database: String = "fixture_db",
    username: String = "fixture_user",
    capacity: Int = 2,
    trust: ByteArray = VersionBoundPersistenceTestInputs.pem(),
    parent: Path = Path.of("/deliberately-not-created/complaint-process-test"),
    private val retained: Boolean = false,
) : AutoCloseable {
    val configuration = VersionBoundPersistenceConfiguration.fromAcquired(
        password,
        host,
        port,
        database,
        username,
        capacity,
        trust,
        parent,
    )
    val owner = configuration.bindLifecycleOwner()
    val root: PersistenceJdbcDriverRoot = poolTestField(owner, "root")

    fun bind(): VersionBoundPersistencePools = owner.bindVersionBoundPools()

    override fun close() {
        checkNotNull(owner.versionBoundPools).close()
        owner.requestShutdown()
        assertEquals(PersistenceLifecycleObservation.TRACKED_LOCAL_ENDED, owner.observeShutdown())
        assertEquals(
            if (retained) PersistencePublicTrustRelease.RETAINED else PersistencePublicTrustRelease.RELEASED,
            owner.releasePublicTrustAfterShutdown(),
        )
    }
}

/** Test inputs feed the existing actual consumer factory, never the process encoder or a fake descriptor. */
internal data class ComplaintProcessAdmissionInputs(
    val concurrent: Int = 2,
    val ingressBuckets: Int = 64,
    val ingressRate: Int = 120,
    val semanticBuckets: Int = 128,
    val semanticEvents: Int = 4096,
    val prune: Int = 8,
    val enrollmentGlobal: Int = 2,
    val createGlobal: Int = 2,
    val members: Int = 64,
    val memberPrune: Int = 8,
    val forwarded: Boolean = false,
    val proxies: List<String> = emptyList(),
    val coordination: String = "memory",
    val instances: Int = 1,
) {
    fun settings(): VersionBoundComplaintConsumerSettings = VersionBoundComplaintConsumerSettings(
        coordination, instances, concurrent, ingressBuckets, ingressRate, semanticBuckets, semanticEvents, prune,
        enrollmentGlobal, createGlobal, members, memberPrune, forwarded, proxies,
    )
}

internal fun processConfiguration(
    consumers: VersionBoundComplaintConsumerConfiguration,
    pools: VersionBoundPersistencePools,
    schema: Int = 1,
    generation: Long = 7,
    database: UUID = UUID.fromString(consumers.journalConfiguration.declaration().writer.databaseIdentity),
    restore: UUID = UUID.fromString(consumers.journalConfiguration.declaration().writer.restoreIdentity),
): VersionBoundComplaintProcessConfiguration = VersionBoundComplaintProcessConfiguration.fromRetained(consumers, pools, schema, generation, database, restore)

/** Change a real acquired version in every HMAC family, including retained-only verifiers. No descriptor is substituted after construction. */
internal fun replacedProcessConsumerVersions(fixture: BoundComplaintConsumerFixture): List<VersionBoundComplaintConsumerConfiguration> {
    val originals = listOf(
        fixture.userSecret,
        fixture.installationSecrets.last(),
        fixture.previous,
        fixture.cursorSecrets.last(),
        fixture.journalSecrets.first(),
    )
    return originals.mapIndexed { index, original ->
        val binding = original.descriptor
        val replacement = fixture.acquired(binding.family, binding.logicalKeyId, 151 + index)
        when (binding.family) {
            SecretMaterialFamily.USER_ADMIN_JWT -> fixture.configuration(
                jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
                    "installation-z",
                    fixture.installationSecrets,
                    JwtKeyProvider.fromAcquired(replacement, KiraSecurityProperties()),
                ),
            )

            SecretMaterialFamily.INSTALLATION_JWT -> fixture.configuration(
                jwt = VersionBoundInstallationJwtConfiguration.fromAcquired(
                    "installation-z",
                    fixture.installationSecrets.map { if (it === original) replacement else it },
                    fixture.user,
                ),
            )

            SecretMaterialFamily.COMPLAINT_ADMISSION -> fixture.configuration(keys = fixture.inputs(previous = replacement))

            SecretMaterialFamily.COMPLAINT_CURSOR -> fixture.configuration(
                keys = fixture.inputs(cursors = fixture.cursorSecrets.map { if (it === original) replacement else it }),
            )

            SecretMaterialFamily.COMPLAINT_JOURNAL_ROUTING -> {
                val previous = fixture.journal.declaration()
                val changed = ComplaintJournalConfigurationV1.of(
                    previous.copy(
                        routing = previous.routing.copy(
                            keys = previous.routing.keys.map {
                                if (it.keyId == binding.logicalKeyId) it.copy(secret = replacement.descriptor.version) else it
                            },
                        ),
                    ),
                )
                val routing = VersionBoundComplaintJournalRouting.fromAcquired(
                    changed,
                    fixture.journalSecrets.map { if (it === original) replacement else it },
                )
                fixture.configuration(keys = fixture.inputs(routing = routing), journal = changed)
            }

            else -> error("Unexpected test HMAC family")
        }
    }
}
