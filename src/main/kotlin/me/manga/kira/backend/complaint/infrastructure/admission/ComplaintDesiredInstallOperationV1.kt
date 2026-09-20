package me.manga.kira.backend.complaint.infrastructure.admission

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseContext
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseOwnership
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisPublishAttemptV1
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/** Three closed operations: authenticate -> LIVE row -> exact preimage CAS -> full reread. No epoch/catalog/counter/fence lock. */
internal class ComplaintDesiredInstallOperationV1 private constructor(
    private val phase: PersistencePhaseContext,
    private val jdbc: JdbcTemplate,
    internal val attempt: ComplaintDesiredInstallAttemptV1,
    private val path: PersistencePhasePath,
) {
    private var stage = Stage.RETAINED
    private var closedOldBinding: DesiredOldBindingV1? = null
    private var transition: ComplaintDesiredInstallationTransitionV1? = null

    internal fun belongsTo(candidate: ComplaintDesiredInstallAttemptV1): Boolean = attempt === candidate
    internal fun belongsTo(candidate: PersistencePhaseContext, selectedPath: PersistencePhasePath): Boolean = phase === candidate && path === selectedPath
    internal fun completedFor(candidate: PersistencePhaseContext): Boolean = phase === candidate && stage === Stage.COMPLETE

    /** Null means only the first durable closure has completed; it is never installed-success or reusable authority. */
    internal fun releasedResult(): ComplaintDesiredInstallationResultV1? {
        phase.desiredInstallation.requireCommitted(this)
        requireConnectionFree()
        attempt.requireOperation(this)
        return transition?.let { ComplaintDesiredInstallationResultV1(it, attempt.desiredGeneration) }
    }

    /** Comparison-only old B stays on the original operation. This is safe to check inside the second, distinct holder. */
    internal fun requireClosedOldBinding(): DesiredOldBindingV1 {
        phase.desiredInstallation.requireCommitted(this)
        state(path === PersistencePhasePath.COMPLAINT_DESIRED_CLOSE && transition == null && stage === Stage.COMPLETE)
        return checkNotNull(closedOldBinding)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun execute() {
        try {
            requireAt(Stage.RETAINED)
            val authenticated = jdbc.query(AUTHENTICATE_DESIRED_OPERATOR_V1, { row, _ -> truth(row, "authenticated") }, attempt.databaseName).single()
            requireDesiredInstallation(authenticated, ComplaintDesiredInstallationFailureV1.AUTHENTICATION_REFUSED)
            requireAt(Stage.RETAINED)
            val before = jdbc.query(LOCK_DESIRED_CONTROL_V1, { row, _ -> DesiredControlRowV1.copy(row) }).single()
            requireAt(Stage.RETAINED)
            stage = Stage.CONTROL_LOCKED
            when (path) {
                PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP -> bootstrap(before)
                PersistencePhasePath.COMPLAINT_DESIRED_CLOSE -> closeGates(before)
                PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE -> supersede(before)
                else -> error("Unsupported desired installation phase.")
            }
            requireAt(Stage.REREADING)
            stage = Stage.COMPLETE
        } catch (problem: Throwable) {
            stage = Stage.FAILED
            attempt.abort()
            phase.recordFailure(problem)
            throw phase.failureException(PersistencePhaseFailureCode.WORK_FAILED)
        }
    }

    private fun bootstrap(before: DesiredControlRowV1) {
        state(attempt.expectedGeneration == null && attempt.desiredGeneration == 1L && attempt.hasInitialReader())
        if (before.exactTarget(attempt) && (before.binding.projectedIdentity(attempt) || before.pristineExceptHash())) {
            observeAlreadySelected(before)
            return
        }
        state(before.binding.hashAbsent() && before.pristineExceptHash())
        state(jdbc.query(READ_DESIRED_PRISTINE_V1, { row, _ -> truth(row, "pristine") }).single())
        requireAt(Stage.CONTROL_LOCKED)
        val at = update(BOOTSTRAP_DESIRED_V1, arrayOf(attempt.configurationHash(), *before.preimageArguments()))
        val after = reread()
        state(before.samePreserved(after) && before.sameGatesAndLease(after))
        state(after.binding.sameExceptDesired(before.binding) && after.exactTarget(attempt) && after.updatedAt == at)
        transition = ComplaintDesiredInstallationTransitionV1.BOOTSTRAPPED
    }

    private fun closeGates(before: DesiredControlRowV1) {
        if (before.exactTarget(attempt) && before.binding.projectedIdentity(attempt)) {
            observeAlreadySelected(before) // No gate write, token increment or clearing of a later current lease.
            return
        }
        state(before.binding.generation == attempt.expectedGeneration && before.binding.projectedIdentity(attempt))
        val old = before.binding // Exact old D/head/trust/writer observed under this operation's row lock, never supplied authority.
        val at = update(CLOSE_DESIRED_GATES_V1, before.preimageArguments())
        val after = reread()
        state(before.binding.same(after.binding) && before.samePreserved(after) && before.sameLease(after))
        state(after.closed && after.scanRequested && after.updatedAt == at)
        closedOldBinding = old // Not usable by the second phase until this operation actually commits AND releases.
    }

    private fun supersede(before: DesiredControlRowV1) {
        val old = attempt.oldBinding(this)
        state(old.sameExceptDesired(before.binding) && before.binding.projectedIdentity(attempt))
        if (before.exactTarget(attempt)) {
            observeAlreadySelected(before) // A competing identical installer may have finished; never double-increment.
            return
        }
        state(old.same(before.binding) && old.generation == attempt.expectedGeneration)
        state(old.generation in 1 until Long.MAX_VALUE && attempt.desiredGeneration == old.generation + 1L)
        state(before.closed && before.scanRequested && before.pendingProjection == null && before.emptySlots())
        state(jdbc.query(READ_DESIRED_PENDING_V1, { row, _ -> truth(row, "no_pending") }).single())
        state(before.leaseToken in 0 until Long.MAX_VALUE)
        requireAt(Stage.CONTROL_LOCKED)
        val at = update(SUPERSEDE_DESIRED_V1, arrayOf(attempt.configurationHash(), attempt.desiredGeneration, *before.preimageArguments()))
        val after = reread()
        state(before.samePreserved(after) && after.binding.sameExceptDesired(before.binding) && after.exactTarget(attempt))
        state(after.closed && after.scanRequested && after.leaseToken == before.leaseToken + 1L && after.leaseOwner == null && after.leaseExpiresAt == null)
        state(after.updatedAt == at)
        transition = ComplaintDesiredInstallationTransitionV1.SUPERSEDED
    }

    private fun observeAlreadySelected(before: DesiredControlRowV1) {
        val after = reread()
        state(before.same(after))
        transition = ComplaintDesiredInstallationTransitionV1.ALREADY_SELECTED
    }

    private fun update(sql: String, arguments: Array<Any?>): Instant {
        requireAt(Stage.CONTROL_LOCKED)
        // SQL is selected only by the three methods above. The command/manifest cannot provide statements or arguments.
        val at = jdbc.query(sql, { row, _ ->
            state(truth(row, "finite_times"))
            val sampled = checkNotNull(row.getTimestamp("sampled_at")).toInstant()
            state(row.getTimestamp("updated_at")?.toInstant() == sampled)
            sampled
        }, *arguments).single()
        requireAt(Stage.CONTROL_LOCKED)
        return at
    }

    private fun reread(): DesiredControlRowV1 {
        requireAt(Stage.CONTROL_LOCKED)
        stage = Stage.REREADING
        val read = jdbc.query(READ_DESIRED_CONTROL_V1, { row, _ -> DesiredControlRowV1.copy(row) }).single()
        requireAt(Stage.REREADING)
        return read
    }

    private fun requireAt(expected: Stage) {
        phase.desiredInstallation.requireRetained(this, jdbc)
        attempt.requireOperation(this)
        state(stage == expected)
    }

    override fun toString(): String = "ComplaintDesiredInstallOperationV1(fixed-control-only,redacted,no-authority)"
    private enum class Stage { RETAINED, CONTROL_LOCKED, REREADING, COMPLETE, FAILED }

    companion object {
        internal fun bootstrap(jdbc: JdbcTemplate, attempt: ComplaintDesiredInstallAttemptV1): ComplaintDesiredInstallOperationV1 =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_DESIRED_BOOTSTRAP)
        internal fun closeGates(jdbc: JdbcTemplate, attempt: ComplaintDesiredInstallAttemptV1): ComplaintDesiredInstallOperationV1 =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_DESIRED_CLOSE)
        internal fun supersede(jdbc: JdbcTemplate, attempt: ComplaintDesiredInstallAttemptV1): ComplaintDesiredInstallOperationV1 =
            execute(jdbc, attempt, PersistencePhasePath.COMPLAINT_DESIRED_SUPERSEDE)

        private fun execute(jdbc: JdbcTemplate, attempt: ComplaintDesiredInstallAttemptV1, path: PersistencePhasePath): ComplaintDesiredInstallOperationV1 {
            val phase = PersistencePhaseOwnership.current() ?: throw PersistencePhaseException(PersistencePhaseFailureCode.ENTRY_REFUSED)
            phase.desiredInstallation.requireOperation(jdbc, path)
            state(attempt.path === path)
            val operation = ComplaintDesiredInstallOperationV1(phase, jdbc, attempt, path)
            phase.desiredInstallation.retain(operation, jdbc)
            attempt.retain(operation)
            operation.execute()
            return operation
        }
    }
}

/** A bounded captured old B for equality, not a cookie, installed setting or caller-selectable update precondition. */
internal class DesiredOldBindingV1 private constructor(
    val generation: Long,
    private val hash: ByteArray?,
    private val database: UUID?,
    private val restore: UUID?,
    private val writer: UUID?,
    private val catalogGeneration: Long?,
    private val catalogHash: ByteArray?,
    private val trustHash: ByteArray?,
    private val catalogWriter: UUID?,
) {
    fun arguments(): Array<Any?> =
        arrayOf(generation, hash?.copyOf(), database, restore, writer, catalogGeneration, catalogHash?.copyOf(), trustHash?.copyOf(), catalogWriter)
    fun hashAbsent(): Boolean = hash == null
    fun target(attempt: ComplaintDesiredInstallAttemptV1): Boolean = generation == attempt.desiredGeneration && attempt.matchesHash(hash)
    fun target(attempt: ComplaintSignedGenesisFirstDAttemptV1): Boolean = generation == attempt.desiredGeneration && attempt.matchesHash(hash)
    fun target(attempt: CatalogGenesisPublishAttemptV1): Boolean = generation == attempt.desiredGeneration && attempt.matchesHash(hash)
    fun same(other: DesiredOldBindingV1): Boolean = generation == other.generation && hash.contentEquals(other.hash) && sameExceptDesired(other)
    fun sameExceptDesired(other: DesiredOldBindingV1): Boolean = database == other.database && restore == other.restore && writer == other.writer &&
        catalogGeneration == other.catalogGeneration && catalogHash.contentEquals(other.catalogHash) && trustHash.contentEquals(other.trustHash) &&
        catalogWriter == other.catalogWriter
    fun pristineIdentities(): Boolean =
        database == null && restore == null && writer == null && catalogGeneration == null && catalogHash == null && trustHash == null && catalogWriter == null
    fun projectedIdentity(attempt: ComplaintDesiredInstallAttemptV1): Boolean = hash?.size == 32 && database == attempt.databaseIdentity &&
        restore == attempt.restoreIdentity && writer == attempt.eventWriterGeneration && catalogGeneration != null && catalogGeneration in 1L..65_536L &&
        catalogHash?.size == 32 && trustHash?.size == 32 && catalogWriter?.let(::v4) == true

    override fun toString(): String = "DesiredOldBindingV1(private-comparison-only)"

    companion object {
        internal fun copy(row: ResultSet): DesiredOldBindingV1 = DesiredOldBindingV1(
            row.getLong("desired_generation").also { state(!row.wasNull() && it > 0) },
            digest(
                row,
                "desired_configuration_hash",
            ),
            row.getObject("database_identity", UUID::class.java), row.getObject("restore_identity", UUID::class.java),
            row.getObject("event_writer_generation", UUID::class.java), row.getObject("accepted_catalog_generation", java.lang.Long::class.java)?.toLong(),
            digest(row, "accepted_catalog_hash"), digest(row, "trust_bundle_hash"), row.getObject("catalog_writer_generation", UUID::class.java),
        )
        private fun digest(row: ResultSet, name: String): ByteArray? = row.getBytes(name)?.also { state(it.size == 32) }
    }
}

/** Exact current nullable preimage and every other control fact, including opaque slot/checkpoint bytes. Never logged or returned. */
internal class DesiredControlRowV1 private constructor(
    val binding: DesiredOldBindingV1,
    private val maintenanceClosed: Boolean,
    private val creationClosed: Boolean,
    val scanRequested: Boolean,
    val leaseOwner: UUID?,
    val leaseToken: Long,
    val leaseExpiresAt: Instant?,
    val updatedAt: Instant,
    private val preserved: Map<String, Any?>,
) {
    val closed: Boolean get() = maintenanceClosed && creationClosed
    val pendingProjection: Any? get() = preserved["pending_projection_token"]

    fun preimageArguments(): Array<Any?> = arrayOf(
        *binding.arguments(), pendingProjection, leaseOwner, leaseToken, leaseExpiresAt?.let(Timestamp::from), Timestamp.from(updatedAt),
        maintenanceClosed, creationClosed, scanRequested,
    )
    fun exactTarget(attempt: ComplaintDesiredInstallAttemptV1): Boolean = binding.target(attempt)
    fun exactTarget(attempt: ComplaintSignedGenesisFirstDAttemptV1): Boolean = binding.target(attempt)
    fun exactTarget(attempt: CatalogGenesisPublishAttemptV1): Boolean = binding.target(attempt)
    fun sameLease(other: DesiredControlRowV1): Boolean =
        leaseOwner == other.leaseOwner && leaseToken == other.leaseToken && leaseExpiresAt == other.leaseExpiresAt
    fun sameGatesAndLease(other: DesiredControlRowV1): Boolean = maintenanceClosed == other.maintenanceClosed && creationClosed == other.creationClosed &&
        scanRequested == other.scanRequested && sameLease(other)
    fun samePreserved(other: DesiredControlRowV1): Boolean =
        preserved.keys == other.preserved.keys && preserved.all { (name, value) -> sameValue(value, other.preserved[name]) }
    fun same(other: DesiredControlRowV1): Boolean =
        binding.same(other.binding) && sameGatesAndLease(other) && updatedAt == other.updatedAt && samePreserved(other)
    fun pristineExceptHash(): Boolean = binding.generation == 1L && binding.pristineIdentities() && closed && scanRequested &&
        leaseToken == 0L && leaseOwner == null && leaseExpiresAt == null && preserved.all { (name, value) ->
            when (name) {
                "publication_epoch" -> value == 1L
                "retention_lease_token", "rotation_sequence" -> value == 0L
                else -> value == null
            }
        }
    fun emptySlots(): Boolean = preserved.all { (name, value) ->
        when {
            name == "rotation_sequence" -> value == 0L
            name.startsWith("rotation_") || name.startsWith("seal_") -> value == null
            else -> true
        }
    }

    override fun toString(): String = "DesiredControlRowV1(private-comparison-only)"

    companion object {
        fun copy(row: ResultSet): DesiredControlRowV1 {
            state(truth(row, "bounded_values") && truth(row, "finite_times"))
            state(row.getObject("data_scope_id", UUID::class.java) == UUID(0, 0) && !truth(row, "test_only"))
            state(row.getInt("implementation_schema") == 1 && !row.wasNull())
            val token = row.getLong("lease_token").also { state(!row.wasNull() && it >= 0) }
            val owner = row.getObject("lease_owner", UUID::class.java)
            val expires = row.getTimestamp("lease_expires_at")?.toInstant()
            state((owner == null && expires == null) || (owner != null && v4(owner) && expires != null && token > 0))
            return DesiredControlRowV1(
                DesiredOldBindingV1.copy(row), truth(row, "maintenance_closed"), truth(row, "creation_closed"), truth(row, "scan_requested"),
                owner, token, expires, checkNotNull(row.getTimestamp("updated_at")).toInstant(),
                DESIRED_PRESERVED_COLUMNS_V1.associateWith { row.getObject(it) },
            )
        }
    }
}

private fun truth(row: ResultSet, name: String): Boolean = row.getBoolean(name).also { state(!row.wasNull()) }
private fun sameValue(left: Any?, right: Any?): Boolean = if (left is ByteArray && right is ByteArray) left.contentEquals(right) else left == right
private fun v4(value: UUID): Boolean = value.version() == 4 && value.variant() == 2
private fun state(condition: Boolean) = requireDesiredInstallation(condition, ComplaintDesiredInstallationFailureV1.STATE_REFUSED)
