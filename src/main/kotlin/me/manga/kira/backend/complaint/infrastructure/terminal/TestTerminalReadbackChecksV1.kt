package me.manga.kira.backend.complaint.infrastructure.terminal

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalFetchedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalListedVersionV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.requireJournalVersion
import java.security.MessageDigest
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Base64

/** Exact frozen/raw/SDK comparison only. Fetched DTOs never construct a native original/proof. */
internal object TestTerminalReadbackChecksV1 {
    fun check(row: TestTerminalDurableRowV1, listed: JournalListedVersionV1, fetched: JournalFetchedVersionV1): Pair<Instant, Instant> {
        val response = fetched.response
        val raw = fetched.observed.response
        val headers = raw.headers()
        requireOrdinarySeal(raw.statusCode() == 200 && response.sdkHttpResponse().statusCode() == 200 &&
            requireJournalVersion(response.versionId()) == listed.versionId && JournalS3HttpWireV1.single(headers, "x-amz-version-id") == listed.versionId)
        val wire = checkNotNull(row.wireBytes())
        try { requireOrdinarySeal(MessageDigest.isEqual(wire, fetched.bytes)) } finally { wire.fill(0) }
        requireOrdinarySeal(response.contentLength() == listed.size && fetched.bytes.size.toLong() == listed.size &&
            fetched.observed.size == fetched.bytes.size && fetched.observed.wireSha256 == row.wireSha256 && Sha256.hex(fetched.bytes) == row.wireSha256)
        requireOrdinarySeal(response.deleteMarker() != true && response.contentRange() == null && response.contentEncoding() == null && response.expiration() == null)
        requireOrdinarySeal(response.contentType() == JournalS3HttpWireV1.CONTENT_TYPE && JournalS3HttpWireV1.single(headers, "Content-Type") == JournalS3HttpWireV1.CONTENT_TYPE)
        requireOrdinarySeal(response.metadata() == row.metadata() && JournalS3HttpWireV1.metadata(headers) == row.metadata() && (response.missingMeta() == null || response.missingMeta() == 0))
        val checksum = JournalS3HttpWireV1.single(headers, "x-amz-checksum-sha256")
        requireOrdinarySeal(checksum == row.checksumSha256 && response.checksumSHA256() == checksum &&
            JournalS3HttpWireV1.single(headers, "x-amz-checksum-type") in listOf(null, "FULL_OBJECT"))
        val hash = MessageDigest.getInstance("SHA-256").digest(fetched.bytes)
        try { requireOrdinarySeal(Base64.getEncoder().encodeToString(hash) == checksum) } finally { hash.fill(0) }
        requireOrdinarySeal(response.objectLockModeAsString() == "COMPLIANCE" && JournalS3HttpWireV1.single(headers, "x-amz-object-lock-mode") == "COMPLIANCE")
        val created = checkNotNull(response.lastModified())
        val retained = checkNotNull(response.objectLockRetainUntilDate())
        requireOrdinarySeal(created == listed.lastModified &&
            ZonedDateTime.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "Last-Modified")), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() == created &&
            Instant.parse(checkNotNull(JournalS3HttpWireV1.single(headers, "x-amz-object-lock-retain-until-date"))) == retained)
        return created to retained
    }
}
