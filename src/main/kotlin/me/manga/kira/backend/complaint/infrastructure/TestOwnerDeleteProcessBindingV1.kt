package me.manga.kira.backend.complaint.infrastructure

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import me.manga.kira.backend.complaint.domain.ComplaintCapacityPolicyV1
import me.manga.kira.backend.complaint.domain.ComplaintInstallationDesiredSettings
import me.manga.kira.backend.complaint.domain.ComplaintInstallationMode
import me.manga.kira.backend.complaint.infrastructure.admission.ComplaintTestNamespaceRegistrationV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationLanesV1
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.TestOwnerDeleteJournalRoutingV1
import org.springframework.jdbc.core.JdbcTemplate

/** No request-authorizing issuer exists. The registered VERIFIED-only continuation does not issue this type. */
internal class TestOwnerDeleteProcessBindingV1 private constructor(val lower: TestOwnerDeleteLocalGraphV1)

/**
 * Retained graph and necessary SQL comparisons. The default lower hash is NOT full-D/registration
 * authority. Only the narrow registered continuation derives full-D from its actual retained process;
 * that graph cannot authorize new work. No activation, secret acquisition, catalog assertion or
 * request-authorizing binding above is issued by either form.
 */
internal class TestOwnerDeleteLocalGraphV1(
    private val ordinary: JdbcTemplate,
    private val deletion: JdbcTemplate,
    val ingress: ComplaintIngressAdmission,
    val routing: TestOwnerDeleteJournalRoutingV1,
    val policy: ComplaintCapacityPolicyV1,
    val lanes: JournalPublicationLanesV1,
    desiredGeneration: Long = 1,
    internal val recoveryRegistration: ComplaintTestNamespaceRegistrationV1? = null,
) {
    private val ordinarySource = checkNotNull(ordinary.dataSource)
    private val deletionSource = checkNotNull(deletion.dataSource)
    private val journal = routing.journalConfiguration
    private val declaration = journal.declaration()
    val writer: UUID = UUID.fromString(declaration.writer.generationId)
    private val desired: ComplaintInstallationDesiredSettings.Configured

    init {
        require(desiredGeneration > 0 && ordinarySource !== deletionSource)
        lanes.retainTestJournal(journal)
        desired = if (recoveryRegistration == null) lowerSettings(desiredGeneration) else {
            recoveryRegistration.requireUsable()
            val process = recoveryRegistration.process
            check(ordinarySource === process.pools.ordinary && deletionSource === process.pools.deletion)
            check(ingress === process.consumers.ingressAdmission && routing === process.consumers.journalRouting &&
                policy === process.consumers.capacityPolicy && lanes === process.publicationLanes && desiredGeneration == process.desiredGeneration)
            process.desiredSettings() // Only the retained full root derives D; a supplied hash is never accepted.
        }
    }

    private fun lowerSettings(desiredGeneration: Long): ComplaintInstallationDesiredSettings.Configured {
        val fields = listOf(
            "kira-complaint-test-owner-delete-lower-comparison-v1".toByteArray(Charsets.UTF_8),
            ByteBuffer.allocate(8).putLong(desiredGeneration).array(), journal.canonicalBytes(), policy.digestBytes(),
        )
        val frame = ByteBuffer.allocate(fields.sumOf { 4 + it.size }).apply { fields.forEach { putInt(it.size).put(it) } }.array()
        val hash = MessageDigest.getInstance("SHA-256").digest(frame)
        frame.fill(0)
        fields.forEach { it.fill(0) }
        val result = ComplaintInstallationDesiredSettings.Configured(
            ComplaintInstallationMode.PRE_CUTOVER_TEST, 1, desiredGeneration, journal.scope,
            UUID.fromString(declaration.writer.databaseIdentity), UUID.fromString(declaration.writer.restoreIdentity), hash,
        )
        hash.fill(0)
        return result
    }

    fun desiredSettings(): ComplaintInstallationDesiredSettings.Configured {
        requireUnchanged()
        return desired
    }

    fun requireUnchanged() {
        check(ordinary.dataSource === ordinarySource && deletion.dataSource === deletionSource)
        check(routing.journalConfiguration === journal)
        lanes.requireTestJournal(journal)
        recoveryRegistration?.process?.requireUnchangedConfiguration()
    }

    fun requireOrdinary(jdbc: JdbcTemplate) {
        requireUnchanged()
        check(jdbc.dataSource === ordinarySource)
    }

    fun requireDeletion(jdbc: JdbcTemplate) {
        requireUnchanged()
        check(jdbc.dataSource === deletionSource)
    }

    override fun toString(): String = "TestOwnerDeleteLocalGraphV1(retained-comparisons,no-request-issuer)"
}
