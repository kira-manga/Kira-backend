package me.manga.kira.backend.common.infrastructure.persistence

import org.xml.sax.InputSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.StringReader
import java.io.StringWriter
import java.io.Writer
import java.sql.SQLException
import java.sql.SQLXML
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.parsers.SAXParserFactory
import javax.xml.stream.XMLInputFactory
import javax.xml.stream.XMLOutputFactory
import javax.xml.transform.Result
import javax.xml.transform.Source
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.sax.SAXTransformerFactory
import javax.xml.transform.stax.StAXResult
import javax.xml.transform.stax.StAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

/** MODEL SQLXML with real JDK live Result graphs; mirrors PgSQLXML data ownership, not DB proof. */
internal class PhysicalJdbcXmlFixture : SQLXML {
    private var freed = false
    private var value = "<initial/>"
    var writer: StringWriter? = null
        private set
    private var binary: ByteArrayOutputStream? = null
    private var dom: DOMResult? = null

    override fun free() {
        freed = true
    }
    override fun getString(): String {
        checkOpen()
        writer?.let { value = it.toString() }
        binary?.let { value = it.toString(Charsets.UTF_8) }
        dom?.let { result ->
            val out = StringWriter()
            TransformerFactory.newInstance().newTransformer().transform(DOMSource(result.node), StreamResult(out))
            value = out.toString()
        }
        writer = null
        binary = null
        dom = null
        return value
    }
    override fun setString(value: String?) {
        checkOpen()
        this.value = value ?: ""
    }
    override fun getBinaryStream(): InputStream = ByteArrayInputStream(getString().toByteArray())
    override fun getCharacterStream(): Reader = StringReader(getString())
    override fun setBinaryStream(): OutputStream {
        checkOpen()
        return ByteArrayOutputStream().also { binary = it }
    }
    override fun setCharacterStream(): Writer {
        checkOpen()
        return StringWriter().also { writer = it }
    }

    override fun <T : Source?> getSource(sourceClass: Class<T>?): T {
        val text = getString()
        val result: Source = when (sourceClass) {
            null, DOMSource::class.java -> DOMSource(DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(InputSource(StringReader(text))))
            StreamSource::class.java -> StreamSource(StringReader(text))
            SAXSource::class.java -> SAXSource(SAXParserFactory.newInstance().newSAXParser().xmlReader, InputSource(StringReader(text)))
            StAXSource::class.java -> StAXSource(XMLInputFactory.newInstance().createXMLStreamReader(StringReader(text)))
            else -> throw SQLException("Unsupported model XML source.")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    override fun <T : Result?> setResult(resultClass: Class<T>?): T {
        checkOpen()
        val result: Result = when (resultClass) {
            null, DOMResult::class.java -> DOMResult().also { dom = it }

            StreamResult::class.java -> StreamResult(setCharacterStream())

            SAXResult::class.java -> {
                val handler = (TransformerFactory.newInstance() as SAXTransformerFactory).newTransformerHandler()
                handler.setResult(StreamResult(setCharacterStream()))
                SAXResult(handler)
            }

            StAXResult::class.java -> StAXResult(XMLOutputFactory.newInstance().createXMLStreamWriter(setCharacterStream()))

            else -> throw SQLException("Unsupported model XML result.")
        }
        @Suppress("UNCHECKED_CAST")
        return result as T
    }

    private fun checkOpen() {
        if (freed) throw SQLException("Model XML freed.")
    }
    override fun toString(): String = error("Native XML diagnostics must not be called.")
}
