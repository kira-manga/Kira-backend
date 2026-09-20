package me.manga.kira.backend.complaint.journal

import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3HttpWireV1
import me.manga.kira.backend.complaint.infrastructure.journal.aws.JournalS3XmlPreflightV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * NOT_RUN: authored raw-body/pre-SDK XML selectors. These pure parser cases issue no original,
 * native readback, inventory-completion or provider-denial authority. Connected signed-request
 * and cleanup coverage must use the genuine registered drain fixture, never a fake original.
 */
class TestOrdinaryInventoryHttpV1Test {
    @Test
    fun `two row continuation shape accepts opaque version ids without imposing lexical version order`() {
        val body = listing(
            version("same-key", "z+/%2F=&", true) + version("same-key", "a+/%25?", false),
            "<NextKeyMarker>same-key</NextKeyMarker><NextVersionIdMarker>a+/%25?</NextVersionIdMarker>",
            truncated = "true",
        )
        inspect(body) // Cursor identity, key binding and progress are checked by the concrete SDK reader, not XML defaults.
    }

    @Test
    fun `absence needs explicit exact false and every required root scalar rather than SDK boolean defaults`() {
        val empty = listing()
        inspect(empty)
        listOf("", "False", "FALSE", " false ", "0", "unknown").forEach { scalar ->
            invalid(empty.replace("<IsTruncated>false</IsTruncated>", "<IsTruncated>$scalar</IsTruncated>"))
        }
        listOf("Name", "Prefix", "KeyMarker", "VersionIdMarker", "MaxKeys", "IsTruncated", "EncodingType").forEach { name ->
            invalid(empty.replace(Regex("<$name>.*?</$name>"), ""))
        }
        invalid(empty.replace("</ListVersionsResult>", "<IsTruncated>false</IsTruncated></ListVersionsResult>"))
    }

    @Test
    fun `delete markers common prefixes unknown fields mixed namespace and third version fail before SDK tree decoding`() {
        val row = version("same-key", "version-one", true)
        listOf(
            "<DeleteMarker><Key>same-key</Key><VersionId>deleted</VersionId></DeleteMarker>",
            "<CommonPrefixes><Prefix>hidden-family/</Prefix></CommonPrefixes>",
            "<Unexpected>hidden</Unexpected>",
            "<Version xmlns=\"urn:foreign\"><Key>same-key</Key></Version>",
        ).forEach { extra -> invalid(listing(row, extra)) }
        invalid(listing(row + version("same-key", "version-two", false) + version("same-key", "version-three", false)))
        invalid(listing(row.replace("<Version>", "<Version fabricated=\"true\">")))
        invalid(listing(row.replace("<VersionId>version-one</VersionId>", "<VersionId>version-one</VersionId><VersionId>hidden</VersionId>")))
    }

    @Test
    fun `DTD scalar comments and processing instructions cannot hide different pagination or latest evidence`() {
        invalid("<!DOCTYPE ListVersionsResult [<!ENTITY hidden SYSTEM \"file:///not-read\">]>" + listing())
        invalid(listing().replace("<IsTruncated>false</IsTruncated>", "<IsTruncated>fa<!-- split -->lse</IsTruncated>"))
        invalid(listing().replace("<KeyMarker></KeyMarker>", "<KeyMarker>old<?split value?>new</KeyMarker>"))
        val row = version("same-key", "version-one", true)
        listOf("", "TRUE", "true ", "garbage").forEach { latest ->
            invalid(listing(row.replace("<IsLatest>true</IsLatest>", "<IsLatest>$latest</IsLatest>")))
        }
    }

    @Test
    fun `bounded raw reader refuses oversized short extra and zero-progress native bodies`() {
        val reads = intArrayOf(0)
        val untouched = object : InputStream() {
            override fun read(): Int { reads[0]++; return -1 }
        }
        assertEquals(JournalPublicationFailureV1.LIMIT_EXCEEDED,
            assertThrows<JournalPublicationExceptionV1> { JournalS3HttpWireV1.read(untouched, 9, 8) {} }.code)
        assertEquals(0, reads[0])
        assertEquals(JournalPublicationFailureV1.INVALID_READBACK,
            assertThrows<JournalPublicationExceptionV1> { JournalS3HttpWireV1.read(ByteArrayInputStream(byteArrayOf(1)), 2, 8) {} }.code)
        assertEquals(JournalPublicationFailureV1.LIMIT_EXCEEDED,
            assertThrows<JournalPublicationExceptionV1> { JournalS3HttpWireV1.read(ByteArrayInputStream(byteArrayOf(1, 2)), 1, 8) {} }.code)
        val noProgress = object : InputStream() {
            override fun read(): Int = 0
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int = 0
        }
        assertEquals(JournalPublicationFailureV1.INVALID_READBACK,
            assertThrows<JournalPublicationExceptionV1> { JournalS3HttpWireV1.read(noProgress, 1, 8) {} }.code)
    }

    private fun inspect(body: String) = JournalS3XmlPreflightV1.inspect(body.toByteArray(Charsets.UTF_8), true) {}

    private fun invalid(body: String) {
        assertEquals(JournalPublicationFailureV1.INVALID_LISTING, assertThrows<JournalPublicationExceptionV1> { inspect(body) }.code)
    }

    private fun listing(rows: String = "", next: String = "", truncated: String = "false"): String =
        "<ListVersionsResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">" +
            "<Name>fixture-bucket</Name><Prefix>fixture-ordinary/</Prefix><KeyMarker></KeyMarker><VersionIdMarker></VersionIdMarker>" +
            "<MaxKeys>2</MaxKeys><IsTruncated>$truncated</IsTruncated><EncodingType>url</EncodingType>$rows$next</ListVersionsResult>"

    private fun version(key: String, version: String, latest: Boolean): String =
        "<Version><Key>${xml(key)}</Key><VersionId>${xml(version)}</VersionId><IsLatest>$latest</IsLatest>" +
            "<LastModified>2030-01-02T03:04:05Z</LastModified><Size>1</Size></Version>"

    private fun xml(value: String): String = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
