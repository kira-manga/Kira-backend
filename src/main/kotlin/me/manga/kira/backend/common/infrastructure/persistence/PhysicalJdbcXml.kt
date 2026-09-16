package me.manga.kira.backend.common.infrastructure.persistence

import org.w3c.dom.Node
import org.xml.sax.ContentHandler
import org.xml.sax.InputSource
import org.xml.sax.XMLReader
import org.xml.sax.ext.LexicalHandler
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import java.util.Properties
import javax.xml.stream.XMLEventReader
import javax.xml.stream.XMLEventWriter
import javax.xml.stream.XMLStreamReader
import javax.xml.stream.XMLStreamWriter
import javax.xml.transform.ErrorListener
import javax.xml.transform.Result
import javax.xml.transform.Source
import javax.xml.transform.Transformer
import javax.xml.transform.URIResolver
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.stax.StAXResult
import javax.xml.transform.stax.StAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

/**
 * Preserve requested JDK concrete XML types without exposing their native nested graphs. DOM,
 * SAX/TransformerHandler and StAX interfaces themselves use the same closed interface dispatcher.
 * Null dispatch is only the unexposed superclass-constructor interval: some JDK constructors call
 * overridable setters. No delegate is installed in the superclass fields during that interval.
 */
internal fun physicalJdbcXmlFacade(node: PhysicalJdbcNode, kind: PhysicalJdbcKind): Any = when (kind) {
    PhysicalJdbcKind.DOM_RESULT -> GuardedDomResult(node)

    PhysicalJdbcKind.DOM_SOURCE -> GuardedDomSource(node)

    PhysicalJdbcKind.SAX_RESULT -> GuardedSaxResult(node)

    PhysicalJdbcKind.SAX_SOURCE -> GuardedSaxSource(node)

    PhysicalJdbcKind.STREAM_RESULT -> GuardedStreamResult(node)

    PhysicalJdbcKind.STREAM_SOURCE -> GuardedStreamSource(node)

    PhysicalJdbcKind.STAX_RESULT -> {
        val writer = node.invokeApi(StAXResult::class.java, "getXMLStreamWriter", emptyArray(), emptyArray()) as XMLStreamWriter?
        if (writer != null) {
            GuardedStaxResult(node, writer)
        } else {
            val events = node.invokeApi(StAXResult::class.java, "getXMLEventWriter", emptyArray(), emptyArray()) as XMLEventWriter
            GuardedStaxResult(node, events)
        }
    }

    PhysicalJdbcKind.STAX_SOURCE -> {
        val reader = node.invokeApi(StAXSource::class.java, "getXMLStreamReader", emptyArray(), emptyArray()) as XMLStreamReader?
        if (reader != null) {
            GuardedStaxSource(node, reader)
        } else {
            val events = node.invokeApi(StAXSource::class.java, "getXMLEventReader", emptyArray(), emptyArray()) as XMLEventReader
            GuardedStaxSource(node, events)
        }
    }

    PhysicalJdbcKind.INPUT_SOURCE -> GuardedInputSource(node)

    PhysicalJdbcKind.TRANSFORMER -> GuardedTransformer(node)

    else -> error("Unsupported internal XML facade kind.")
}

private class GuardedDomResult(node: PhysicalJdbcNode) :
    DOMResult(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getNode(): Node? = if (dispatch == null) super.getNode() else call("getNode") as Node?
    override fun setNode(node: Node?) {
        if (dispatch == null) super.setNode(node) else call("setNode", arrayOf(Node::class.java), arrayOf(node))
    }
    override fun getNextSibling(): Node? = if (dispatch == null) super.getNextSibling() else call("getNextSibling") as Node?
    override fun setNextSibling(node: Node?) {
        if (dispatch == null) super.setNextSibling(node) else call("setNextSibling", arrayOf(Node::class.java), arrayOf(node))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun toString(): String = "PhysicalJdbcDOMResult(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(DOMResult::class.java, name, types, args)
}

private class GuardedDomSource(node: PhysicalJdbcNode) :
    DOMSource(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getNode(): Node? = if (dispatch == null) super.getNode() else call("getNode") as Node?
    override fun setNode(node: Node?) {
        if (dispatch == null) super.setNode(node) else call("setNode", arrayOf(Node::class.java), arrayOf(node))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun isEmpty(): Boolean = if (dispatch == null) super.isEmpty() else call("isEmpty") as Boolean
    override fun toString(): String = "PhysicalJdbcDOMSource(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(DOMSource::class.java, name, types, args)
}

private class GuardedSaxResult(node: PhysicalJdbcNode) :
    SAXResult(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getHandler(): ContentHandler? = if (dispatch == null) super.getHandler() else call("getHandler") as ContentHandler?
    override fun setHandler(handler: ContentHandler?) {
        if (dispatch == null) super.setHandler(handler) else call("setHandler", arrayOf(ContentHandler::class.java), arrayOf(handler))
    }
    override fun getLexicalHandler(): LexicalHandler? = if (dispatch == null) super.getLexicalHandler() else call("getLexicalHandler") as LexicalHandler?
    override fun setLexicalHandler(handler: LexicalHandler?) {
        if (dispatch == null) super.setLexicalHandler(handler) else call("setLexicalHandler", arrayOf(LexicalHandler::class.java), arrayOf(handler))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun toString(): String = "PhysicalJdbcSAXResult(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(SAXResult::class.java, name, types, args)
}

private class GuardedSaxSource(node: PhysicalJdbcNode) :
    SAXSource(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getXMLReader(): XMLReader? = if (dispatch == null) super.getXMLReader() else call("getXMLReader") as XMLReader?
    override fun setXMLReader(reader: XMLReader?) {
        if (dispatch == null) super.setXMLReader(reader) else call("setXMLReader", arrayOf(XMLReader::class.java), arrayOf(reader))
    }
    override fun getInputSource(): InputSource? = if (dispatch == null) super.getInputSource() else call("getInputSource") as InputSource?
    override fun setInputSource(source: InputSource?) {
        if (dispatch == null) super.setInputSource(source) else call("setInputSource", arrayOf(InputSource::class.java), arrayOf(source))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun isEmpty(): Boolean = if (dispatch == null) super.isEmpty() else call("isEmpty") as Boolean
    override fun toString(): String = "PhysicalJdbcSAXSource(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(SAXSource::class.java, name, types, args)
}

private class GuardedStreamResult(node: PhysicalJdbcNode) :
    StreamResult(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getOutputStream(): OutputStream? = if (dispatch == null) super.getOutputStream() else call("getOutputStream") as OutputStream?
    override fun setOutputStream(stream: OutputStream?) {
        if (dispatch == null) super.setOutputStream(stream) else call("setOutputStream", arrayOf(OutputStream::class.java), arrayOf(stream))
    }
    override fun getWriter(): Writer? = if (dispatch == null) super.getWriter() else call("getWriter") as Writer?
    override fun setWriter(writer: Writer?) {
        if (dispatch == null) super.setWriter(writer) else call("setWriter", arrayOf(Writer::class.java), arrayOf(writer))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun setSystemId(file: File?) {
        if (dispatch == null) super.setSystemId(file) else call("setSystemId", arrayOf(File::class.java), arrayOf(file))
    }
    override fun toString(): String = "PhysicalJdbcStreamResult(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(StreamResult::class.java, name, types, args)
}

private class GuardedStreamSource(node: PhysicalJdbcNode) :
    StreamSource(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getInputStream(): InputStream? = if (dispatch == null) super.getInputStream() else call("getInputStream") as InputStream?
    override fun setInputStream(stream: InputStream?) {
        if (dispatch == null) super.setInputStream(stream) else call("setInputStream", arrayOf(InputStream::class.java), arrayOf(stream))
    }
    override fun getReader(): Reader? = if (dispatch == null) super.getReader() else call("getReader") as Reader?
    override fun setReader(reader: Reader?) {
        if (dispatch == null) super.setReader(reader) else call("setReader", arrayOf(Reader::class.java), arrayOf(reader))
    }
    override fun getPublicId(): String? = if (dispatch == null) super.getPublicId() else call("getPublicId") as String?
    override fun setPublicId(publicId: String?) {
        if (dispatch == null) super.setPublicId(publicId) else call("setPublicId", arrayOf(String::class.java), arrayOf(publicId))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun setSystemId(file: File?) {
        if (dispatch == null) super.setSystemId(file) else call("setSystemId", arrayOf(File::class.java), arrayOf(file))
    }
    override fun isEmpty(): Boolean = if (dispatch == null) super.isEmpty() else call("isEmpty") as Boolean
    override fun toString(): String = "PhysicalJdbcStreamSource(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(StreamSource::class.java, name, types, args)
}

private class GuardedStaxResult :
    StAXResult,
    PhysicalJdbcValueFacade {
    override val jdbcGuard: PhysicalJdbcNode
    constructor(node: PhysicalJdbcNode, writer: XMLStreamWriter) : super(writer) {
        jdbcGuard = node
    }
    constructor(node: PhysicalJdbcNode, writer: XMLEventWriter) : super(writer) {
        jdbcGuard = node
    }
    override fun getXMLStreamWriter(): XMLStreamWriter? = call("getXMLStreamWriter") as XMLStreamWriter?
    override fun getXMLEventWriter(): XMLEventWriter? = call("getXMLEventWriter") as XMLEventWriter?
    override fun getSystemId(): String? = call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun toString(): String = "PhysicalJdbcStAXResult(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(StAXResult::class.java, name, types, args)
}

private class GuardedStaxSource :
    StAXSource,
    PhysicalJdbcValueFacade {
    override val jdbcGuard: PhysicalJdbcNode
    constructor(node: PhysicalJdbcNode, reader: XMLStreamReader) : super(reader) {
        jdbcGuard = node
    }
    constructor(node: PhysicalJdbcNode, reader: XMLEventReader) : super(reader) {
        jdbcGuard = node
    }
    override fun getXMLStreamReader(): XMLStreamReader? = call("getXMLStreamReader") as XMLStreamReader?
    override fun getXMLEventReader(): XMLEventReader? = call("getXMLEventReader") as XMLEventReader?
    override fun getSystemId(): String? = call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun isEmpty(): Boolean = call("isEmpty") as Boolean
    override fun toString(): String = "PhysicalJdbcStAXSource(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(StAXSource::class.java, name, types, args)
}

private class GuardedInputSource(node: PhysicalJdbcNode) :
    InputSource(),
    PhysicalJdbcValueFacade {
    private var dispatch: PhysicalJdbcNode? = null
    init {
        dispatch = node
    }
    override val jdbcGuard: PhysicalJdbcNode get() = requireNotNull(dispatch)
    override fun getPublicId(): String? = if (dispatch == null) super.getPublicId() else call("getPublicId") as String?
    override fun setPublicId(publicId: String?) {
        if (dispatch == null) super.setPublicId(publicId) else call("setPublicId", arrayOf(String::class.java), arrayOf(publicId))
    }
    override fun getSystemId(): String? = if (dispatch == null) super.getSystemId() else call("getSystemId") as String?
    override fun setSystemId(systemId: String?) {
        if (dispatch == null) super.setSystemId(systemId) else call("setSystemId", arrayOf(String::class.java), arrayOf(systemId))
    }
    override fun getEncoding(): String? = if (dispatch == null) super.getEncoding() else call("getEncoding") as String?
    override fun setEncoding(encoding: String?) {
        if (dispatch == null) super.setEncoding(encoding) else call("setEncoding", arrayOf(String::class.java), arrayOf(encoding))
    }
    override fun getByteStream(): InputStream? = if (dispatch == null) super.getByteStream() else call("getByteStream") as InputStream?
    override fun setByteStream(stream: InputStream?) {
        if (dispatch == null) super.setByteStream(stream) else call("setByteStream", arrayOf(InputStream::class.java), arrayOf(stream))
    }
    override fun getCharacterStream(): Reader? = if (dispatch == null) super.getCharacterStream() else call("getCharacterStream") as Reader?
    override fun setCharacterStream(reader: Reader?) {
        if (dispatch == null) super.setCharacterStream(reader) else call("setCharacterStream", arrayOf(Reader::class.java), arrayOf(reader))
    }
    override fun isEmpty(): Boolean = if (dispatch == null) super.isEmpty() else call("isEmpty") as Boolean
    override fun toString(): String = "PhysicalJdbcInputSource(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(InputSource::class.java, name, types, args)
}

private class GuardedTransformer(override val jdbcGuard: PhysicalJdbcNode) :
    Transformer(),
    PhysicalJdbcValueFacade {
    override fun reset() {
        call("reset")
    }
    override fun transform(xmlSource: Source?, outputTarget: Result?) {
        call("transform", arrayOf(Source::class.java, Result::class.java), arrayOf(xmlSource, outputTarget))
    }
    override fun setParameter(name: String?, value: Any?) {
        call("setParameter", arrayOf(String::class.java, Any::class.java), arrayOf(name, value))
    }
    override fun getParameter(name: String?): Any? = call("getParameter", arrayOf(String::class.java), arrayOf(name))
    override fun clearParameters() {
        call("clearParameters")
    }
    override fun setURIResolver(resolver: URIResolver?) {
        call("setURIResolver", arrayOf(URIResolver::class.java), arrayOf(resolver))
    }
    override fun getURIResolver(): URIResolver? = call("getURIResolver") as URIResolver?
    override fun setOutputProperties(oformat: Properties?) {
        call("setOutputProperties", arrayOf(Properties::class.java), arrayOf(oformat))
    }
    override fun getOutputProperties(): Properties = call("getOutputProperties") as Properties
    override fun setOutputProperty(name: String?, value: String?) {
        call("setOutputProperty", arrayOf(String::class.java, String::class.java), arrayOf(name, value))
    }
    override fun getOutputProperty(name: String?): String? = call("getOutputProperty", arrayOf(String::class.java), arrayOf(name)) as String?
    override fun setErrorListener(listener: ErrorListener?) {
        call("setErrorListener", arrayOf(ErrorListener::class.java), arrayOf(listener))
    }
    override fun getErrorListener(): ErrorListener = call("getErrorListener") as ErrorListener
    override fun toString(): String = "PhysicalJdbcTransformer(redacted)"
    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(Transformer::class.java, name, types, args)
}
