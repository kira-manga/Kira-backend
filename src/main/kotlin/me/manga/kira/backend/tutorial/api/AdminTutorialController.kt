package me.manga.kira.backend.tutorial.api

import jakarta.validation.Valid
import me.manga.kira.backend.tutorial.application.TutorialMediaService
import me.manga.kira.backend.tutorial.application.TutorialService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/v1/admin")
class AdminTutorialController(private val tutorials: TutorialService, private val media: TutorialMediaService) {
    @PostMapping("/tutorial-categories")
    @ResponseStatus(HttpStatus.CREATED)
    fun createCategory(@Valid @RequestBody request: CreateIdentityRequest) = AdminCategoryResponse.of(tutorials.createCategory(request.slug, request.position))

    @GetMapping("/tutorial-categories")
    fun categories() = tutorials.listAdminCategories().map(AdminCategoryResponse::of)

    @PostMapping("/tutorial-categories/{id}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    fun categoryRevision(@PathVariable id: UUID, @Valid @RequestBody request: CategoryRevisionRequest) =
        CategoryRevisionResponse.of(tutorials.createCategoryRevision(id, request.domain()))

    @GetMapping("/tutorial-categories/{id}/revisions")
    fun categoryRevisions(@PathVariable id: UUID) = tutorials.listCategoryRevisions(id).map(CategoryRevisionResponse::of)

    @PostMapping("/tutorial-categories/{id}/revisions/{revision}/publish")
    fun publishCategory(@PathVariable id: UUID, @PathVariable revision: Int) = AdminCategoryResponse.of(tutorials.publishCategory(id, revision))

    @PostMapping("/tutorial-categories/{id}/revisions/{revision}/rollback")
    fun rollbackCategory(@PathVariable id: UUID, @PathVariable revision: Int) = AdminCategoryResponse.of(tutorials.rollbackCategory(id, revision))

    @PostMapping("/tutorial-categories/{id}/archive")
    fun archiveCategory(@PathVariable id: UUID) = AdminCategoryResponse.of(tutorials.archiveCategory(id))

    @PostMapping("/tutorial-categories/{id}/restore")
    fun restoreCategory(@PathVariable id: UUID) = AdminCategoryResponse.of(tutorials.restoreCategory(id))

    @PostMapping("/tutorial-categories/reorder")
    fun reorderCategories(@Valid @RequestBody request: ReorderRequest) =
        tutorials.reorderCategories(request.items.map { it.domain() }).map(AdminCategoryResponse::of)

    @PostMapping("/tutorials")
    @ResponseStatus(HttpStatus.CREATED)
    fun createTutorial(@Valid @RequestBody request: CreateIdentityRequest) =
        AdminTutorialResponse.of(tutorials.createTutorial(request.slug, request.position, request.featuredPosition))

    @GetMapping("/tutorials")
    fun tutorials() = tutorials.listAdminTutorials().map(AdminTutorialResponse::of)

    @PostMapping("/tutorials/{id}/revisions")
    @ResponseStatus(HttpStatus.CREATED)
    fun tutorialRevision(@PathVariable id: UUID, @Valid @RequestBody request: TutorialRevisionRequest) =
        TutorialRevisionResponse.of(tutorials.createTutorialRevision(id, request.categoryId, request.domain()))

    @GetMapping("/tutorials/{id}/revisions")
    fun tutorialRevisions(@PathVariable id: UUID) = tutorials.listTutorialRevisions(id).map(TutorialRevisionResponse::of)

    @PostMapping("/tutorials/{id}/revisions/{revision}/publish")
    fun publishTutorial(@PathVariable id: UUID, @PathVariable revision: Int) = AdminTutorialResponse.of(tutorials.publishTutorial(id, revision))

    @PostMapping("/tutorials/{id}/revisions/{revision}/rollback")
    fun rollbackTutorial(@PathVariable id: UUID, @PathVariable revision: Int) = AdminTutorialResponse.of(tutorials.rollbackTutorial(id, revision))

    @PostMapping("/tutorials/{id}/archive")
    fun archiveTutorial(@PathVariable id: UUID) = AdminTutorialResponse.of(tutorials.archiveTutorial(id))

    @PostMapping("/tutorials/{id}/restore")
    fun restoreTutorial(@PathVariable id: UUID) = AdminTutorialResponse.of(tutorials.restoreTutorial(id))

    @PostMapping("/tutorials/reorder")
    fun reorderTutorials(@Valid @RequestBody request: ReorderRequest) =
        tutorials.reorderTutorials(request.items.map { it.domain() }).map(AdminTutorialResponse::of)

    @PostMapping("/tutorial-media", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.CREATED)
    fun uploadMedia(@RequestPart("file") file: MultipartFile) = TutorialMediaResponse.of(media.upload(file))

    @GetMapping("/tutorial-media")
    fun media() = media.list().map(TutorialMediaResponse::of)

    @DeleteMapping("/tutorial-media/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteMedia(@PathVariable id: UUID) = media.delete(id)
}
