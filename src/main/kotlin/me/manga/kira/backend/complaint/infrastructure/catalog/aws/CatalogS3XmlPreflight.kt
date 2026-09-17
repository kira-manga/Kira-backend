package me.manga.kira.backend.complaint.infrastructure.catalog.aws

import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackException
import me.manga.kira.backend.complaint.domain.catalog.CatalogReadbackFailure
import me.manga.kira.backend.complaint.domain.catalog.requireCatalogReadback
import me.manga.kira.backend.complaint.infrastructure.catalog.catalogProviderCall
import java.io.ByteArrayInputStream
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Bound recursive SDK decoding and refuse LIST scalar forms that its XML decoder can silently change. */
internal object CatalogS3XmlPreflight {
    fun inspect(bytes: ByteArray, successfulListing: Boolean, pageSize: Int, check: () -> Unit) = catalogProviderCall {
        val factory = XMLInputFactory.newDefaultFactory().apply {
            setProperty(XMLInputFactory.SUPPORT_DTD, false)
            setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
            setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
            setProperty(XMLInputFactory.IS_COALESCING, false)
            setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
            setXMLResolver { _, _, _, _ -> throw XMLStreamException("External XML resolution refused.") }
        }
        val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
        withS3Cleanup(
            { inspect(reader, successfulListing, pageSize, check) },
            { catalogProviderCall(CatalogReadbackFailure.CLOSE_FAILURE) { reader.close() } },
        )
    }

    private fun inspect(reader: XMLStreamReader, successfulListing: Boolean, pageSize: Int, check: () -> Unit) {
        val shape = if (successfulListing) ListShape() else null
        val maximumElements = 128 + pageSize * 32
        var depth = 0
        var elements = 0
        var tokens = 0
        while (reader.hasNext()) {
            check()
            val token = reader.next()
            check()
            requireCatalogReadback(++tokens <= maximumElements * 8, CatalogReadbackFailure.LIMIT_EXCEEDED)
            when (token) {
                XMLStreamConstants.START_ELEMENT -> {
                    requireCatalogReadback(++depth <= 16 && ++elements <= maximumElements, CatalogReadbackFailure.LIMIT_EXCEEDED)
                    requireCatalogReadback(
                        reader.localName.length <= 256 && reader.attributeCount <= 16 && reader.namespaceCount <= 8,
                        CatalogReadbackFailure.LIMIT_EXCEEDED,
                    )
                    shape?.start(reader)
                }

                XMLStreamConstants.END_ELEMENT -> {
                    shape?.end(reader.localName)
                    depth--
                }

                XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> shape?.text(reader)

                XMLStreamConstants.COMMENT, XMLStreamConstants.PROCESSING_INSTRUCTION -> shape?.markup()

                XMLStreamConstants.DTD,
                XMLStreamConstants.ENTITY_REFERENCE,
                XMLStreamConstants.ENTITY_DECLARATION,
                XMLStreamConstants.NOTATION_DECLARATION,
                -> throw CatalogReadbackException(CatalogReadbackFailure.INVALID_READBACK)
            }
        }
        requireCatalogReadback(depth == 0 && elements > 0, CatalogReadbackFailure.INVALID_READBACK)
        shape?.complete()
    }

    /** Missing evidence and request binding remain the adapter's responsibility; never invent fields here. */
    private class ListShape {
        private val stack = ArrayList<Node>()
        private var roots = 0

        fun start(reader: XMLStreamReader) {
            val name = reader.localName
            requireCatalogReadback(reader.namespaceURI == NAMESPACE && reader.attributeCount == 0, CatalogReadbackFailure.INVALID_LISTING)
            val parent = stack.lastOrNull()
            if (parent == null) {
                requireCatalogReadback(++roots == 1 && name == "ListVersionsResult", CatalogReadbackFailure.INVALID_LISTING)
            } else {
                val allowed = when (parent.name) {
                    "ListVersionsResult" -> ROOT_FIELDS
                    "Version" -> VERSION_FIELDS
                    "DeleteMarker" -> DELETE_MARKER_FIELDS
                    "Owner" -> OWNER_FIELDS
                    "RestoreStatus" -> RESTORE_FIELDS
                    "CommonPrefixes" -> COMMON_PREFIX_FIELDS
                    else -> emptySet()
                }
                requireCatalogReadback(name in allowed, CatalogReadbackFailure.INVALID_LISTING)
                val repeating = (parent.name == "ListVersionsResult" && name in REPEATING_ROOT_FIELDS) ||
                    (parent.name == "Version" && name == "ChecksumAlgorithm")
                requireCatalogReadback(parent.children.add(name) || repeating, CatalogReadbackFailure.INVALID_LISTING)
            }
            stack.add(Node(name))
        }

        fun text(reader: XMLStreamReader) {
            val parent = stack.lastOrNull() ?: return
            if (parent.name in CONTAINERS) requireCatalogReadback(reader.isWhiteSpace, CatalogReadbackFailure.INVALID_LISTING)
            parent.booleanText?.let { text ->
                requireCatalogReadback(reader.textLength <= 5 - text.length, CatalogReadbackFailure.INVALID_LISTING)
                text.append(reader.textCharacters, reader.textStart, reader.textLength)
            }
        }

        fun markup() {
            val parent = stack.lastOrNull() ?: return
            // SDK2.54.19 overwrites, rather than joins, scalar text runs split by COMMENT/PI.
            requireCatalogReadback(parent.name in CONTAINERS, CatalogReadbackFailure.INVALID_LISTING)
        }

        fun end(name: String) {
            requireCatalogReadback(stack.isNotEmpty() && stack.last().name == name, CatalogReadbackFailure.INVALID_LISTING)
            val node = stack.removeAt(stack.lastIndex)
            node.booleanText?.let { text ->
                // Boolean.parseBoolean would silently turn missing/garbage text into false, including IsTruncated.
                val value = text.toString()
                requireCatalogReadback(value == "true" || value == "false", CatalogReadbackFailure.INVALID_LISTING)
            }
        }

        fun complete() = requireCatalogReadback(roots == 1 && stack.isEmpty(), CatalogReadbackFailure.INVALID_LISTING)

        private class Node(val name: String, val children: MutableSet<String> = HashSet()) {
            val booleanText: StringBuilder? = if (name in BOOLEAN_FIELDS) StringBuilder(5) else null
        }
    }

    private const val NAMESPACE = "http://s3.amazonaws.com/doc/2006-03-01/"
    private val CONTAINERS = setOf("ListVersionsResult", "Version", "DeleteMarker", "Owner", "RestoreStatus", "CommonPrefixes")
    private val REPEATING_ROOT_FIELDS = setOf("Version", "DeleteMarker", "CommonPrefixes")
    private val BOOLEAN_FIELDS = setOf("IsTruncated", "IsLatest", "IsRestoreInProgress")
    private val ROOT_FIELDS = setOf(
        "Name",
        "Prefix",
        "KeyMarker",
        "VersionIdMarker",
        "MaxKeys",
        "IsTruncated",
        "EncodingType",
        "NextKeyMarker",
        "NextVersionIdMarker",
        "Delimiter",
        "Version",
        "DeleteMarker",
        "CommonPrefixes",
    )
    private val VERSION_FIELDS = setOf(
        "Key",
        "VersionId",
        "IsLatest",
        "LastModified",
        "Size",
        "ETag",
        "Owner",
        "StorageClass",
        "ChecksumAlgorithm",
        "ChecksumType",
        "RestoreStatus",
    )
    private val DELETE_MARKER_FIELDS = setOf("Key", "VersionId", "IsLatest", "LastModified", "Owner")
    private val OWNER_FIELDS = setOf("ID", "DisplayName")
    private val RESTORE_FIELDS = setOf("IsRestoreInProgress", "RestoreExpiryDate")
    private val COMMON_PREFIX_FIELDS = setOf("Prefix")
}
