package me.manga.kira.backend.sourceconfig.domain

import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument

/**
 * Current initial-catalog admission, evaluated only after the caller locks and proves PENDING.
 * COMPLETE receipt replay and ordinary publication never consult today's initial reference.
 * Strict parsing and packaged-resource loading belong to the infrastructure implementation.
 */
interface InitialSourceCatalogPolicy {
    /** Strictly admit the original decoded input, without treating its revision/time as authority. */
    fun admitPayload(rawJson: String): InitialSourceCatalogAdmission

    /** Inspect every staged head, including retained drafts/extras excluded from assembly. */
    fun requireStagedInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>)

    /** Reassert the complete 12 ACTIVE / 33 WITHHELD inventory at initial materialization. */
    fun requirePublicationInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>)

    companion object {
        const val POLICY_ID = "app-bundle-v6-initial-catalog-v1"
        const val REFERENCE_SHA256 = "42a26ca29182a0c8c1150196ff55979fc41a8d828ed60556e9dcf6062b8b9095"
        const val GENERIC_ENGINE = "generic"

        val APPROVED_GENERIC_APIS = listOf(
            "Azora",
            "Mangamello",
            "Mangamello Plus",
            "SwatManga",
            "Lekmanga",
            "Team X",
            "DilarV2",
            "3asq",
            "Demonicscans",
            "Mangabuddy",
            "Zazamanga",
            "Tapas",
        )
        val LEGACY_APIS = listOf(
            "Lavatoons",
            "Mangatuk",
            "Dilar",
            "Promanga",
            "Prochan",
            "Batoto",
            "Manhwatop",
            "Comick",
            "Mangapark",
            "مانجا بارك",
            "Mangapark-It",
            "Mangapark-Es",
            "Mangapark-Es-La",
            "Olympusbiblioteca",
            "Manhwaweb",
            "Taurus Fansub",
            "Inmanga",
            "Komik Cast",
            "Komiku",
            "Manga Origine",
            "Raijinscan",
            "Manhastro",
            "Flowermanga",
            "Mediocretoons",
            "Desu",
            "Mangahub",
            "Batcave",
            "Timenaight",
            "Webtoontr",
            "Webtoonhatti",
            "Mangaworld",
            "Senkuro",
            "Sussytoons",
        )
        val EXPECTED_ALL = (APPROVED_GENERIC_APIS + LEGACY_APIS).toSet()
    }
}

data class InitialSourceCatalogAdmission(
    val document: SourceConfigDocument,
    val policyId: String,
    val referenceSha256: String,
)

/** Implementations supply fixed, bounded details, never source bodies or submitted values. */
class InitialSourceCatalogPolicyRejected(detail: String, cause: Throwable? = null) : RuntimeException(detail, cause)
