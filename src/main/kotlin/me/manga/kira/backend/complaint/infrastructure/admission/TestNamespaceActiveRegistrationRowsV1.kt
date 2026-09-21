package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalAccountingPlanV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationProjectionCountersV1
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationBoolean
import me.manga.kira.backend.complaint.infrastructure.catalog.requiredTestActivationLong
import me.manga.kira.backend.complaint.infrastructure.terminal.TestOrdinaryDrainRowsV1
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.HexFormat
import java.util.UUID

/** Exact current control preimages (including xmin/leases/rotation/checkpoint), not authority for any of those fields. */
internal class TestNamespaceActiveRegistrationControlV1(row: ResultSet) {
    val scope: UUID = checkNotNull(row.getObject("data_scope_id", UUID::class.java))
    val generation = row.requiredTestActivationLong("accepted_catalog_generation")
    val hash = boundedRecoveryBytes(row, "accepted_catalog_hash", 32, 32)
    private val desiredGeneration = row.requiredTestActivationLong("desired_generation")
    private val desiredHash = row.getBytes("desired_configuration_hash")?.copyOf()
    private val fingerprint = boundedRecoveryBytes(row, "fingerprint", 32, 32)
    init { requireRegistration(row.requiredTestActivationBoolean("valid") && (desiredHash == null || desiredHash.size == 32)) }
    fun requireSame(other: TestNamespaceActiveRegistrationControlV1) = requireRegistration(
        scope == other.scope && generation == other.generation && hash.contentEquals(other.hash) &&
            desiredGeneration == other.desiredGeneration && desiredHash.contentEquals(other.desiredHash) && fingerprint.contentEquals(other.fingerprint),
    )
    fun globalIdentityArguments(): Array<Any?> {
        requireRegistration(scope == UUID(0L, 0L))
        return arrayOf(desiredGeneration, desiredHash?.copyOf())
    }
}

/** Detached current birth/activation/full-D comparisons, deliberately not initial-PROJECT facts or a sealer/terminal ticket. */
internal class TestNamespaceActiveRegistrationBindingV1(
    process: VersionBoundTestNamespaceProcessV1, val tail: TestNamespaceRecoveryRegistrationTailV1, val installationLimit: Long,
    global: TestNamespaceActiveRegistrationControlV1,
) {
    val scope = process.consumers.journalConfiguration.scope.id
    val configurationHash = HexFormat.of().formatHex(process.configurationHashBytes())
    val plan = TestTerminalAccountingPlanV1(installationLimit, process.consumers.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
    private val run: Array<Any?> = arrayOf(scope, process.configurationHashBytes(), installationLimit, tail.generation, tail.envelopeHash.copyOf(),
        Timestamp.from(checkNotNull(tail.completed.projectedAt)), plan.originalUnusedReserve.toLongArray().joinToString(",", "{", "}"))
    private val controls: Array<Any?> = arrayOf(scope, process.desiredGeneration, process.implementationSchema, process.configurationHashBytes(),
        process.databaseIdentity, process.restoreIdentity, UUID.fromString(process.consumers.journalConfiguration.declaration().writer.generationId),
        UUID.fromString(process.catalogActivation.initialWriterRegistry().catalogWriter.generationId),
        HexFormat.of().parseHex(process.catalogReadback.currentTrustBundleSha256), tail.generation, tail.envelopeHash.copyOf())
    private val globalIdentity = global.globalIdentityArguments()
    init { requireRegistration(installationLimit > 0 && tail.scope == scope) }
    fun runArguments(): Array<Any?> = copy(run)
    fun controlArguments(): Array<Any?> = copy(controls)
    fun globalIdentityArguments(): Array<Any?> = copy(globalIdentity)
    private fun copy(values: Array<Any?>): Array<Any?> = values.map { value -> when (value) {
        is ByteArray -> value.copyOf()
        is Timestamp -> Timestamp.from(value.toInstant())
        else -> value
    } }.toTypedArray()
}

/** ACTIVE remainder after actual enrollments. Never compares with PROJECT's initial counters/effect. */
internal class TestNamespaceActiveRegistrationRunV1(row: ResultSet, binding: TestNamespaceActiveRegistrationBindingV1) {
    val enrolled = row.requiredTestActivationLong("enrolled_count")
    val reserve = TestOrdinaryDrainRowsV1.vector(row, "original_reserve")
    val unused = TestOrdinaryDrainRowsV1.vector(row, "unused_reserve")
    private val fingerprint = boundedRecoveryBytes(row, "fingerprint", 32, 32)
    init {
        requireRegistration(row.requiredTestActivationBoolean("valid") && reserve == binding.plan.originalUnusedReserve &&
            row.requiredTestActivationLong("installation_limit") == binding.installationLimit && enrolled in 0..binding.installationLimit)
        val spent = TestTerminalCapacityChargesV1.INSTALLATION_SHARE.scaled(enrolled)
        requireRegistration(spent.fitsWithin(reserve) && unused == reserve - spent)
    }
    fun requireAccounting(counters: CatalogTestRunActivationProjectionCountersV1, ids: Long, credentials: Long) {
        requireRegistration(ids == enrolled && credentials in 0..ids && counters.balance.actual[ComplaintCapacityCounter.TEST_RUNS] == 1L &&
            counters.balance.testReserved == unused && TestTerminalCapacityChargesV1.ACTIVE_RUN.fitsWithin(counters.balance.actual) &&
            counters.balance.actual[ComplaintCapacityCounter.INSTALLATION_IDS] >= ids &&
            counters.balance.actual[ComplaintCapacityCounter.APP_INSTALLATIONS] >= credentials)
        // Ordinary content/audits and the opt-in V26 slot spend ordinary free/actual, not this reserve.
        // Their aggregate counters need not equal a fresh PROJECT snapshot. No V17/V26 restart owner is issued here.
    }
    fun requireSame(other: TestNamespaceActiveRegistrationRunV1) = requireRegistration(
        enrolled == other.enrolled && reserve == other.reserve && unused == other.unused && fingerprint.contentEquals(other.fingerprint),
    )
}

internal class TestNamespaceActiveRegistrationSnapshotV1(
    val tail: TestNamespaceRecoveryRegistrationTailV1,
    val binding: TestNamespaceActiveRegistrationBindingV1,
    val history: CatalogTestRunActivationHistoryV1,
    private val global: TestNamespaceActiveRegistrationControlV1,
    private val scoped: TestNamespaceActiveRegistrationControlV1,
    private val counters: CatalogTestRunActivationProjectionCountersV1,
    private val run: TestNamespaceActiveRegistrationRunV1,
    private val installations: Pair<Long, Long>,
) {
    fun requireSame(other: TestNamespaceActiveRegistrationSnapshotV1) {
        tail.requireSame(other.tail); history.requireSame(other.history); global.requireSame(other.global); scoped.requireSame(other.scoped)
        counters.requireSame(other.counters); run.requireSame(other.run)
        requireRegistration(binding.installationLimit == other.binding.installationLimit && binding.configurationHash == other.binding.configurationHash &&
            installations == other.installations)
    }
}
