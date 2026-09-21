package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.audit.infrastructure.ComplaintTestRunErasureAuditInsertionV1
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintCapacityCharges
import me.manga.kira.backend.complaint.domain.ComplaintCapacityLedger
import me.manga.kira.backend.complaint.domain.ComplaintCapacityVector
import me.manga.kira.backend.complaint.domain.ComplaintDailyAdmission
import me.manga.kira.backend.complaint.domain.InstallationCredentialSnapshot
import me.manga.kira.backend.complaint.domain.InstallationIdentityState
import me.manga.kira.backend.complaint.domain.InstallationProjectionMode
import me.manga.kira.backend.complaint.domain.InstallationRecoveryDecision
import me.manga.kira.backend.complaint.domain.InstallationRecoveryEvidence
import me.manga.kira.backend.complaint.domain.InstallationRecoveryReducer
import me.manga.kira.backend.complaint.domain.InstallationRecoverySnapshot
import me.manga.kira.backend.complaint.domain.InstallationReservationSnapshot
import me.manga.kira.backend.complaint.domain.InstallationTerminalState
import me.manga.kira.backend.complaint.domain.OwnerDeleteAllCapacityCharges
import me.manga.kira.backend.complaint.domain.RecoveryContentEffect
import me.manga.kira.backend.complaint.domain.RecoveryCredentialEffect
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalCapacityChargesV1
import me.manga.kira.backend.complaint.infrastructure.capacity.JdbcComplaintCapacityStore
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogTestRunActivationHistoryV1
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureRowsV1 as Rows
import me.manga.kira.backend.complaint.infrastructure.terminal.TestRunErasureSqlV1 as Sql
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.ResultSetExtractor
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.HexFormat
import java.util.UUID

/** One bounded, original-owned READ/BATCH/FINAL holder. No native call or arbitrary work callback. */
internal class TestRunErasureOperationV1 private constructor(
    private val phase: PersistencePhaseContext, private val jdbc: JdbcTemplate, internal val original: TestRunErasureV1,
) {
    internal val path = original.path
    internal val step = original.step
    internal lateinit var snapshot: Rows.Snapshot
        private set
    internal var remaining = false
        private set
    private var stage = Stage.NEW
    private var work = Work.NONE
    private var selected: List<Selected> = emptyList()
    private var global: Rows.Control? = null
    private var scoped: Rows.Control? = null
    private var counters: JdbcComplaintCapacityStore.LockedTestRunErasure? = null
    private lateinit var before: Rows.Snapshot
    private lateinit var beforeCounts: Rows.Counts
    private lateinit var afterCounts: Rows.Counts
    private lateinit var beforeAudits: Rows.Audits
    private lateinit var at: Instant
    private var lease: Lease? = null
    private var leaseRetired = false
    private var removed = ComplaintCapacityVector.ZERO
    private var spend = ComplaintCapacityVector.ZERO
    private var converted = ComplaintCapacityVector.ZERO
    private var released = ComplaintCapacityVector.ZERO
    private val lockedPairs = linkedMapOf<String, Pair<Rows.Publication, Rows.Recovery>>()
    private val pairs = linkedMapOf<String, Pair<Rows.Publication, Rows.Recovery>>()
    private var sidecars: List<Rows.Sidecar> = emptyList()
    private var pendingAudit: Audit? = null
    private var auditOwner: ComplaintTestRunErasureAuditInsertionV1? = null
    private var auditClaimed = false
    private val audits = arrayListOf<ComplaintTestRunErasureAuditInsertionV1>()

    internal fun belongsTo(value: PersistencePhaseContext) = phase === value
    internal fun completedFor(value: PersistencePhaseContext): Boolean = belongsTo(value) && stage === Stage.COMPLETE &&
        leaseRetired && counters?.completedFor(this) == true && audits.all { it.completedFor(this) }
    internal fun requireReleased() { phase.testRunErasureBoundary.requireCommitted(this); requireConnectionFree() }

    private fun execute() {
        retained(Stage.NEW)
        if (step === TestRunErasureStepV1.BATCH) discover()
        stage = Stage.CONTROLS
        global = readControl(UUID(0, 0), lock = true)
        scoped = readControl(original.scope, lock = true)
        requireErasure(global != null)
        at = databaseTime()
        // The unlocked discovery is never authority. Lock receipts first, then sorted P/L pairs;
        // every lower row is selected only after all capacity counters and the exact run lock.
        stage = Stage.PRIMARY
        lockPrimaries()
        stage = Stage.COUNTERS_REQUESTED
        counters = JdbcComplaintCapacityStore(jdbc, original.process.consumers.capacityPolicy.digestBytes()).lockForTestRunErasure(this)
        retained(Stage.COUNTERS_LOCKING)
        stage = Stage.BODY
        before = readSnapshot(lockRun = true)
        original.capturedSnapshot()?.requireSame(before)
        beforeCounts = counts(); beforeCounts.requireShape(before)
        beforeAudits = auditSummary(before)
        requireSupported()
        if (step !== TestRunErasureStepV1.CAPTURE) {
            original.authenticatedEvidence().requireComplete(before)
            validateRemaining(before, beforeCounts)
        }
        when (step) {
            TestRunErasureStepV1.CAPTURE -> requireErasure(work === Work.NONE && selected.isEmpty())
            TestRunErasureStepV1.VERIFY -> requireErasure(before.run.purged && !beforeCounts.remaining && work === Work.NONE)
            TestRunErasureStepV1.BATCH -> {
                val expectedWork = when {
                    beforeCounts.content != 0L || beforeCounts.notices != 0L -> Work.CONTENT
                    beforeCounts.resources != 0L -> Work.RESOURCE
                    beforeCounts.receipts != 0L -> Work.RECEIPT
                    beforeCounts.deletionReceipts != 0L -> Work.DELETION_RECEIPT
                    beforeCounts.applied != 0L -> Work.APPLIED
                    beforeCounts.ordinaryPublications != 0L -> Work.PUBLICATION
                    beforeCounts.mutableInstallations != 0L || beforeCounts.credentials != 0L -> Work.INSTALLATION
                    else -> Work.NONE
                }
                requireErasure(!before.run.purged && work === expectedWork && (work !== Work.NONE) == beforeCounts.remaining)
                if (work !== Work.NONE) { acquire(); eraseBatch(); release() }
            }
            TestRunErasureStepV1.FINAL -> {
                requireErasure(!before.run.purged && !beforeCounts.remaining)
                acquire(); eraseFinal()
            }
        }
        if (lease == null) leaseRetired = true
        requireErasure(leaseRetired)
        snapshot = readSnapshot(lockRun = false)
        before.requireImmutable(snapshot)
        if (step === TestRunErasureStepV1.CAPTURE || step === TestRunErasureStepV1.VERIFY) before.requireSame(snapshot)
        afterCounts = counts(); afterCounts.requireShape(snapshot)
        val afterAudits = auditSummary(snapshot)
        requireErasure(afterAudits.dispositions == beforeAudits.dispositions + spend[me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.AUDIT_ROWS] &&
            afterCounts.actual == beforeCounts.actual - removed + spend + converted)
        remaining = afterCounts.remaining
        requireSupported()
        stage = Stage.TRANSFER
        checkNotNull(counters).settle(this)
        retained(Stage.TRANSFER)
        requireErasure(checkNotNull(counters).completedFor(this) && audits.all { it.completedFor(this) })
        stage = Stage.COMPLETE
    }

    private fun discover() {
        retained(Stage.NEW)
        // Exactly these categories, in dependency order. Never skip surviving content to remove IDs.
        val candidates = listOf(Work.CONTENT to Sql.discoverContent, Work.RESOURCE to Sql.discoverResources,
            Work.RECEIPT to Sql.discoverReceipts, Work.DELETION_RECEIPT to Sql.discoverDeletionReceipts,
            Work.APPLIED to Sql.discoverApplied, Work.PUBLICATION to Sql.discoverPublications, Work.INSTALLATION to Sql.discoverInstallations)
        for ((kind, sql) in candidates) {
            val page = jdbc.query(sql, { row, _ -> selection(kind, row) }, original.scope)
            requireErasure(page.size <= Sql.PAGE)
            if (page.isNotEmpty()) { work = kind; selected = page; break }
        }
    }
    private fun selection(kind: Work, row: ResultSet) = Selected(
        id = if (kind in setOf(Work.CONTENT, Work.RESOURCE, Work.INSTALLATION)) Rows.uuid(row, "id") else null,
        owner = if (kind === Work.CONTENT) row.getObject("owner_id", UUID::class.java) else null,
        actorKind = if (kind === Work.RECEIPT) Rows.text(row, "actor_kind", 16) else null,
        actor = when (kind) { Work.RECEIPT -> Rows.uuid(row, "actor_id"); Work.DELETION_RECEIPT -> Rows.uuid(row, "installation_id"); else -> null },
        request = when (kind) { Work.RECEIPT -> Rows.uuid(row, "idempotency_key"); Work.DELETION_RECEIPT -> Rows.uuid(row, "deletion_key"); else -> null },
        event = when (kind) { Work.RECEIPT, Work.DELETION_RECEIPT -> row.getString("publication_ref"); Work.APPLIED, Work.PUBLICATION -> Rows.text(row, "event_id", 43); else -> null },
        key = if (kind in setOf(Work.APPLIED, Work.PUBLICATION)) Rows.text(row, "object_key", 1024) else null,
        version = if (kind === Work.APPLIED) Rows.text(row, "object_version", 1024) else null,
        hash = Rows.hash(row, "physical_hash"),
    )

    private fun lockPrimaries() {
        retained(Stage.PRIMARY)
        if (step === TestRunErasureStepV1.CAPTURE || step === TestRunErasureStepV1.VERIFY) return
        if (work === Work.RECEIPT || work === Work.DELETION_RECEIPT) selected.forEach { expected ->
            val current = if (work === Work.RECEIPT) jdbc.query(Sql.lockReceipt, { row, _ -> requireErasure(Rows.boolean(row, "valid")); selection(work, row) },
                original.scope, expected.actorKind, expected.actor, expected.request).single()
            else jdbc.query(Sql.lockDeletionReceipt, { row, _ -> requireErasure(Rows.boolean(row, "valid")); selection(work, row) },
                original.scope, expected.actor, expected.request).single()
            requireErasure(current == expected)
        }
        val ids = if (step === TestRunErasureStepV1.FINAL) {
            // Final terminal obligations are bounded by the authenticated <=4096 chunks plus purge.
            val current = readPublicationPages()
            requireErasure(current.size <= 4097 && current.all { it.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE") })
            current.map { it.id to it.key }
        } else if (work === Work.APPLIED) selected.flatMap { selected ->
            val native = original.authenticatedEvidence().ordinary(checkNotNull(selected.key), checkNotNull(selected.version))
            val primaries = native.aliases.mapNotNull { alias ->
                jdbc.query(Sql.readPublication, { row, _ -> Rows.Publication(row, original, at) }, alias.id).singleOrNull()
            }
            // All APPLIED rows are erased before any ordinary P/L. A missing family primary
            // is not a possible committed erasure prefix; an alias must bind the surviving P.
            requireErasure(primaries.size == 1)
            primaries.map { it.id to it.key }
        }.distinct() else selected.mapNotNull { it.event }.distinct().map { id ->
            val p = jdbc.query(Sql.readPublication, { row, _ -> Rows.Publication(row, original, at) }, id).singleOrNull()
            checkNotNull(p).let { it.id to it.key }
        }
        ids.sortedBy { it.second }.forEach { (id, _) ->
            retained(Stage.PRIMARY)
            val p = jdbc.query(Sql.lockPublication, { row, _ -> Rows.Publication(row, original, at) }, id).single()
            val native = if (p.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE")) {
                p.requireTerminal(original.authenticatedEvidence()); null
            } else p.requireOrdinary(original.authenticatedEvidence())
            val l = jdbc.query(Sql.lockRecovery, { row, _ -> Rows.Recovery(row, original, p, at, native) }, id).single()
            requireErasure(lockedPairs.put(id, p to l) == null)
            if (work === Work.PUBLICATION) requireErasure(selected.single { it.event == id }.hash == p.physicalHash)
        }
        if (work === Work.APPLIED) selected.forEach { expected ->
            val actual = jdbc.query(Sql.lockApplied, { row, _ -> Rows.Applied(row, original, original.authenticatedEvidence(), at) }, expected.key, expected.version).single()
            requireErasure(actual.physicalHash == expected.hash && actual.id == expected.event)
            actual.requirePrimary(lockedPairs.values.single { actual.belongsTo(it.first) })
        }
    }

    private fun readSnapshot(lockRun: Boolean): Rows.Snapshot {
        retained(Stage.BODY)
        val run = jdbc.query(if (lockRun) Sql.lockRun else Sql.run, { row, _ -> Rows.Run(row, original) }, original.scope).single()
        val activation = jdbc.query(Sql.activation, { row, _ -> Rows.Mutation(row) }, run.context.activationCatalogGeneration).single()
        val terminal = jdbc.query(Sql.suffix, { row, _ -> Rows.Mutation(row) }, activation.generation).single()
        val history = checkNotNull(jdbc.query(Sql.history, ResultSetExtractor { rows -> CatalogTestRunActivationHistoryV1.readRecoveryRegistration(rows,
            activation.generation, original.process.catalogReadback.chainPolicy.limits.maximumGenerations) }, activation.token, original.scope,
            activation.generation, hex(activation.head.envelopeSha256), activation.generation,
            original.process.catalogReadback.chainPolicy.limits.maximumGenerations + 1L))
        val active = jdbc.query(Sql.active, { row, _ -> Rows.Active(row, original, run) }, *activeArguments(run), original.routing.journalConfiguration.sealTerminalPrefix + "%").singleOrNull()
        val queue = jdbc.query(Sql.queue, { row, _ -> requireErasure(Rows.boolean(row, "valid")); Rows.hash(row, "physical_hash") },
            *controlArguments(), hex(original.routing.journalConfiguration.sha256), hex(original.routing.journalConfiguration.sha256)).singleOrNull()
        val g = checkNotNull(readControl(UUID(0, 0), lock = false))
        val s = readControl(original.scope, lock = false)
        checkNotNull(global).requireSame(g)
        if (lockRun) {
            requireErasure((scoped == null) == (s == null)); scoped?.requireSame(checkNotNull(s))
        }
        return Rows.Snapshot(g, s, activation, history, terminal, run, active, queue).also { it.requireHead() }
    }
    private fun controlArguments(): Array<Any?> = arrayOf(original.scope, original.process.desiredGeneration, original.process.implementationSchema,
        original.process.configurationHashBytes(), original.process.databaseIdentity, original.process.restoreIdentity,
        UUID.fromString(original.writer), UUID.fromString(original.process.catalogActivation.catalogWriterGenerationId), hex(original.process.catalogReadback.currentTrustBundleSha256))
    private fun activeArguments(run: Rows.Run): Array<Any?> = arrayOf(original.scope, original.process.configurationHashBytes(), hex(original.routing.journalConfiguration.sha256),
        original.process.desiredGeneration, original.process.implementationSchema, original.process.databaseIdentity, original.process.restoreIdentity,
        UUID.fromString(original.writer), run.context.activationCatalogGeneration, hex(run.context.activationCatalogSha256), Timestamp.from(run.createdAt),
        UUID.fromString(original.process.catalogActivation.catalogWriterGenerationId), hex(original.process.catalogReadback.currentTrustBundleSha256))
    private fun readControl(id: UUID, lock: Boolean): Rows.Control? = jdbc.query(if (lock) Sql.lockControl else Sql.control,
        { row, _ -> Rows.Control(row) }, *controlArguments(), id).singleOrNull()
    private fun databaseTime(): Instant = jdbc.query(Sql.sampleTime, { row, _ -> Rows.time(row, "at") }).single().also { original.requireSqlTime(this, jdbc, it) }
    private fun counts() = jdbc.query(Sql.counts, { row, _ -> Rows.Counts(row) }, original.scope).single()
    private fun auditSummary(value: Rows.Snapshot) = jdbc.query(Sql.auditSummary, { row, _ -> Rows.Audits(row, value) }, original.scope, value.terminal.token, value.terminal.generation).single()
    private fun requireSupported() = requireErasure(jdbc.query(Sql.supported, { row, _ -> Rows.boolean(row, "valid") }, original.scope,
        original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", UUID.fromString(original.writer), before.run.ordinaryEpoch).single())

    private fun readPublicationPages(): List<Rows.Publication> {
        val all = arrayListOf<Rows.Publication>()
        var after: String? = null
        val maximum = original.routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions + 4097L
        while (true) {
            val page = jdbc.query(Sql.publicationPage, { row, _ -> Rows.Publication(row, original, at) }, original.scope,
                original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", after, after)
            requireErasure(page.size <= Sql.PAGE && page.size.toLong() <= maximum - all.size)
            if (page.isEmpty()) return all
            all.addAll(page); after = page.last().key
        }
    }

    private fun validateRemaining(value: Rows.Snapshot, totals: Rows.Counts) {
        retained(Stage.BODY)
        val evidence = original.authenticatedEvidence()
        val publications = readPublicationPages()
        requireErasure(publications.size.toLong() == totals.publications)
        publications.forEach { p ->
            retained(Stage.BODY)
            val native = if (p.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE")) { p.requireTerminal(evidence); null } else p.requireOrdinary(evidence)
            val l = jdbc.query(Sql.recovery, { row, _ -> Rows.Recovery(row, original, p, at, native) }, p.id).single()
            if (native != null) {
                val family = evidence.ordinaryFamily(p.id)
                // L's cumulative charge describes the immutable full native family, not the
                // remaining SQL cardinality after a prior erasure batch committed.
                requireErasure(family.size in 1..4 && family.size.toLong() == checkNotNull(l.used)[me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.JOURNAL_APPLIED] &&
                    family.all { it.aliases == native.aliases && it.recoveryPromise == native.recoveryPromise } &&
                    family.groupBy { it.entry.key }.values.all { sameKey -> sameKey.map { it.entry.wireSha256 }.distinct().size == 1 })
            }
            lockedPairs[p.id]?.let { requireErasure(it.first.physicalHash == p.physicalHash && it.second.physicalHash == l.physicalHash) }
            requireErasure(pairs.put(p.id, p to l) == null)
        }
        if (!value.run.purged) requireErasure(publications.filter { it.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE") }.map { it.id }.toSet() ==
            evidence.terminalTargets().filter { it.kind.name != "EPOCH_SEAL" }.map { it.id }.toSet())
        var afterKey: String? = null; var afterVersion: String? = null; var applied = 0L
        while (true) {
            retained(Stage.BODY)
            val page = jdbc.query(Sql.appliedPage, { row, _ -> Rows.Applied(row, original, evidence, at) }, original.scope,
                original.routing.journalConfiguration.ordinaryPrefix + "%", original.routing.journalConfiguration.sealTerminalPrefix + "%", afterKey, afterKey, afterVersion)
            applied = Math.addExact(applied, page.size.toLong())
            requireErasure(applied <= original.routing.journalConfiguration.declaration().limits.capacity.maximumRetainedVersions)
            if (page.isEmpty()) break
            page.forEach { current -> current.requirePrimary(pairs.values.single { current.belongsTo(it.first) }) }
            afterKey = page.last().key; afterVersion = page.last().version
        }
        requireErasure(applied == totals.applied)
        val expected = evidence.installations()
        val observed = hashSetOf<UUID>()
        var afterId: UUID? = null; var credentials = 0L
        while (true) {
            retained(Stage.BODY)
            val page = jdbc.query(Sql.installationIds, { row, _ -> Rows.uuid(row, "id") }, original.scope, afterId, afterId)
            if (page.isEmpty()) break
            requireErasure(observed.size.toLong() + page.size <= original.installationLimit)
            page.forEach { id ->
                requireErasure(observed.add(id) && expected.containsKey(id))
                val i = jdbc.query(Sql.installation, { row, _ -> Rows.Installation(row) }, original.scope, id).single()
                val a = jdbc.query(Sql.credential, { row, _ -> Rows.Credential(row) }, original.scope, id).singleOrNull()
                if (a != null) credentials++
                val decision = reduce(i, a, complete = value.run.purged || step === TestRunErasureStepV1.FINAL, verify = value.run.purged)
                if (value.run.purged) requireErasure(decision === InstallationRecoveryDecision.VerifyOnly)
                if (step === TestRunErasureStepV1.FINAL) requireErasure(a == null && decision is InstallationRecoveryDecision.Apply &&
                    !decision.reserveIdentityCapacity && decision.identityState == i.state &&
                    decision.credentialEffect === RecoveryCredentialEffect.REMOVE && decision.contentEffect === RecoveryContentEffect.NONE)
            }
            afterId = page.last()
        }
        requireErasure(observed == expected.keys && observed.size.toLong() == totals.installations && credentials == totals.credentials)
        sidecars = jdbc.query(Sql.sidecar, { row, _ -> Rows.Sidecar(row, original, evidence, at, pairs[row.getString("publication_ref")]?.first) },
            original.scope, original.routing.journalConfiguration.sealTerminalPrefix + "%")
        val expectedKeys = if (value.run.purged) emptySet() else evidence.terminalTargets().filter { it.source === TestTerminalQuiescenceSourceV1.V21_TERMINAL_INTENT }.map { it.objectRef.objectKey }.toSet()
        requireErasure(sidecars.map { it.key }.toSet() == expectedKeys && sidecars.size.toLong() == totals.sidecars && sidecars.size == expectedKeys.size)
    }

    private fun acquire() {
        retained(Stage.BODY); requireErasure(lease == null && !before.run.purged)
        val old = checkNotNull(before.scoped)
        requireErasure(old.leaseOwner == null && old.leaseExpiresAt == null)
        lease = jdbc.query(Sql.acquireLease, { row, _ -> Lease(Rows.long(row, "lease_token"), Rows.time(row, "lease_expires_at")) },
            original.attemptId, original.budget.remainingMillis(2000), original.scope).single()
        requireErasure(checkNotNull(lease).token == Math.addExact(old.leaseToken, 1L))
        old.requireCore(checkNotNull(readControl(original.scope, lock = false)))
        requireLease()
    }
    private fun requireLease() {
        val current = checkNotNull(lease)
        requireErasure(!leaseRetired && jdbc.query(Sql.currentLease, { row, _ -> Rows.boolean(row, "valid") }, original.attemptId,
            current.token, Timestamp.from(current.expiresAt), original.scope).single())
    }
    private fun release() {
        retained(Stage.BODY); requireLease()
        val current = checkNotNull(lease)
        requireErasure(jdbc.update(Sql.releaseLease, original.scope, original.attemptId, current.token, Timestamp.from(current.expiresAt)) == 1)
        leaseRetired = true
    }
    private fun mutate(sql: String, vararg arguments: Any?) {
        retained(Stage.BODY); requireLease(); original.requireSqlTime(this, jdbc, databaseTime())
        requireErasure(jdbc.update(sql, *arguments) == 1)
    }

    private fun eraseBatch() {
        retained(Stage.BODY)
        when (work) {
            Work.CONTENT -> {
                // Discovery supplies comparison IDs only. Lock every owner pair in PostgreSQL's
                // UUID order BEFORE resources/content, then compare each locked content owner.
                // A UUID's Java signed compareTo is not PostgreSQL's unsigned byte ordering.
                selected.mapNotNull { it.owner }.distinct().sortedBy(UUID::toString).forEach { id ->
                    val i = jdbc.query(Sql.lockInstallation, { row, _ -> Rows.Installation(row) }, original.scope, id).single()
                    val a = jdbc.query(Sql.lockCredential, { row, _ -> Rows.Credential(row) }, original.scope, id).single()
                    requireErasure(original.authenticatedEvidence().installations().containsKey(id))
                    requireErasure(reduce(i, a, complete = false, verify = false) is InstallationRecoveryDecision.Deferred)
                }
                selected.forEach { value ->
                    jdbc.query(Sql.lockResource, { row, _ -> requireErasure(Rows.boolean(row, "valid")); Rows.uuid(row, "id") }, original.scope, value.id).single()
                }
                selected.forEach { value ->
                    val kind = jdbc.query(Sql.lockContent, { row, _ ->
                        requireErasure(Rows.boolean(row, "valid") && Rows.hash(row, "physical_hash") == value.hash)
                        requireErasure(row.getObject("owner_id", UUID::class.java) == value.owner)
                        Rows.text(row, "kind", 8)
                    }, original.scope, value.id).single()
                    mutate(Sql.deleteContent, value.id, original.scope, hex(value.hash))
                    removed += if (kind == "NOTICE") TestTerminalCapacityChargesV1.SYSTEM_NOTICE else ComplaintCapacityCharges.INSTALLATION_CONTENT_V1
                }
            }
            Work.RESOURCE -> selected.forEach { value ->
                jdbc.query(Sql.lockResource, { row, _ -> requireErasure(Rows.boolean(row, "valid") && Rows.hash(row, "physical_hash") == value.hash) }, original.scope, value.id).single()
                requireErasure(jdbc.query(Sql.noResourceDependencies, { row, _ -> Rows.boolean(row, "valid") }, value.id, value.id).single())
                mutate(Sql.deleteResource, value.id, original.scope, hex(value.hash)); removed += ComplaintCapacityCharges.RESOURCE_ID
            }
            Work.RECEIPT -> selected.forEach { value ->
                mutate(Sql.deleteReceipt, value.actorKind, value.actor, value.request, original.scope, hex(value.hash)); removed += ComplaintCapacityCharges.NORMAL_RECEIPT
            }
            Work.DELETION_RECEIPT -> selected.forEach { value ->
                mutate(Sql.deleteDeletionReceipt, value.actor, value.request, original.scope, hex(value.hash)); removed += OwnerDeleteAllCapacityCharges.RECEIPT
            }
            Work.APPLIED -> selected.forEach { value ->
                requireNoPublicationDependencies(checkNotNull(value.event))
                mutate(Sql.deleteApplied, value.key, value.version, original.scope, hex(value.hash)); removed += OwnerDeleteAllCapacityCharges.APPLIED
            }
            Work.PUBLICATION -> selected.forEach { value -> removePair(checkNotNull(value.event), terminal = false) }
            Work.INSTALLATION -> {
                selected.forEach { value ->
                    val id = checkNotNull(value.id)
                    val i = jdbc.query(Sql.lockInstallation, { row, _ -> Rows.Installation(row) }, original.scope, id).single()
                    requireErasure(i.physicalHash == value.hash)
                    val a = jdbc.query(Sql.lockCredential, { row, _ -> Rows.Credential(row) }, original.scope, id).singleOrNull()
                    val decision = reduce(i, a, complete = true, verify = false)
                    requireErasure(decision is InstallationRecoveryDecision.Apply && !decision.reserveIdentityCapacity &&
                        decision.credentialEffect === RecoveryCredentialEffect.REMOVE && decision.contentEffect === RecoveryContentEffect.NONE)
                    decision as InstallationRecoveryDecision.Apply
                    if (i.state != decision.identityState) {
                        val time = databaseTime()
                        mutate(Sql.projectInstallation, decision.identityState.name, Timestamp.from(time), id, original.scope, hex(i.physicalHash))
                        spend += ComplaintCapacityCharges.AUDIT
                        insertAudit(if (decision.identityState === InstallationIdentityState.RETIRED) Audit.RETIRED else Audit.DELETED, time)
                    }
                    if (a != null) { mutate(Sql.deleteCredential, id, original.scope, hex(a.physicalHash)); removed += ComplaintCapacityCharges.INSTALLATION_CREDENTIAL }
                }
                if (!spend.isZero()) {
                    requireErasure(spend.fitsWithin(before.run.unused))
                    mutate(Sql.spendRun, (before.run.unused - spend).toLongArray(), original.scope, hex(before.run.physicalHash))
                }
            }
            Work.NONE -> throw TestRunErasureExceptionV1()
        }
    }

    private fun reduce(i: Rows.Installation, a: Rows.Credential?, complete: Boolean, verify: Boolean): InstallationRecoveryDecision {
        requireErasure(a == null || a.id == i.id)
        val flags = jdbc.query(Sql.installationDependencies, { row, _ -> Rows.boolean(row, "content") to Rows.boolean(row, "receipts") }, i.id, i.id, i.id).single()
        val identity = ScopedInstallationId(i.id, original.routing.journalConfiguration.scope)
        val target = InstallationTerminalState.valueOf(checkNotNull(original.authenticatedEvidence().installations()[i.id]).disposition.name)
        return InstallationRecoveryReducer.reduce(InstallationRecoverySnapshot(identity, InstallationReservationSnapshot(identity, i.state),
            a?.let { InstallationCredentialSnapshot(identity, it.state) }, flags.first, flags.second, complete,
            if (verify) InstallationProjectionMode.PURGED_TEST_HISTORY else InstallationProjectionMode.APPLY), InstallationRecoveryEvidence.TestManifest(identity, target))
    }
    private fun requireNoPublicationDependencies(id: String) = requireErasure(jdbc.query(Sql.noPublicationDependencies,
        { row, _ -> Rows.boolean(row, "valid") }, id, id, id).single())
    private fun removePair(id: String, terminal: Boolean) {
        val (p, l) = checkNotNull(lockedPairs[id])
        requireErasure(pairs[id]?.first?.physicalHash == p.physicalHash && pairs[id]?.second?.physicalHash == l.physicalHash)
        requireErasure(terminal == (p.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE")))
        requireNoPublicationDependencies(id)
        mutate(Sql.deleteRecovery, id, original.scope, hex(l.physicalHash)); removed += OwnerDeleteAllCapacityCharges.RESERVATION
        mutate(Sql.deletePublication, id, original.scope, hex(p.physicalHash)); removed += OwnerDeleteAllCapacityCharges.PUBLICATION
        if (p.kind == "TEST_RUN_PURGE") { requireErasure(converted.isZero() && l.promise == TestRunPurgeOperationV1.FUTURE); converted = l.promise }
        // Every ordinary L admitted here is already CONVERTED by the genuine full drain,
        // which released P-U before E. Removing that retained row refunds its physical price
        // only: its preserved promise/used columns must NEVER pay P-U a second time.
    }
    private fun eraseFinal() {
        retained(Stage.BODY)
        requireErasure(lockedPairs.keys == pairs.keys && pairs.values.all { it.first.kind in setOf("INSTALLATION_MANIFEST", "TEST_RUN_PURGE") })
        // No per-installation transition is possible here: the preceding bounded batches proved
        // complete permanent-ID/manifest equality with all credentials/content/receipts absent.
        sidecars.forEach { value -> mutate(Sql.deleteSidecar, value.token, original.scope, hex(value.physicalHash)); removed += TestTerminalCapacityChargesV1.SIDECAR }
        pairs.values.filter { it.first.kind == "INSTALLATION_MANIFEST" }.sortedBy { it.first.key }.forEach { removePair(it.first.id, terminal = true) }
        removePair(pairs.values.single { it.first.kind == "TEST_RUN_PURGE" }.first.id, terminal = true)
        before.active?.let { value ->
            mutate(Sql.deleteActive, value.token, original.scope, hex(value.physicalHash))
            removed += ComplaintCapacityVector.units(me.manga.kira.backend.complaint.domain.ComplaintCapacityCounter.STORAGE_BYTES, 2097152L)
        }
        requireErasure(converted == ComplaintCapacityCharges.AUDIT && spend.isZero())
        val current = checkNotNull(lease)
        mutate(Sql.deleteControl, original.scope, original.attemptId, current.token, Timestamp.from(current.expiresAt), hex(checkNotNull(before.scoped).coreHash))
        removed += TestTerminalCapacityChargesV1.CONTROL
        leaseRetired = true // Exact DELETE retired the retained lease; no new SQL lease can be issued.
        val time = databaseTime()
        insertAudit(Audit.PURGED, time)
        released = before.run.unused
        requireErasure(jdbc.update(Sql.purgeRun, Timestamp.from(time), original.scope, hex(before.run.physicalHash)) == 1)
        retained(Stage.BODY)
    }

    private fun insertAudit(kind: Audit, time: Instant) {
        retained(Stage.BODY); requireErasure(pendingAudit == null && auditOwner == null)
        pendingAudit = kind; at = time; auditClaimed = false
        stage = Stage.AUDIT
        val insertion = ComplaintTestRunErasureAuditInsertionV1.insert(this)
        requireErasure(insertion === auditOwner && insertion.completedFor(this) && auditClaimed)
        audits += insertion; pendingAudit = null; auditOwner = null; stage = Stage.BODY
    }
    internal fun beginAudit(value: ComplaintTestRunErasureAuditInsertionV1): JdbcTemplate {
        retained(Stage.AUDIT); requireErasure(value.belongsTo(this) && pendingAudit != null && auditOwner == null && !auditClaimed)
        auditOwner = value; return jdbc
    }
    internal fun auditArguments(value: ComplaintTestRunErasureAuditInsertionV1, selected: JdbcTemplate): Array<Any?> {
        requireAudit(value, selected); requireErasure(!auditClaimed); auditClaimed = true
        val kind = checkNotNull(pendingAudit)
        requireErasure(if (kind === Audit.PURGED) step === TestRunErasureStepV1.FINAL && leaseRetired && converted == ComplaintCapacityCharges.AUDIT
            else step === TestRunErasureStepV1.BATCH && !leaseRetired && !spend.isZero())
        return arrayOf(original.scope, before.terminal.token, before.terminal.generation, kind.action, kind.name, Timestamp.from(at))
    }
    internal fun requireAudit(value: ComplaintTestRunErasureAuditInsertionV1, selected: JdbcTemplate) {
        retained(Stage.AUDIT, selected); requireErasure(auditOwner === value && value.belongsTo(this) && pendingAudit != null)
    }
    internal fun beginCounterLock(selected: JdbcTemplate) { retained(Stage.COUNTERS_REQUESTED, selected); stage = Stage.COUNTERS_LOCKING }
    internal fun requireCounterTransfer(value: JdbcComplaintCapacityStore.LockedTestRunErasure, selected: JdbcTemplate) {
        retained(Stage.TRANSFER, selected); requireErasure(counters === value)
    }
    internal fun settleLockedLedger(selected: JdbcTemplate, ledger: ComplaintCapacityLedger, daily: ComplaintDailyAdmission, digest: ByteArray): ComplaintCapacityLedger {
        retained(Stage.TRANSFER, selected)
        ledger.configuration.requireMatching(digest)
        val policy = original.process.consumers.capacityPolicy
        requireErasure(ledger.balance.hardLimit == policy.hardLimit && ledger.balance.creationLimit == policy.creationLimit && daily.dailyLimit == policy.dailyEnrollmentLimit &&
            beforeCounts.actual.fitsWithin(ledger.balance.actual) && before.run.unused.fitsWithin(ledger.balance.testReserved) &&
            spend.fitsWithin(before.run.unused) && removed.fitsWithin(beforeCounts.actual))
        if (step === TestRunErasureStepV1.CAPTURE || step === TestRunErasureStepV1.VERIFY) requireErasure(
            removed.isZero() && spend.isZero() && converted.isZero() && released.isZero())
        var result = ledger
        if (!removed.isZero()) result = result.refundActual(digest, removed)
        if (!spend.isZero()) result = result.spendTestReserve(digest, spend, ComplaintCapacityVector.ZERO)
        if (!converted.isZero()) {
            requireErasure(step === TestRunErasureStepV1.FINAL && converted == TestRunPurgeOperationV1.FUTURE)
            result = result.convertRecovery(digest, converted, ComplaintCapacityCharges.AUDIT)
        }
        if (!released.isZero()) { requireErasure(step === TestRunErasureStepV1.FINAL && released == before.run.unused); result = result.releaseTestReserve(digest, released) }
        requireErasure(afterCounts.actual.fitsWithin(result.balance.actual) && snapshot.run.unused.fitsWithin(result.balance.testReserved))
        return result
    }
    internal fun failed(problem: Throwable): Nothing {
        original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled()
        throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
    }
    private fun retained(expected: Stage, selected: JdbcTemplate = jdbc) {
        phase.testRunErasureBoundary.requireRetained(this, selected)
        requireErasure(stage === expected && selected === jdbc && step === original.step)
        original.requireOperation(this, selected)
    }
    private data class Selected(val id: UUID?, val owner: UUID?, val actorKind: String?, val actor: UUID?, val request: UUID?, val event: String?, val key: String?, val version: String?, val hash: String) {
        override fun toString(): String = "TestRunErasureSelectedV1(unlocked-comparison,redacted)"
    }
    private data class Lease(val token: Long, val expiresAt: Instant)
    private enum class Work { NONE, CONTENT, RESOURCE, RECEIPT, DELETION_RECEIPT, APPLIED, PUBLICATION, INSTALLATION }
    private enum class Stage { NEW, CONTROLS, PRIMARY, COUNTERS_REQUESTED, COUNTERS_LOCKING, BODY, AUDIT, TRANSFER, COMPLETE }
    private enum class Audit(val action: String) {
        RETIRED("COMPLAINT_INSTALLATION_RETIRED"), DELETED("COMPLAINT_INSTALLATION_DELETED"), PURGED("COMPLAINT_TEST_RUN_PURGED")
    }
    companion object {
        internal fun execute(jdbc: JdbcTemplate, original: TestRunErasureV1): TestRunErasureOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            try {
                phase.testRunErasureBoundary.requireOperation(original, jdbc)
                return TestRunErasureOperationV1(phase, jdbc, original).also { phase.testRunErasureBoundary.retain(it, jdbc); it.execute() }
            } catch (problem: Throwable) { original.observeFailure(problem); phase.recordFailure(problem); original.throwIfSignalled(); throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED) }
        }
        private fun hex(value: String): ByteArray = HexFormat.of().parseHex(value)
    }
}
