package me.manga.kira.backend.sourceconfig.infrastructure

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import me.manga.kira.backend.sourceconfig.domain.NewValidationResult
import me.manga.kira.backend.sourceconfig.domain.StoredValidationResult
import me.manga.kira.backend.sourceconfig.domain.ValidationResultRepository
import me.manga.kira.backend.sourceconfig.validation.ValidationError
import me.manga.kira.backend.sourceconfig.validation.ValidationWarning
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * Adapts [SpringDataValidationResultRepository] to the [ValidationResultRepository] port (PLAN §2).
 *
 * The pure validation types ([ValidationError]/[ValidationWarning]) are intentionally NOT
 * `@Serializable` (the `validation` package stays free of any framework-serialization coupling), so
 * this infrastructure adapter maps them to/from the `{code, path, message}` `jsonb` arrays explicitly
 * with kotlinx JSON DSL. `validated_at` is stamped here (application time).
 */
@Repository
class JpaValidationResultRepositoryAdapter(private val jpa: SpringDataValidationResultRepository) : ValidationResultRepository {

    override fun save(spec: NewValidationResult): StoredValidationResult {
        val entity =
            SourceValidationResultEntity(
                revisionId = spec.revisionId,
                valid = spec.valid,
                errors = encodeErrors(spec.errors),
                warnings = encodeWarnings(spec.warnings),
                rulesVersion = spec.rulesVersion,
                validatedAt = Instant.now(),
            )
        return jpa.save(entity).toDomain()
    }

    override fun findLatestForRevision(revisionId: UUID): StoredValidationResult? = jpa.findFirstByRevisionIdOrderByValidatedAtDesc(revisionId)?.toDomain()

    private fun SourceValidationResultEntity.toDomain(): StoredValidationResult = StoredValidationResult(
        id = requireNotNull(id) { "persisted SourceValidationResultEntity must have an id" },
        revisionId = requireNotNull(revisionId),
        valid = valid,
        errors = decodeErrors(errors),
        warnings = decodeWarnings(warnings),
        rulesVersion = rulesVersion,
        validatedAt = validatedAt,
    )

    private fun encodeErrors(errors: List<ValidationError>): String = buildJsonArray {
        errors.forEach { e ->
            addJsonObject {
                put("code", e.code)
                put("path", e.path)
                put("message", e.message)
            }
        }
    }.toString()

    private fun encodeWarnings(warnings: List<ValidationWarning>): String = buildJsonArray {
        warnings.forEach { w ->
            addJsonObject {
                put("code", w.code)
                put("path", w.path)
                put("message", w.message)
            }
        }
    }.toString()

    private fun decodeErrors(json: String): List<ValidationError> = JSON.parseToJsonElement(json).jsonArray.map {
        val obj = it.jsonObject
        ValidationError(
            code = obj.getValue("code").jsonPrimitive.content,
            path = obj.getValue("path").jsonPrimitive.content,
            message = obj.getValue("message").jsonPrimitive.content,
        )
    }

    private fun decodeWarnings(json: String): List<ValidationWarning> = JSON.parseToJsonElement(json).jsonArray.map {
        val obj = it.jsonObject
        ValidationWarning(
            code = obj.getValue("code").jsonPrimitive.content,
            path = obj.getValue("path").jsonPrimitive.content,
            message = obj.getValue("message").jsonPrimitive.content,
        )
    }

    private companion object {
        val JSON = Json
    }
}
