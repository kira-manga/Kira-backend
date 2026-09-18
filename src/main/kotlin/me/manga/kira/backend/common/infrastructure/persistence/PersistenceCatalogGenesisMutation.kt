package me.manga.kira.backend.common.infrastructure.persistence

import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisInitialLiveBinding
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationInput
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogGenesisMutationOperation
import org.springframework.jdbc.core.JdbcTemplate

/** Narrow view of the two named G1 write phases; never a callback, resource or publication capability. */
internal interface PersistenceCatalogGenesisMutation {
    fun requireOperation(jdbc: JdbcTemplate, path: PersistencePhasePath, input: CatalogGenesisMutationInput? = null)
    fun retain(operation: CatalogGenesisMutationOperation, jdbc: JdbcTemplate)
    fun requireRetained(operation: CatalogGenesisMutationOperation, jdbc: JdbcTemplate)
    fun requireCommitted(operation: CatalogGenesisMutationOperation)
    fun requireProcessBinding(binding: CatalogGenesisInitialLiveBinding, jdbc: JdbcTemplate)
    fun completed(): Boolean
}
