package me.manga.kira.backend.tutorial.application

import me.manga.kira.backend.common.exception.ConflictException
import me.manga.kira.backend.common.exception.NotFoundException

class TutorialNotFoundException : NotFoundException("tutorial not found.", "TUTORIAL_NOT_FOUND")
class TutorialCategoryNotFoundException : NotFoundException("tutorial category not found.", "TUTORIAL_CATEGORY_NOT_FOUND")
class TutorialRevisionNotFoundException : NotFoundException("tutorial revision not found.", "TUTORIAL_REVISION_NOT_FOUND")
class TutorialMediaNotFoundException : NotFoundException("tutorial media not found.", "TUTORIAL_MEDIA_NOT_FOUND")
class TutorialConflictException(detail: String, code: String = "TUTORIAL_CONFLICT") : ConflictException(detail, code)
class TutorialMediaInUseException :
    ConflictException(
        "media is referenced by a retained tutorial revision and cannot be deleted.",
        "TUTORIAL_MEDIA_IN_USE",
    )
