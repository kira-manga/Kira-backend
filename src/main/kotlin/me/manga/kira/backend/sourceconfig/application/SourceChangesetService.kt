package me.manga.kira.backend.sourceconfig.application

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.sourceconfig.domain.NewSourceChangeset
import me.manga.kira.backend.sourceconfig.domain.SourceChangeset
import me.manga.kira.backend.sourceconfig.domain.SourceChangesetRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class SourceChangesetService(
    private val repository: SourceChangesetRepository,
    private val sourceAdmin: SourceAdminService,
    private val audit: AuditService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    @Transactional
    fun create(name: String, description: String?, actorId: UUID): ChangesetView {
        val now = clock.instant()
        val changeset =
            repository.create(
                NewSourceChangeset(
                    id = UUID.randomUUID(),
                    name = name.trim(),
                    description = description?.trim()?.takeIf(String::isNotEmpty),
                    operationsJson = "[]",
                    actorId = actorId,
                    at = now,
                ),
            )
        audit.record(
            AuditAction.SOURCE_CHANGESET_CREATED,
            AuditService.ENTITY_SOURCE_CHANGESET,
            changeset.id.toString(),
            emptyMap(),
            actorId,
        )
        return view(changeset)
    }

    @Transactional(readOnly = true)
    fun get(id: UUID): ChangesetView = view(repository.findById(id) ?: throw SourceChangesetNotFoundException())

    @Transactional(readOnly = true)
    fun list(): List<ChangesetView> = repository.findAll().map(::view)

    @Transactional
    fun save(id: UUID, expectedVersion: Long, name: String, description: String?, operations: List<CatalogChange>, actorId: UUID): ChangesetView {
        validateShape(operations, allowEmpty = true)
        val updated =
            repository.update(
                id = id,
                expectedVersion = expectedVersion,
                name = name.trim(),
                description = description?.trim()?.takeIf(String::isNotEmpty),
                operationsJson = encode(operations),
                actorId = actorId,
                at = clock.instant(),
            ) ?: throw SourceChangesetVersionConflictException()
        audit.record(
            AuditAction.SOURCE_CHANGESET_UPDATED,
            AuditService.ENTITY_SOURCE_CHANGESET,
            id.toString(),
            mapOf("version" to updated.version, "operationCount" to operations.size),
            actorId,
        )
        return view(updated)
    }

    @Transactional(readOnly = true)
    fun validate(id: UUID, expectedVersion: Long): ChangesetValidation {
        val changeset = repository.findById(id) ?: throw SourceChangesetNotFoundException()
        if (changeset.version != expectedVersion || changeset.status.wire != "open") {
            throw SourceChangesetVersionConflictException()
        }
        return sourceAdmin.validateChanges(decode(changeset.operationsJson))
    }

    @Transactional
    fun apply(id: UUID, expectedVersion: Long, actorId: UUID): ChangesetApplyOutcome {
        val changeset =
            repository.lockOpen(id, expectedVersion)
                ?: throw SourceChangesetVersionConflictException()
        val outcome = sourceAdmin.applyChanges(decode(changeset.operationsJson), actorId, id)
        repository.markApplied(id, expectedVersion, outcome.documentRevision, actorId, clock.instant())
            ?: throw SourceChangesetVersionConflictException()
        return outcome
    }

    @Transactional
    fun discard(id: UUID, expectedVersion: Long, actorId: UUID): ChangesetView {
        val discarded =
            repository.discard(id, expectedVersion, actorId, clock.instant())
                ?: throw SourceChangesetVersionConflictException()
        audit.record(
            AuditAction.SOURCE_CHANGESET_DISCARDED,
            AuditService.ENTITY_SOURCE_CHANGESET,
            id.toString(),
            mapOf("version" to discarded.version),
            actorId,
        )
        return view(discarded)
    }

    private fun view(changeset: SourceChangeset) = ChangesetView(changeset, decode(changeset.operationsJson))

    private fun encode(operations: List<CatalogChange>): String = objectMapper.writeValueAsString(operations)

    private fun decode(json: String): List<CatalogChange> = objectMapper.readValue(json, object : TypeReference<List<CatalogChange>>() {})

    private fun validateShape(operations: List<CatalogChange>, allowEmpty: Boolean) {
        if (!allowEmpty && operations.isEmpty()) throw EmptyChangesetException()
        if (operations.size > 500) throw ChangesetTooLargeException()
    }
}

data class ChangesetView(val changeset: SourceChangeset, val operations: List<CatalogChange>)
