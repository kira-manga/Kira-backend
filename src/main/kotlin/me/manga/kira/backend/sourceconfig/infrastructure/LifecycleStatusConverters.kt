package me.manga.kira.backend.sourceconfig.infrastructure

import jakarta.persistence.AttributeConverter
import jakarta.persistence.Converter
import me.manga.kira.backend.sourceconfig.domain.RevisionStatus
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus

/**
 * JPA converters mapping the status enums to their **lowercase wire values** (PLAN §5 — the DB CHECK
 * constraints are lowercase: `draft`/`active`/…, `draft`/`published`/`superseded`). `@Enumerated(STRING)`
 * would store the uppercase enum name and violate the CHECK, so an explicit converter is used. Kept in
 * `infrastructure` (a JPA concern); the enums themselves stay pure-domain.
 */
@Converter
class SourceLifecycleStatusConverter : AttributeConverter<SourceLifecycleStatus, String> {
    override fun convertToDatabaseColumn(attribute: SourceLifecycleStatus): String = attribute.wire

    override fun convertToEntityAttribute(dbData: String): SourceLifecycleStatus = SourceLifecycleStatus.fromWire(dbData)
}

@Converter
class RevisionStatusConverter : AttributeConverter<RevisionStatus, String> {
    override fun convertToDatabaseColumn(attribute: RevisionStatus): String = attribute.wire

    override fun convertToEntityAttribute(dbData: String): RevisionStatus = RevisionStatus.fromWire(dbData)
}
