package me.manga.kira.backend.sourceconfig.domain

interface PublishedSourceCatalogRepository {
    fun insert(spec: NewPublishedSourceCatalog): PublishedSourceCatalog

    fun findByRevision(revision: Long): PublishedSourceCatalog?

    /** Every api that has appeared in any public v2 catalog entry. */
    fun previouslyPublishedApis(): Set<String>

    /** Cumulative tombstones carried by the latest prior v2 catalog. */
    fun removedApis(revision: Long): Set<String>

    /**
     * Return immutable bytes only when this exact api/revision has appeared in a public v2 manifest.
     * Draft, withheld-only, legacy, and guessed revisions therefore remain indistinguishable 404s.
     */
    fun findPublishedArtifact(api: String, revision: Int): PublishedSourceArtifact?
}
