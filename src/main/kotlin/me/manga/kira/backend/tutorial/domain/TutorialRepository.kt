package me.manga.kira.backend.tutorial.domain

import java.time.Instant
import java.util.UUID

@Suppress("TooManyFunctions")
interface TutorialRepository {
    fun tutorialCount(): Int
    fun categoryCount(): Int

    fun createCategory(id: UUID, slug: String, position: Int, now: Instant): StoredCategory
    fun findCategory(id: UUID, lock: Boolean = false): StoredCategory?
    fun listCategories(publicOnly: Boolean = false): List<StoredCategory>
    fun nextCategoryPosition(): Int
    fun lockCategoryOrder()
    fun createCategoryRevision(id: UUID, categoryId: UUID, contentJson: String, createdBy: UUID?, now: Instant): StoredRevision
    fun findCategoryRevision(categoryId: UUID, revisionNumber: Int): StoredRevision?
    fun listCategoryRevisions(categoryId: UUID): List<StoredRevision>
    fun publishCategory(categoryId: UUID, revisionId: UUID, now: Instant)
    fun updateCategoryStatus(categoryId: UUID, status: TutorialLifecycle, now: Instant)
    fun reorderCategories(items: List<Pair<UUID, Int>>, now: Instant)

    fun createTutorial(id: UUID, slug: String, position: Int, featuredPosition: Int?, now: Instant): StoredTutorial
    fun findTutorial(id: UUID, lock: Boolean = false): StoredTutorial?
    fun findPublicTutorialBySlug(slug: String): StoredTutorial?
    fun listTutorials(publicOnly: Boolean = false, categorySlug: String? = null, featured: Boolean? = null): List<StoredTutorial>
    fun nextTutorialPosition(): Int
    fun lockTutorialOrder()
    fun createTutorialRevision(
        id: UUID,
        tutorialId: UUID,
        categoryId: UUID,
        contentJson: String,
        media: Map<String, UUID>,
        createdBy: UUID?,
        now: Instant,
    ): StoredRevision
    fun findTutorialRevision(tutorialId: UUID, revisionNumber: Int): StoredRevision?
    fun findTutorialRevisionById(id: UUID): StoredRevision?
    fun listTutorialRevisions(tutorialId: UUID): List<StoredRevision>
    fun publishTutorial(tutorialId: UUID, revisionId: UUID, mediaIds: Set<UUID>, now: Instant)
    fun updateTutorialStatus(tutorialId: UUID, status: TutorialLifecycle, now: Instant)
    fun reorderTutorials(items: List<Triple<UUID, Int, Int?>>, now: Instant)

    fun createMedia(media: StoredMedia): StoredMedia
    fun findMedia(id: UUID): StoredMedia?
    fun findMedia(ids: Set<UUID>): List<StoredMedia>
    fun listMedia(): List<StoredMedia>
    fun mediaReferenceCount(id: UUID): Int
    fun deleteMedia(id: UUID): Boolean
}
