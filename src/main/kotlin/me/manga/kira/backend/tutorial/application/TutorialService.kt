package me.manga.kira.backend.tutorial.application

import com.fasterxml.jackson.databind.ObjectMapper
import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.config.KiraSecurityProperties
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.tutorial.domain.CategoryContent
import me.manga.kira.backend.tutorial.domain.MediaSlot
import me.manga.kira.backend.tutorial.domain.StoredCategory
import me.manga.kira.backend.tutorial.domain.StoredRevision
import me.manga.kira.backend.tutorial.domain.StoredTutorial
import me.manga.kira.backend.tutorial.domain.TutorialContent
import me.manga.kira.backend.tutorial.domain.TutorialLifecycle
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.tutorial.domain.TutorialValidator
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.util.UUID

@Service
class TutorialService(
    private val repository: TutorialRepository,
    private val objectMapper: ObjectMapper,
    private val audit: AuditService,
    private val currentUser: CurrentUser,
    private val clock: Clock,
    properties: KiraTutorialProperties,
    security: KiraSecurityProperties,
) {
    private val validator = TutorialValidator(properties.maximumSteps)
    private val publicBaseUrl = security.externalBaseUrl

    @Transactional
    fun createCategory(slug: String, position: Int? = null): AdminCategoryView {
        validator.slug(slug)
        if (position != null && position < 0) throw BadRequestException("position must be non-negative.")
        val id = UUID.randomUUID()
        val created = repository.createCategory(id, slug, position ?: repository.nextCategoryPosition(), clock.instant())
        audit.record(AuditAction.TUTORIAL_CATEGORY_CREATED, AuditService.ENTITY_TUTORIAL_CATEGORY, id.toString(), mapOf("slug" to slug))
        return AdminCategoryView(created, null)
    }

    @Transactional(readOnly = true)
    fun listAdminCategories(): List<AdminCategoryView> = repository.listCategories().map { category ->
        AdminCategoryView(category, category.publishedRevisionId?.let { publishedCategory(category) })
    }

    @Transactional
    fun createCategoryRevision(categoryId: UUID, content: CategoryContent): CategoryRevisionView {
        validator.category(content)
        requireCategory(categoryId, lock = true)
        val revision = repository.createCategoryRevision(
            UUID.randomUUID(),
            categoryId,
            objectMapper.writeValueAsString(content),
            actor(),
            clock.instant(),
        )
        audit.record(
            AuditAction.TUTORIAL_CATEGORY_REVISION_CREATED,
            AuditService.ENTITY_TUTORIAL_CATEGORY,
            categoryId.toString(),
            mapOf("revision" to revision.revisionNumber),
        )
        return CategoryRevisionView(revision, content)
    }

    @Transactional(readOnly = true)
    fun listCategoryRevisions(categoryId: UUID): List<CategoryRevisionView> {
        requireCategory(categoryId)
        return repository.listCategoryRevisions(categoryId).map(::categoryRevision)
    }

    @Transactional
    fun publishCategory(categoryId: UUID, revisionNumber: Int): AdminCategoryView {
        val category = requireCategory(categoryId, lock = true)
        val revision = repository.findCategoryRevision(categoryId, revisionNumber) ?: throw TutorialRevisionNotFoundException()
        ensureNewerCategoryRevision(category, revision)
        repository.publishCategory(categoryId, revision.id, clock.instant())
        audit.record(
            AuditAction.TUTORIAL_CATEGORY_PUBLISHED,
            AuditService.ENTITY_TUTORIAL_CATEGORY,
            categoryId.toString(),
            mapOf("revision" to revisionNumber),
        )
        return AdminCategoryView(requireCategory(categoryId), categoryRevision(revision))
    }

    @Transactional
    fun rollbackCategory(categoryId: UUID, revisionNumber: Int): AdminCategoryView {
        requireCategory(categoryId, lock = true)
        val historical = repository.findCategoryRevision(categoryId, revisionNumber) ?: throw TutorialRevisionNotFoundException()
        val copy = repository.createCategoryRevision(
            UUID.randomUUID(),
            categoryId,
            historical.contentJson,
            actor(),
            clock.instant(),
        )
        repository.publishCategory(categoryId, copy.id, clock.instant())
        audit.record(
            AuditAction.TUTORIAL_CATEGORY_REVISION_CREATED,
            AuditService.ENTITY_TUTORIAL_CATEGORY,
            categoryId.toString(),
            mapOf("revision" to copy.revisionNumber),
        )
        audit.record(
            AuditAction.TUTORIAL_CATEGORY_PUBLISHED,
            AuditService.ENTITY_TUTORIAL_CATEGORY,
            categoryId.toString(),
            mapOf("revision" to copy.revisionNumber),
        )
        audit.record(
            AuditAction.TUTORIAL_CATEGORY_ROLLBACK,
            AuditService.ENTITY_TUTORIAL_CATEGORY,
            categoryId.toString(),
            mapOf("fromRevision" to revisionNumber, "revision" to copy.revisionNumber),
        )
        return AdminCategoryView(requireCategory(categoryId), categoryRevision(copy))
    }

    @Transactional
    fun archiveCategory(categoryId: UUID) = changeCategoryStatus(categoryId, TutorialLifecycle.ARCHIVED, AuditAction.TUTORIAL_CATEGORY_ARCHIVED)

    @Transactional
    fun restoreCategory(categoryId: UUID): AdminCategoryView {
        val category = requireCategory(categoryId, lock = true)
        val target = if (category.publishedRevisionId == null) TutorialLifecycle.DRAFT else TutorialLifecycle.PUBLISHED
        repository.updateCategoryStatus(categoryId, target, clock.instant())
        audit.record(AuditAction.TUTORIAL_CATEGORY_RESTORED, AuditService.ENTITY_TUTORIAL_CATEGORY, categoryId.toString())
        val restored = requireCategory(categoryId)
        return AdminCategoryView(restored, restored.publishedRevisionId?.let { publishedCategory(restored) })
    }

    @Transactional
    fun reorderCategories(items: List<ReorderItem>): List<AdminCategoryView> {
        repository.lockCategoryOrder()
        val categories = repository.listCategories()
        validateCompleteOrder(items, categories.map { it.id }, allowFeatured = false)
        repository.reorderCategories(items.map { it.id to it.position }, clock.instant())
        audit.record(AuditAction.TUTORIAL_REORDERED, AuditService.ENTITY_TUTORIAL_CATEGORY, "all", mapOf("count" to items.size))
        return listAdminCategories()
    }

    @Transactional
    fun createTutorial(slug: String, position: Int? = null, featuredPosition: Int? = null): AdminTutorialView {
        validator.slug(slug)
        if (position != null && position < 0) throw BadRequestException("position must be non-negative.")
        if (featuredPosition != null && featuredPosition < 0) throw BadRequestException("featuredPosition must be non-negative.")
        val id = UUID.randomUUID()
        val created = repository.createTutorial(id, slug, position ?: repository.nextTutorialPosition(), featuredPosition, clock.instant())
        audit.record(AuditAction.TUTORIAL_CREATED, AuditService.ENTITY_TUTORIAL, id.toString(), mapOf("slug" to slug))
        return AdminTutorialView(created, null)
    }

    @Transactional(readOnly = true)
    fun listAdminTutorials(): List<AdminTutorialView> = repository.listTutorials().map { tutorial ->
        AdminTutorialView(tutorial, tutorial.publishedRevisionId?.let { publishedTutorial(tutorial) })
    }

    @Transactional
    fun createTutorialRevision(tutorialId: UUID, categoryId: UUID, content: TutorialContent): TutorialRevisionView {
        requireTutorial(tutorialId, lock = true)
        requireCategory(categoryId)
        val referenced = content.mediaReferences().values.toSet()
        val existing = repository.findMedia(referenced).map { it.id }.toSet()
        validator.tutorial(content, existing)
        val revision = repository.createTutorialRevision(
            UUID.randomUUID(),
            tutorialId,
            categoryId,
            objectMapper.writeValueAsString(content),
            content.mediaReferences(),
            actor(),
            clock.instant(),
        )
        audit.record(
            AuditAction.TUTORIAL_REVISION_CREATED,
            AuditService.ENTITY_TUTORIAL,
            tutorialId.toString(),
            mapOf("revision" to revision.revisionNumber),
        )
        return TutorialRevisionView(revision, content)
    }

    @Transactional(readOnly = true)
    fun listTutorialRevisions(tutorialId: UUID): List<TutorialRevisionView> {
        requireTutorial(tutorialId)
        return repository.listTutorialRevisions(tutorialId).map(::tutorialRevision)
    }

    @Transactional
    fun publishTutorial(tutorialId: UUID, revisionNumber: Int): AdminTutorialView {
        val tutorial = requireTutorial(tutorialId, lock = true)
        val revision = repository.findTutorialRevision(tutorialId, revisionNumber) ?: throw TutorialRevisionNotFoundException()
        ensureNewerTutorialRevision(tutorial, revision)
        val category = requireCategory(requireNotNull(revision.categoryId))
        if (category.status != TutorialLifecycle.PUBLISHED || category.publishedRevisionId == null) {
            throw TutorialConflictException("the referenced category must be published before the tutorial.", "TUTORIAL_CATEGORY_NOT_PUBLISHED")
        }
        val content = tutorialRevision(revision).content
        repository.publishTutorial(tutorialId, revision.id, content.mediaReferences().values.toSet(), clock.instant())
        audit.record(
            AuditAction.TUTORIAL_PUBLISHED,
            AuditService.ENTITY_TUTORIAL,
            tutorialId.toString(),
            mapOf("revision" to revisionNumber),
        )
        return AdminTutorialView(requireTutorial(tutorialId), TutorialRevisionView(revision, content))
    }

    @Transactional
    fun rollbackTutorial(tutorialId: UUID, revisionNumber: Int): AdminTutorialView {
        requireTutorial(tutorialId, lock = true)
        val historical = repository.findTutorialRevision(tutorialId, revisionNumber) ?: throw TutorialRevisionNotFoundException()
        val category = requireCategory(requireNotNull(historical.categoryId))
        if (category.status != TutorialLifecycle.PUBLISHED) {
            throw TutorialConflictException("the historical revision's category is not published.", "TUTORIAL_CATEGORY_NOT_PUBLISHED")
        }
        val content = tutorialRevision(historical).content
        val copy = repository.createTutorialRevision(
            UUID.randomUUID(),
            tutorialId,
            category.id,
            historical.contentJson,
            content.mediaReferences(),
            actor(),
            clock.instant(),
        )
        repository.publishTutorial(tutorialId, copy.id, content.mediaReferences().values.toSet(), clock.instant())
        audit.record(
            AuditAction.TUTORIAL_REVISION_CREATED,
            AuditService.ENTITY_TUTORIAL,
            tutorialId.toString(),
            mapOf("revision" to copy.revisionNumber),
        )
        audit.record(
            AuditAction.TUTORIAL_PUBLISHED,
            AuditService.ENTITY_TUTORIAL,
            tutorialId.toString(),
            mapOf("revision" to copy.revisionNumber),
        )
        audit.record(
            AuditAction.TUTORIAL_ROLLBACK,
            AuditService.ENTITY_TUTORIAL,
            tutorialId.toString(),
            mapOf("fromRevision" to revisionNumber, "revision" to copy.revisionNumber),
        )
        return AdminTutorialView(requireTutorial(tutorialId), TutorialRevisionView(copy, content))
    }

    @Transactional
    fun archiveTutorial(tutorialId: UUID) = changeTutorialStatus(tutorialId, TutorialLifecycle.ARCHIVED, AuditAction.TUTORIAL_ARCHIVED)

    @Transactional
    fun restoreTutorial(tutorialId: UUID): AdminTutorialView {
        val tutorial = requireTutorial(tutorialId, lock = true)
        val target = if (tutorial.publishedRevisionId == null) TutorialLifecycle.DRAFT else TutorialLifecycle.PUBLISHED
        repository.updateTutorialStatus(tutorialId, target, clock.instant())
        audit.record(AuditAction.TUTORIAL_RESTORED, AuditService.ENTITY_TUTORIAL, tutorialId.toString())
        val restored = requireTutorial(tutorialId)
        return AdminTutorialView(restored, restored.publishedRevisionId?.let { publishedTutorial(restored) })
    }

    @Transactional
    fun reorderTutorials(items: List<ReorderItem>): List<AdminTutorialView> {
        repository.lockTutorialOrder()
        val tutorials = repository.listTutorials()
        validateCompleteOrder(items, tutorials.map { it.id }, allowFeatured = true)
        repository.reorderTutorials(items.map { Triple(it.id, it.position, it.featuredPosition) }, clock.instant())
        audit.record(AuditAction.TUTORIAL_REORDERED, AuditService.ENTITY_TUTORIAL, "all", mapOf("count" to items.size))
        return listAdminTutorials()
    }

    @Transactional(readOnly = true)
    fun publicCategories(): List<PublicCategoryView> = repository.listCategories(publicOnly = true).map(::publicCategory)

    @Transactional(readOnly = true)
    fun publicTutorials(categorySlug: String?, featured: Boolean?): List<PublicTutorialView> =
        repository.listTutorials(publicOnly = true, categorySlug = categorySlug, featured = featured).map(::publicTutorial)

    @Transactional(readOnly = true)
    fun publicTutorial(slug: String): PublicTutorialView = repository.findPublicTutorialBySlug(slug)?.let(::publicTutorial)
        ?: throw TutorialNotFoundException()

    private fun publicTutorial(tutorial: StoredTutorial): PublicTutorialView {
        val revision = publishedTutorial(tutorial)
        val category = publicCategory(requireCategory(requireNotNull(revision.revision.categoryId)))
        val media = repository.findMedia(revision.content.mediaReferences().values.toSet()).associateBy { it.id }
        fun slot(value: MediaSlot): PublicMediaSlot {
            fun asset(id: UUID) = checkNotNull(media[id]?.toPublic(publicBaseUrl)) {
                "published tutorial references missing media"
            }
            return PublicMediaSlot(
                default = asset(value.defaultMediaId),
                alt = value.alt,
                variants = PublicMediaVariants(
                    value.variants.enLight?.let(::asset),
                    value.variants.enDark?.let(::asset),
                    value.variants.arLight?.let(::asset),
                    value.variants.arDark?.let(::asset),
                ),
            )
        }
        val content = revision.content
        return PublicTutorialView(
            tutorial.id, tutorial.slug, category, content.title, content.summary, content.introduction, content.duration, content.level,
            slot(content.cover),
            content.steps.map { PublicTutorialStep(it.id, it.title, it.body, it.tip, it.media?.let(::slot)) },
            tutorial.position, tutorial.featuredPosition, revision.revision.revisionNumber,
        )
    }

    private fun publicCategory(category: StoredCategory): PublicCategoryView {
        val revision = publishedCategory(category)
        return PublicCategoryView(
            category.id,
            category.slug,
            revision.content.label,
            revision.content.iconCode,
            category.position,
            revision.revision.revisionNumber,
        )
    }

    private fun changeCategoryStatus(id: UUID, status: TutorialLifecycle, action: AuditAction): AdminCategoryView {
        requireCategory(id, lock = true)
        repository.updateCategoryStatus(id, status, clock.instant())
        audit.record(action, AuditService.ENTITY_TUTORIAL_CATEGORY, id.toString())
        val updated = requireCategory(id)
        return AdminCategoryView(updated, updated.publishedRevisionId?.let { publishedCategory(updated) })
    }

    private fun changeTutorialStatus(id: UUID, status: TutorialLifecycle, action: AuditAction): AdminTutorialView {
        requireTutorial(id, lock = true)
        repository.updateTutorialStatus(id, status, clock.instant())
        audit.record(action, AuditService.ENTITY_TUTORIAL, id.toString())
        val updated = requireTutorial(id)
        return AdminTutorialView(updated, updated.publishedRevisionId?.let { publishedTutorial(updated) })
    }

    private fun validateCompleteOrder(items: List<ReorderItem>, existingIds: List<UUID>, allowFeatured: Boolean) {
        if (items.map { it.id }.toSet() != existingIds.toSet() || items.size != existingIds.size) {
            throw BadRequestException("reorder must contain every identity exactly once.", "INVALID_TUTORIAL_ORDER")
        }
        if (items.map { it.position }.sorted() != items.indices.toList()) {
            throw BadRequestException("positions must be unique and contiguous from zero.", "INVALID_TUTORIAL_ORDER")
        }
        val featured = items.mapNotNull { it.featuredPosition }
        if (!allowFeatured && featured.isNotEmpty()) throw BadRequestException("category ordering does not accept featured positions.")
        if (featured.sorted() != featured.indices.toList()) {
            throw BadRequestException("featured positions must be unique and contiguous from zero.", "INVALID_FEATURED_ORDER")
        }
    }

    private fun ensureNewerCategoryRevision(category: StoredCategory, revision: StoredRevision) {
        val current = category.publishedRevisionId?.let { id -> repository.listCategoryRevisions(category.id).firstOrNull { it.id == id } }
        if (current != null && revision.revisionNumber <= current.revisionNumber) {
            throw TutorialConflictException("use rollback to republish an historical category revision.", "HISTORICAL_REVISION_REQUIRES_ROLLBACK")
        }
    }

    private fun ensureNewerTutorialRevision(tutorial: StoredTutorial, revision: StoredRevision) {
        val current = tutorial.publishedRevisionId?.let(repository::findTutorialRevisionById)
        if (current != null && revision.revisionNumber <= current.revisionNumber) {
            throw TutorialConflictException("use rollback to republish an historical tutorial revision.", "HISTORICAL_REVISION_REQUIRES_ROLLBACK")
        }
    }

    private fun publishedCategory(category: StoredCategory): CategoryRevisionView {
        val id = checkNotNull(category.publishedRevisionId) { "published category pointer is missing" }
        val revision = checkNotNull(repository.listCategoryRevisions(category.id).firstOrNull { it.id == id }) {
            "published category revision is missing"
        }
        return categoryRevision(revision)
    }

    private fun publishedTutorial(tutorial: StoredTutorial): TutorialRevisionView {
        val id = checkNotNull(tutorial.publishedRevisionId) { "published tutorial pointer is missing" }
        return tutorialRevision(checkNotNull(repository.findTutorialRevisionById(id)) { "published tutorial revision is missing" })
    }

    private fun categoryRevision(revision: StoredRevision) =
        CategoryRevisionView(revision, objectMapper.readValue(revision.contentJson, CategoryContent::class.java))
    private fun tutorialRevision(revision: StoredRevision) =
        TutorialRevisionView(revision, objectMapper.readValue(revision.contentJson, TutorialContent::class.java))
    private fun requireCategory(id: UUID, lock: Boolean = false) = repository.findCategory(id, lock) ?: throw TutorialCategoryNotFoundException()
    private fun requireTutorial(id: UUID, lock: Boolean = false) = repository.findTutorial(id, lock) ?: throw TutorialNotFoundException()
    private fun actor(): UUID? = currentUser.getOrNull()?.id
}
