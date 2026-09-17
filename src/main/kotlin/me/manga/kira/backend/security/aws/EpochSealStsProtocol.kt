package me.manga.kira.backend.security.aws

import java.io.ByteArrayInputStream
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.Instant
import javax.xml.XMLConstants
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLStreamConstants
import javax.xml.stream.XMLStreamException
import javax.xml.stream.XMLStreamReader

/** Fixed STS Query 2011-06-15 request and closed XML response preflight, before SDK recursive decoding. */
internal object EpochSealStsProtocol {
    const val VERSION = "2011-06-15"
    const val MAX_REQUEST_BYTES = 12 * 1024
    private const val NAMESPACE = "https://sts.amazonaws.com/doc/2011-06-15/"

    fun request(bytes: ByteArray, call: EpochSealStsCall, check: () -> Unit) {
        check()
        requireEpochSealSts(bytes.size in 1..MAX_REQUEST_BYTES && bytes.all { it.toInt() in 33..126 })
        val parts = bytes.toString(Charsets.US_ASCII).split('&')
        val expected = call.parameters + mapOf("Action" to call.action, "Version" to VERSION)
        requireEpochSealSts(parts.size == expected.size && parts.size <= 6)
        val actual = HashMap<String, String>()
        parts.forEach { part ->
            check()
            val split = part.indexOf('=')
            requireEpochSealSts(split > 0 && split == part.lastIndexOf('='))
            val name = URLDecoder.decode(part.substring(0, split), Charsets.UTF_8)
            val value = URLDecoder.decode(part.substring(split + 1), Charsets.UTF_8)
            requireEpochSealSts(actual.put(name, value) == null && expected[name] == value)
        }
        requireEpochSealSts(actual == expected)
        check()
    }

    fun response(bytes: ByteArray, call: EpochSealStsCall, check: () -> Unit): EpochSealStsWireReport =
        epochSealStsCall(EpochSealStsFailure.PROTOCOL_REJECTED) {
            // All accepted STS scalar grammars are ASCII (a strict UTF-8 subset). No alternative
            // encodings/BOM, entity references, CDATA, DTD or comments can change SDK scalar text.
            requireEpochSealSts(bytes.isNotEmpty())
            bytes.forEachIndexed { index, value ->
                if (index % 1024 == 0) check()
                val n = value.toInt()
                requireEpochSealSts((n in 32..126 || n in listOf(9, 10, 13)) && n != '&'.code)
                requireEpochSealSts(!(n == '<'.code && index + 1 < bytes.size && bytes[index + 1] == '!'.code.toByte()))
            }
            val factory = XMLInputFactory.newDefaultFactory().apply {
                setProperty(XMLInputFactory.SUPPORT_DTD, false)
                setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false)
                setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false)
                setProperty(XMLInputFactory.IS_COALESCING, false)
                setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "")
                setXMLResolver { _, _, _, _ -> throw XMLStreamException("External STS XML resolution refused.") }
            }
            check()
            val reader = factory.createXMLStreamReader(ByteArrayInputStream(bytes))
            val shape = Shape(call.action)
            withEpochSealStsCleanup(
                {
                    withEpochSealStsCleanup({ shape.read(reader, check) }, shape::clearNodes)
                },
                { epochSealStsClose { reader.close() } },
            )
        }

    private class Shape(action: String) {
        private val root = "${action}Response"
        private val result = "${action}Result"
        private val assume = action == "AssumeRole"
        private val stack = ArrayList<Node>(4)
        private val scalars = HashMap<String, String>()
        private val digests = HashMap<String, ByteArray>()
        private var roots = 0

        @Suppress("TooGenericExceptionCaught")
        fun read(reader: XMLStreamReader, check: () -> Unit): EpochSealStsWireReport {
            var tokens = 0
            var elements = 0
            requireEpochSealSts(reader.version == null || reader.version == "1.0")
            requireEpochSealSts(reader.characterEncodingScheme == null || reader.characterEncodingScheme.equals("UTF-8", ignoreCase = true))
            try {
                while (reader.hasNext()) {
                    check()
                    val token = reader.next()
                    check()
                    requireEpochSealSts(++tokens <= 256)
                    when (token) {
                        XMLStreamConstants.START_ELEMENT -> {
                            requireEpochSealSts(++elements <= 32 && stack.size < 4)
                            start(reader)
                        }
                        XMLStreamConstants.END_ELEMENT -> end(reader.localName)
                        XMLStreamConstants.CHARACTERS, XMLStreamConstants.SPACE -> text(reader)
                        XMLStreamConstants.END_DOCUMENT -> requireEpochSealSts(stack.isEmpty() && roots == 1)
                        else -> throw EpochSealStsException(EpochSealStsFailure.PROTOCOL_REJECTED)
                    }
                }
                requireEpochSealSts(roots == 1 && stack.isEmpty())
                check()
                return EpochSealStsWireReport(scalars.toMap(), digests.toMap())
            } catch (failure: Throwable) {
                digests.values.forEach { it.fill(0) }
                throw failure
            }
        }

        private fun children(name: String): Set<String> = when (name) {
            root -> setOf(result, "ResponseMetadata")
            result -> if (assume) {
                setOf("Credentials", "AssumedRoleUser", "PackedPolicySize", "SessionTokenSize", "SessionTokenUtilization")
            } else {
                setOf("UserId", "Account", "Arn")
            }
            "Credentials" -> setOf("AccessKeyId", "SecretAccessKey", "SessionToken", "Expiration")
            "AssumedRoleUser" -> setOf("AssumedRoleId", "Arn")
            "ResponseMetadata" -> setOf("RequestId")
            else -> emptySet()
        }

        private fun start(reader: XMLStreamReader) {
            val name = reader.localName
            requireEpochSealSts(name.length in 1..32 && reader.namespaceURI == NAMESPACE && reader.attributeCount == 0 && reader.namespaceCount <= 1)
            repeat(reader.namespaceCount) { requireEpochSealSts(reader.getNamespaceURI(it) == NAMESPACE) }
            val parent = stack.lastOrNull()
            if (parent == null) {
                requireEpochSealSts(++roots == 1 && name == root)
            } else {
                requireEpochSealSts(name in children(parent.name) && parent.children.add(name))
            }
            val fields = children(name)
            stack.add(Node(name, if (fields.isEmpty()) maximum(name) else 0))
        }

        private fun text(reader: XMLStreamReader) {
            val node = stack.lastOrNull()
            if (node == null || node.bytes.isEmpty()) {
                requireEpochSealSts(reader.isWhiteSpace)
                return
            }
            requireEpochSealSts(reader.textLength <= node.bytes.size - node.count)
            repeat(reader.textLength) { offset ->
                val value = reader.textCharacters[reader.textStart + offset]
                requireEpochSealSts(value in '!'..'~')
                node.bytes[node.count++] = value.code.toByte()
            }
        }

        private fun end(name: String) {
            requireEpochSealSts(stack.isNotEmpty() && stack.last().name == name)
            val node = stack.last()
            try {
                val expected = children(name)
                // SDK 2.54.19 knows the two token-size fields and deprecates PackedPolicySize.
                // These optional observations are bounded/cross-checked, never policy evidence.
                val optional = if (assume && name == result) OPTIONAL_SIZE_FIELDS else emptySet()
                requireEpochSealSts(node.children.containsAll(expected - optional) && expected.containsAll(node.children))
                if (expected.isEmpty()) {
                    requireEpochSealSts(node.count > 0)
                    if (name in CREDENTIAL_FIELDS) {
                        val digest = MessageDigest.getInstance("SHA-256")
                        digest.update(node.bytes, 0, node.count)
                        requireEpochSealSts(digests.put(name, digest.digest()) == null)
                    } else {
                        requireEpochSealSts(scalars.put(name, String(node.bytes, 0, node.count, Charsets.US_ASCII)) == null)
                    }
                }
            } finally {
                node.bytes.fill(0)
                stack.removeAt(stack.lastIndex)
            }
        }

        fun clearNodes() { stack.forEach { it.bytes.fill(0) } }

        private fun maximum(name: String): Int = when (name) {
            "SessionToken" -> 16_384
            "SecretAccessKey" -> 256
            "AccessKeyId", "UserId", "AssumedRoleId", "RequestId" -> 128
            "Arn" -> 512
            "Expiration" -> 40
            "Account" -> 12
            "PackedPolicySize", "SessionTokenUtilization" -> 3
            "SessionTokenSize" -> 5
            else -> throw EpochSealStsException(EpochSealStsFailure.PROTOCOL_REJECTED)
        }

        private class Node(val name: String, maximum: Int) {
            val children = HashSet<String>()
            val bytes = ByteArray(maximum)
            var count = 0
        }
    }

    private val CREDENTIAL_FIELDS = setOf("AccessKeyId", "SecretAccessKey", "SessionToken")
    private val OPTIONAL_SIZE_FIELDS = setOf("PackedPolicySize", "SessionTokenUtilization", "SessionTokenSize")
}

/** Credential evidence is a digest of the actual raw scalar bytes, never request labels or a second credentials copy. */
internal class EpochSealStsWireReport(private val values: Map<String, String>, private val digests: Map<String, ByteArray>) {
    fun requireValue(name: String, value: String?) {
        requireEpochSealSts(value != null && values[name] == value)
    }

    fun requireCredential(name: String, value: String?) {
        requireEpochSealSts(value != null && value.length <= 16_384 && value.all { it in '!'..'~' })
        val bytes = checkNotNull(value).toByteArray(Charsets.US_ASCII)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        try {
            val expected = digests[name]
            requireEpochSealSts(expected != null && MessageDigest.isEqual(expected, digest))
        } finally {
            bytes.fill(0)
            digest.fill(0)
        }
    }

    fun expiration(): Instant {
        val value = values["Expiration"]
        requireEpochSealSts(value != null && value.matches(Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}(?:\\.[0-9]{1,9})?Z")))
        return Instant.parse(checkNotNull(value))
    }

    fun requireOptionalSize(name: String, observed: Int?, maximum: Int, exact: Int? = null) {
        val value = values[name]
        if (value == null) {
            requireEpochSealSts(observed == null)
        } else {
            val number = value.toIntOrNull()
            requireEpochSealSts(number != null && number in 0..maximum && number.toString() == value && number == observed)
            requireEpochSealSts(exact == null || number == exact)
        }
    }

    fun clear() { digests.values.forEach { it.fill(0) } }
    override fun toString(): String = "EpochSealStsWireReport(bounded,redacted,no-authority)"
}
