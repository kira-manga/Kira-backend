package me.manga.kira.backend.common.infrastructure.persistence

import java.io.InputStream
import java.io.OutputStream
import java.io.Reader
import java.io.Writer
import java.nio.CharBuffer

/** These adapters hold only a closed, guarded dispatcher, never a stream/reader/writer delegate. */
internal interface PhysicalJdbcValueFacade {
    val jdbcGuard: PhysicalJdbcNode
}

internal class PhysicalJdbcInputStream(override val jdbcGuard: PhysicalJdbcNode) :
    InputStream(),
    PhysicalJdbcValueFacade {
    override fun read(): Int = call("read") as Int
    override fun read(b: ByteArray): Int = call("read", arrayOf(ByteArray::class.java), arrayOf(b)) as Int
    override fun read(b: ByteArray, off: Int, len: Int): Int = call("read", arrayOf(ByteArray::class.java, INT, INT), arrayOf(b, off, len)) as Int

    override fun readAllBytes(): ByteArray = call("readAllBytes") as ByteArray
    override fun readNBytes(len: Int): ByteArray = call("readNBytes", arrayOf(INT), arrayOf(len)) as ByteArray
    override fun readNBytes(b: ByteArray, off: Int, len: Int): Int = call("readNBytes", arrayOf(ByteArray::class.java, INT, INT), arrayOf(b, off, len)) as Int

    override fun skip(n: Long): Long = call("skip", arrayOf(LONG), arrayOf(n)) as Long
    override fun skipNBytes(n: Long) {
        call("skipNBytes", arrayOf(LONG), arrayOf(n))
    }
    override fun available(): Int = call("available") as Int
    override fun close() {
        call("close")
    }
    override fun mark(readlimit: Int) {
        call("mark", arrayOf(INT), arrayOf(readlimit))
    }
    override fun reset() {
        call("reset")
    }
    override fun markSupported(): Boolean = call("markSupported") as Boolean
    override fun transferTo(out: OutputStream): Long = call("transferTo", arrayOf(OutputStream::class.java), arrayOf(out)) as Long
    override fun toString(): String = "PhysicalJdbcInputStream(redacted)"

    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(InputStream::class.java, name, types, args)
}

internal class PhysicalJdbcOutputStream(override val jdbcGuard: PhysicalJdbcNode) :
    OutputStream(),
    PhysicalJdbcValueFacade {
    override fun write(b: Int) {
        call("write", arrayOf(INT), arrayOf(b))
    }
    override fun write(b: ByteArray) {
        call("write", arrayOf(ByteArray::class.java), arrayOf(b))
    }
    override fun write(b: ByteArray, off: Int, len: Int) {
        call("write", arrayOf(ByteArray::class.java, INT, INT), arrayOf(b, off, len))
    }
    override fun flush() {
        call("flush")
    }
    override fun close() {
        call("close")
    }
    override fun toString(): String = "PhysicalJdbcOutputStream(redacted)"

    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(OutputStream::class.java, name, types, args)
}

internal class PhysicalJdbcReader(override val jdbcGuard: PhysicalJdbcNode) :
    Reader(),
    PhysicalJdbcValueFacade {
    override fun read(): Int = call("read") as Int
    override fun read(cbuf: CharArray): Int = call("read", arrayOf(CharArray::class.java), arrayOf(cbuf)) as Int
    override fun read(cbuf: CharArray, off: Int, len: Int): Int = call("read", arrayOf(CharArray::class.java, INT, INT), arrayOf(cbuf, off, len)) as Int

    override fun read(target: CharBuffer): Int = call("read", arrayOf(CharBuffer::class.java), arrayOf(target)) as Int
    override fun skip(n: Long): Long = call("skip", arrayOf(LONG), arrayOf(n)) as Long
    override fun ready(): Boolean = call("ready") as Boolean
    override fun markSupported(): Boolean = call("markSupported") as Boolean
    override fun mark(readAheadLimit: Int) {
        call("mark", arrayOf(INT), arrayOf(readAheadLimit))
    }
    override fun reset() {
        call("reset")
    }
    override fun close() {
        call("close")
    }
    override fun transferTo(out: Writer): Long = call("transferTo", arrayOf(Writer::class.java), arrayOf(out)) as Long
    override fun toString(): String = "PhysicalJdbcReader(redacted)"

    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(Reader::class.java, name, types, args)
}

internal class PhysicalJdbcWriter(override val jdbcGuard: PhysicalJdbcNode) :
    Writer(),
    PhysicalJdbcValueFacade {
    override fun write(c: Int) {
        call("write", arrayOf(INT), arrayOf(c))
    }
    override fun write(cbuf: CharArray) {
        call("write", arrayOf(CharArray::class.java), arrayOf(cbuf))
    }
    override fun write(cbuf: CharArray, off: Int, len: Int) {
        call("write", arrayOf(CharArray::class.java, INT, INT), arrayOf(cbuf, off, len))
    }
    override fun write(str: String) {
        call("write", arrayOf(String::class.java), arrayOf(str))
    }
    override fun write(str: String, off: Int, len: Int) {
        call("write", arrayOf(String::class.java, INT, INT), arrayOf(str, off, len))
    }
    override fun append(csq: CharSequence?): Writer = call("append", arrayOf(CharSequence::class.java), arrayOf(csq)) as Writer
    override fun append(csq: CharSequence?, start: Int, end: Int): Writer =
        call("append", arrayOf(CharSequence::class.java, INT, INT), arrayOf(csq, start, end)) as Writer

    override fun append(c: Char): Writer = call("append", arrayOf(CHAR), arrayOf(c)) as Writer
    override fun flush() {
        call("flush")
    }
    override fun close() {
        call("close")
    }
    override fun toString(): String = "PhysicalJdbcWriter(redacted)"

    private fun call(name: String, types: Array<Class<*>> = emptyArray(), args: Array<Any?> = emptyArray()): Any? =
        jdbcGuard.invokeApi(Writer::class.java, name, types, args)
}

private val INT: Class<*> = Integer.TYPE
private val LONG: Class<*> = java.lang.Long.TYPE
private val CHAR: Class<*> = Character.TYPE
