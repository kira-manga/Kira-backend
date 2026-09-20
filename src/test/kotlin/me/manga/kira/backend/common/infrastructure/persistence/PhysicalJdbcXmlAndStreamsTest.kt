package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.w3c.dom.DOMException
import org.w3c.dom.Node
import org.w3c.dom.UserDataHandler
import org.xml.sax.SAXException
import org.xml.sax.helpers.AttributesImpl
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringWriter
import java.nio.CharBuffer
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.stream.XMLStreamException
import javax.xml.transform.dom.DOMResult
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.sax.SAXResult
import javax.xml.transform.sax.SAXSource
import javax.xml.transform.sax.TransformerHandler
import javax.xml.transform.stax.StAXResult
import javax.xml.transform.stax.StAXSource
import javax.xml.transform.stream.StreamResult
import javax.xml.transform.stream.StreamSource

class PhysicalJdbcXmlAndStreamsTest {
    @Test
    fun `all input stream default and overload routes stay guarded through close`() {
        val f = PhysicalJdbcGraphFixture()
        val blob = f.connection.createBlob()
        val input = blob.binaryStream
        assertEquals(1, input.read())
        assertEquals(2, input.readNBytes(2).size)
        input.mark(8)
        assertTrue(input.markSupported())
        assertEquals(2, input.read(ByteArray(2)))
        input.reset()
        assertEquals(1, input.read(ByteArray(3), 1, 1))
        assertEquals(1, input.readNBytes(ByteArray(3), 1, 1))
        input.skipNBytes(1)
        assertEquals(1L, input.skip(1))
        assertEquals(1, input.available())
        assertEquals(1L, input.transferTo(ByteArrayOutputStream()))
        assertEquals(0, input.readAllBytes().size)
        input.close()
        val rawReads = f.probes.last().reads.get()
        assertThrows<IOException> { input.readAllBytes() }
        assertThrows<IOException> { input.readNBytes(1) }
        assertThrows<IOException> { input.skipNBytes(1) }
        assertEquals(rawReads, f.probes.last().reads.get())
        assertEquals("PhysicalJdbcInputStream(redacted)", input.toString())
        assertEquals(1, f.probes.last().closes.get())
    }

    @Test
    fun `new stale transfer target is rejected before source consumption`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        val target = xml.setBinaryStream()
        target.close()
        val input = f.connection.createBlob().binaryStream
        assertThrows<IOException> { input.transferTo(target) }
        assertEquals(0, f.probes.last().reads.get())
    }

    @Test
    fun `writer output overloads and Reader defaults preserve ordinary operation and facade identity`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        val writer = xml.setCharacterStream()
        writer.write('a'.code)
        writer.write(charArrayOf('b', 'c'))
        writer.write(charArrayOf('d', 'e'), 1, 1)
        writer.write("fg")
        writer.write("hij", 1, 1)
        assertSame(writer, writer.append('k'))
        assertSame(writer, writer.append("lm"))
        assertSame(writer, writer.append("nop", 1, 2))
        writer.flush()
        writer.close()
        assertEquals("abcefgiklmo", xml.string)
        val reader = xml.characterStream
        assertTrue(reader.markSupported())
        reader.mark(32)
        assertEquals('a'.code, reader.read())
        reader.reset()
        assertEquals(2, reader.read(CharArray(2)))
        assertEquals(1, reader.read(CharArray(2), 0, 1))
        assertEquals(1L, reader.skip(1))
        assertTrue(reader.ready())
        assertEquals(2, reader.read(CharBuffer.allocate(2)))
        assertTrue(reader.transferTo(StringWriter()) > 0)
        reader.close()
        assertThrows<IOException> { reader.read() }
    }

    @Test
    fun `live StreamResult writer is revoked by SQLXML free but detached Source reads remain owner gated`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        val result = xml.setResult(StreamResult::class.java)
        val writer = result.writer
        writer.write("<x>text</x>")
        writer.flush()
        val retained = requireNotNull(f.xml.writer)
        val source = xml.getSource(StreamSource::class.java)
        val detachedReader = source.reader
        xml.free()
        assertThrows<IOException> { writer.write("late") }
        assertEquals("<x>text</x>", retained.toString())
        assertEquals('<'.code, detachedReader.read()) // Pinned getSource's immutable data copy is detached.
        f.seal()
        assertThrows<IOException> { detachedReader.read() }
    }

    @Test
    fun `DOM result guards nodes owner document child list and clone callbacks with private node inputs`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        val result = xml.setResult(DOMResult::class.java)
        result.node = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument()
        val document = result.node as org.w3c.dom.Document
        val element = document.createElement("root")
        document.appendChild(element) // Xerces needs its original node, not a public proxy.
        element.appendChild(document.createTextNode("value"))
        assertSame(document, element.ownerDocument)
        assertSame(element, document.childNodes.item(0))
        val callbackNodes = mutableListOf<Node>()
        element.setUserData(
            "k",
            "value",
            UserDataHandler { _, _, _, source, destination ->
                if (source != null) callbackNodes.add(source)
                if (destination != null) callbackNodes.add(destination)
            },
        )
        val clone = element.cloneNode(true)
        assertTrue(callbackNodes.isNotEmpty())
        callbackNodes.forEach { assertNotNull(PhysicalJdbcDescendants.knownGuard(it)) }
        assertTrue(xml.string.contains("<root>value</root>"))
        xml.free()
        assertThrows<DOMException> { element.nodeName }
        assertThrows<DOMException> { clone.nodeName }
        callbackNodes.forEach { node -> assertThrows<DOMException> { node.nodeName } }
    }

    @Test
    fun `SAX live handler and Transformer graph cannot escape the XML owner`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        val result = xml.setResult(SAXResult::class.java)
        val handler = result.handler
        handler.startDocument()
        handler.startElement("", "root", "root", AttributesImpl())
        handler.characters("value".toCharArray(), 0, 5)
        handler.endElement("", "root", "root")
        handler.endDocument()
        val transformer = (handler as TransformerHandler).transformer
        assertTrue(transformer.toString().contains("redacted"))
        assertNotNull(transformer.outputProperties)
        assertTrue(xml.string.contains("<root>value</root>"))
        xml.free()
        assertThrows<SAXException> { handler.startDocument() }
        assertThrows<IllegalStateException> { transformer.getParameter("name") }
    }

    @Test
    fun `StAX writer and returned namespace context remain in the live XML graph`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        val writer = xml.setResult(StAXResult::class.java).xmlStreamWriter
        writer.writeStartDocument()
        writer.writeStartElement("root")
        writer.writeCharacters("value")
        writer.writeEndElement()
        writer.writeEndDocument()
        writer.flush()
        val namespaces = writer.namespaceContext
        assertTrue(xml.string.contains("<root>value</root>"))
        xml.free()
        assertThrows<XMLStreamException> { writer.writeCharacters("late") }
        assertThrows<IllegalStateException> { namespaces.getNamespaceURI("p") }
        writer.close() // Authenticated cleanup survives parent free; it is not business revival.
        assertFalse(f.prepared.epoch.foregroundActive())
    }

    @Test
    fun `all ordinary source families retain their requested concrete JDK types`() {
        val f = PhysicalJdbcGraphFixture()
        val xml = f.connection.createSQLXML()
        assertEquals("initial", (xml.getSource(DOMSource::class.java).node as org.w3c.dom.Document).documentElement.nodeName)
        assertEquals('<'.code, xml.getSource(StreamSource::class.java).reader.read())
        val sax = xml.getSource(SAXSource::class.java)
        assertNotNull(sax.xmlReader)
        assertFalse(sax.inputSource.isEmpty)
        val stax = xml.getSource(StAXSource::class.java)
        assertNotNull(stax.xmlStreamReader)
        assertFalse(stax.isEmpty)
    }
}
