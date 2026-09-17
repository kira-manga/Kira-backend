package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationExceptionV1
import me.manga.kira.backend.complaint.infrastructure.journal.JournalPublicationFailureV1
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationCall
import me.manga.kira.backend.complaint.infrastructure.journal.journalPublicationClose
import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication
import me.manga.kira.backend.complaint.infrastructure.journal.withJournalPublicationCleanup
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Bound and check the actual bytes BEFORE the SDK's recursive XML tree builder can see them. */
internal object JournalS3XmlPreflightV1 {
    fun inspect(bytes: ByteArray, listing: Boolean, check: () -> Unit) = journalPublicationCall(JournalPublicationFailureV1.INVALID_LISTING) {
        requireJournalPublication(bytes.isNotEmpty(), JournalPublicationFailureV1.INVALID_LISTING)
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLInputFactory.IS_COALESCING, false)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setXMLResolver { _, _, _, _ -> throw XMLStreamException("External journal XML resolution refused.") }
        }
        check()
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        withJournalPublicationCleanup({ inspect(reader, listing, check) }, { journalPublicationClose { reader.close() } })
        check()
    }

    private fun inspect(reader: XMLStreamReader, listing: Boolean, check: () -> Unit) {
        val shape = if (listing) ListShape() else null
        var depth = 0
        var elements = 0
        var tokens = 0
        while (reader.hasNext()) {
            check()
            val token = reader.next()
            check()
            requireJournalPublication(++tokens <= 1536, JournalPublicationFailureV1.LIMIT_EXCEEDED)
            when (token) {
                XMLStreamConstants.START_ELEMENT -> {
                    requireJournalPublication(++depth <= 16 && ++elements <= 192, JournalPublicationFailureV1.LIMIT_EXCEEDED)
                    requireJournalPublication(
                        reader.localName.length in 1..256 && reader.attributeCount <= 16 && reader.namespaceCount <= 8,
                        JournalPublicationFailureV1.LIMIT_EXCEEDED,
                    )
                    if (shape != null) {
                        shape.start(reader)
                    } else if (depth == 1) {
                        requireJournalPublication(reader.localName == "Error", JournalPublicationFailureV1.INVALID_READBACK)
                    }
                }

                XMLStreamConstants.END_ELEMENT -> {
                    shape?.end(reader.localName)
                    depth--
                    requireJournalPublication(depth >= 0, JournalPublicationFailureV1.INVALID_LISTING)
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> shape?.text(reader)

                XMLStreamConstants.COMMENT, XMLStreamConstants.PROCESSING_INSTRUCTION -> shape?.markup()

                XMLStreamConstants.DTD,
                XMLStreamConstants.ENTITY_REFERENCE,
                XMLStreamConstants.ENTITY_DECLARATION,
                XMLStreamConstants.NOTATION_DECLARATION,
                -> throw JournalPublicationExceptionV1(JournalPublicationFailureV1.INVALID_LISTING)
            }
        }
        requireJournalPublication(depth == 0 && elements > 0, JournalPublicationFailureV1.INVALID_LISTING)
        shape?.complete()
    }

    /** Prevent missing/duplicate scalar evidence being turned into SDK defaults or last-value wins. */
    private class ListShape {
        private val stack = ArrayList<Node>()
        private var roots = 0
        private var versions = 0

        fun start(reader: XMLStreamReader) {
            val name = reader.localName
            requireJournalPublication(reader.namespaceURI == NAMESPACE && reader.attributeCount == 0, JournalPublicationFailureV1.INVALID_LISTING)
            val parent = stack.lastOrNull()
            if (parent == null) {
                requireJournalPublication(++roots == 1 && name == "ListVersionsResult", JournalPublicationFailureV1.INVALID_LISTING)
            } else {
                val allowed = when (parent.name) {
                    "ListVersionsResult" -> ROOT_FIELDS
                    "Version" -> VERSION_FIELDS
                    "Owner" -> setOf("ID", "DisplayName")
                    else -> emptySet()
                }
                requireJournalPublication(name in allowed, JournalPublicationFailureV1.INVALID_LISTING)
                val repeating = (parent.name == "ListVersionsResult" && name == "Version") ||
                    (parent.name == "Version" && name == "ChecksumAlgorithm")
                requireJournalPublication(parent.children.add(name) || repeating, JournalPublicationFailureV1.INVALID_LISTING)
                if (parent.name == "ListVersionsResult" && name == "Version") {
                    requireJournalPublication(++versions <= 2, JournalPublicationFailureV1.INVALID_LISTING)
                }
            }
            stack.add(Node(name))
        }

        fun text(reader: XMLStreamReader) {
            val parent = stack.lastOrNull() ?: return
            if (parent.name in setOf("ListVersionsResult", "Version", "Owner")) {
                requireJournalPublication(reader.isWhiteSpace, JournalPublicationFailureV1.INVALID_LISTING)
            }
            parent.booleanText?.let { text ->
                requireJournalPublication(reader.textLength <= 5 - text.length, JournalPublicationFailureV1.INVALID_LISTING)
                text.append(reader.textCharacters, reader.textStart, reader.textLength)
            }
        }

        fun markup() {
            val parent = stack.lastOrNull() ?: return
            // SDK XML scalar decoding overwrites text runs separated by comments or processing instructions.
            requireJournalPublication(
                parent.name in setOf("ListVersionsResult", "Version", "Owner"),
                JournalPublicationFailureV1.INVALID_LISTING,
            )
        }

        fun end(name: String) {
            requireJournalPublication(stack.isNotEmpty() && stack.last().name == name, JournalPublicationFailureV1.INVALID_LISTING)
            val node = stack.removeAt(stack.lastIndex)
            val required = when (node.name) {
                "ListVersionsResult" -> ROOT_REQUIRED
                "Version" -> VERSION_REQUIRED
                else -> emptySet()
            }
            requireJournalPublication(node.children.containsAll(required), JournalPublicationFailureV1.INVALID_LISTING)
            node.booleanText?.let { text ->
                // SDK Boolean.parseBoolean turns empty/garbage text into false. Never let that prove absence.
                requireJournalPublication(text.toString() in setOf("true", "false"), JournalPublicationFailureV1.INVALID_LISTING)
            }
        }

        fun complete() = requireJournalPublication(roots == 1 && stack.isEmpty(), JournalPublicationFailureV1.INVALID_LISTING)
        private class Node(val name: String, val children: MutableSet<String> = HashSet()) {
            val booleanText: StringBuilder? = if (name == "IsTruncated" || name == "IsLatest") StringBuilder(5) else null
        }
    }

    private const val NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/"
    private val ROOT_REQUIRED = setOf("Name", "Prefix", "KeyMarker", "VersionIdMarker", "MaxKeys", "IsTruncated", "EncodingType")
    private val ROOT_FIELDS = ROOT_REQUIRED + setOf("Version", "NextKeyMarker", "NextVersionIdMarker", "Delimiter")
    private val VERSION_REQUIRED = setOf("Key", "VersionId", "IsLatest", "LastModified", "Size")
    private val VERSION_FIELDS = VERSION_REQUIRED + setOf("ETag", "Owner", "StorageClass", "ChecksumAlgorithm", "ChecksumType")
}
