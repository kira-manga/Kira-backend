package me.manga.kira.backend.tutorial.application

import me.manga.kira.backend.audit.application.AuditService
import me.manga.kira.backend.audit.domain.AuditAction
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.common.exception.PayloadTooLargeException
import me.manga.kira.backend.config.KiraTutorialProperties
import me.manga.kira.backend.security.CurrentUser
import me.manga.kira.backend.tutorial.domain.StoredMedia
import me.manga.kira.backend.tutorial.domain.TutorialRepository
import me.manga.kira.backend.user.domain.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Clock
import java.util.UUID
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

@Service
class TutorialMediaService(
    private val repository: TutorialRepository,
    private val properties: KiraTutorialProperties,
    private val currentUser: CurrentUser,
    private val audit: AuditService,
    private val clock: Clock,
) {
    init {
        Files.createDirectories(properties.mediaDirectory)
    }

    @Transactional
    @Suppress("TooGenericExceptionCaught")
    fun upload(file: MultipartFile): StoredMedia {
        val raw = file.inputStream.use { it.readNBytes(MAX_BYTES + 1) }
        if (raw.size > MAX_BYTES) throw PayloadTooLargeException("tutorial media must not exceed 4 MiB.")
        val format = detect(raw)
        val decoded = decode(raw, format)
        val encoded = encode(decoded.image, format)
        if (encoded.size > MAX_BYTES) throw PayloadTooLargeException("sanitized tutorial media must not exceed 4 MiB.")
        val id = UUID.randomUUID()
        val extension = if (format == ImageFormat.PNG) "png" else "jpg"
        val filename = "$id.$extension"
        val destination = safePath(filename)
        val temporary = Files.createTempFile(properties.mediaDirectory, ".upload-", ".$extension")
        try {
            Files.write(temporary, encoded)
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE)
            val media = StoredMedia(
                id, filename, format.contentType, encoded.size.toLong(), decoded.width, decoded.height,
                sha256(encoded), published = false, currentUser.getOrNull()?.id, clock.instant(),
            )
            repository.createMedia(media)
            audit.record(
                AuditAction.TUTORIAL_MEDIA_UPLOADED,
                AuditService.ENTITY_TUTORIAL_MEDIA,
                id.toString(),
                mapOf("sha256" to media.sha256, "bytes" to media.byteSize),
            )
            return media
        } catch (exception: Exception) {
            Files.deleteIfExists(temporary)
            Files.deleteIfExists(destination)
            throw exception
        }
    }

    @Transactional(readOnly = true)
    fun list(): List<StoredMedia> = repository.listMedia()

    @Transactional(readOnly = true)
    fun loadForDelivery(id: UUID): Pair<StoredMedia, Path> {
        val media = repository.findMedia(id) ?: throw TutorialMediaNotFoundException()
        val admin = currentUser.getOrNull()?.role == Role.ADMIN
        if (!media.published && !admin) throw TutorialMediaNotFoundException()
        val path = safePath(media.storageFilename)
        if (!Files.isRegularFile(path)) throw TutorialMediaNotFoundException()
        return media to path
    }

    @Transactional(noRollbackFor = [TutorialMediaInUseException::class])
    fun delete(id: UUID) {
        val media = repository.findMedia(id) ?: throw TutorialMediaNotFoundException()
        val references = repository.mediaReferenceCount(id)
        if (references > 0) {
            audit.record(
                AuditAction.TUTORIAL_MEDIA_DELETION_REFUSED,
                AuditService.ENTITY_TUTORIAL_MEDIA,
                id.toString(),
                mapOf("references" to references),
            )
            throw TutorialMediaInUseException()
        }
        if (!repository.deleteMedia(id)) throw TutorialMediaNotFoundException()
        audit.record(AuditAction.TUTORIAL_MEDIA_DELETED, AuditService.ENTITY_TUTORIAL_MEDIA, id.toString())
        Files.deleteIfExists(safePath(media.storageFilename))
    }

    @Transactional
    fun importSeedAsset(bytes: ByteArray, published: Boolean = true): StoredMedia {
        val format = detect(bytes)
        val decoded = decode(bytes, format)
        val encoded = encode(decoded.image, format)
        val checksum = sha256(encoded)
        repository.listMedia().firstOrNull { it.sha256 == checksum }?.let { existing ->
            val path = safePath(existing.storageFilename)
            if (!Files.isRegularFile(path) || sha256(Files.readAllBytes(path)) != checksum) {
                Files.write(path, encoded)
            }
            return existing
        }
        val id = UUID.randomUUID()
        val extension = if (format == ImageFormat.PNG) "png" else "jpg"
        val filename = "$id.$extension"
        Files.write(safePath(filename), encoded)
        val media = repository.createMedia(
            StoredMedia(
                id, filename, format.contentType, encoded.size.toLong(), decoded.width, decoded.height,
                checksum, published, null, clock.instant(),
            ),
        )
        audit.record(
            AuditAction.TUTORIAL_MEDIA_UPLOADED,
            AuditService.ENTITY_TUTORIAL_MEDIA,
            id.toString(),
            mapOf("sha256" to media.sha256, "bytes" to media.byteSize),
            actorUserId = null,
        )
        return media
    }

    private fun safePath(filename: String): Path {
        check(SAFE_FILENAME.matches(filename)) { "invalid server-owned tutorial media filename" }
        val root = properties.mediaDirectory.toAbsolutePath().normalize()
        val resolved = root.resolve(filename).normalize()
        check(resolved.parent == root) { "tutorial media path escaped storage directory" }
        return resolved
    }

    private fun detect(bytes: ByteArray): ImageFormat = when {
        bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(PNG_SIGNATURE) -> ImageFormat.PNG
        bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() && bytes[2] == 0xff.toByte() -> ImageFormat.JPEG
        else -> throw BadRequestException("only signature-valid JPEG and PNG tutorial media is accepted.", "INVALID_TUTORIAL_MEDIA_FORMAT")
    }

    @Suppress("ComplexCondition", "TooGenericExceptionCaught")
    private fun decode(bytes: ByteArray, format: ImageFormat): DecodedImage {
        ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw BadRequestException("tutorial media is malformed.", "MALFORMED_TUTORIAL_MEDIA")
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                if (width <= 0 || height <= 0 || width > MAX_DIMENSION || height > MAX_DIMENSION || width.toLong() * height > MAX_PIXELS) {
                    throw BadRequestException("tutorial media dimensions exceed the allowed limits.", "INVALID_TUTORIAL_MEDIA_DIMENSIONS")
                }
                val image = reader.read(0) ?: throw BadRequestException("tutorial media is malformed.", "MALFORMED_TUTORIAL_MEDIA")
                val actual = reader.formatName.lowercase()
                if ((format == ImageFormat.PNG && actual != "png") || (format == ImageFormat.JPEG && actual !in setOf("jpeg", "jpg"))) {
                    throw BadRequestException("tutorial media signature does not match its image data.", "FORGED_TUTORIAL_MEDIA")
                }
                return DecodedImage(image, width, height)
            } catch (exception: BadRequestException) {
                throw exception
            } catch (_: Exception) {
                throw BadRequestException("tutorial media is malformed.", "MALFORMED_TUTORIAL_MEDIA")
            } finally {
                reader.dispose()
            }
        }
    }

    private fun encode(source: BufferedImage, format: ImageFormat): ByteArray {
        val output = ByteArrayOutputStream()
        if (format == ImageFormat.PNG) {
            if (!ImageIO.write(source, "png", output)) throw BadRequestException("PNG encoding is unavailable.")
        } else {
            val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().use { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, source.width, source.height)
                graphics.drawImage(source, 0, 0, null)
            }
            val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
            ImageIO.createImageOutputStream(output).use { imageOutput ->
                writer.output = imageOutput
                val params = writer.defaultWriteParam
                if (params.canWriteCompressed()) {
                    params.compressionMode = ImageWriteParam.MODE_EXPLICIT
                    params.compressionQuality = 0.92f
                }
                writer.write(null, IIOImage(rgb, null, null), params)
            }
            writer.dispose()
        }
        return output.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private data class DecodedImage(val image: BufferedImage, val width: Int, val height: Int)
    private enum class ImageFormat(val contentType: String) { JPEG("image/jpeg"), PNG("image/png") }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        const val MAX_DIMENSION = 4096
        const val MAX_PIXELS = 16_000_000L
        private val SAFE_FILENAME = Regex("[0-9a-f-]{36}\\.(?:jpg|png)")
        private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}

private inline fun <T : java.awt.Graphics> T.use(block: (T) -> Unit) {
    try {
        block(this)
    } finally {
        dispose()
    }
}
