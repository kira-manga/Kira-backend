package me.manga.kira.backend.common.infrastructure.persistence

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.nio.ByteBuffer
import java.util.Locale

/** Bounded plaintext framing only. Authentication payloads/keys/SQL text are forwarded, not retained or logged. */
internal object PgLifecycleDatabaseWire {
    const val STARTUP = 196608
    const val CANCEL = 80877102

    class Message(val type: Int, val bytes: ByteArray) {
        fun integer(): Int {
            check(bytes.size >= 4)
            return ByteBuffer.wrap(bytes).int
        }
    }

    fun startup(input: DataInputStream): ByteArray {
        val size = input.readInt()
        check(size in 8..65_536)
        val bytes = ByteArray(size)
        ByteBuffer.wrap(bytes).putInt(size)
        input.readFully(bytes, 4, size - 4)
        return bytes
    }

    fun protocol(startup: ByteArray): Int = ByteBuffer.wrap(startup, 4, 4).int

    fun cancelPid(startup: ByteArray): Int {
        check(startup.size == 16 && protocol(startup) == CANCEL)
        return ByteBuffer.wrap(startup, 8, 4).int
    }

    fun requireStartup(startup: ByteArray, application: String) {
        check(protocol(startup) == STARTUP)
        val values = strings(startup, 8)
        check(values.last() == "" && values.size % 2 == 1 && values.size <= 41)
        val properties = values.dropLast(1).chunked(2).associate { it[0] to it[1] }
        check(properties.size * 2 == values.size - 1)
        check(properties["application_name"] == application) { "ApplicationName was not sent in the startup packet." }
        check(properties["user"] == PgLifecycleDatabaseSettings.CANDIDATE && properties["database"] == PgLifecycleDatabaseSettings.DATABASE)
    }

    fun read(input: DataInputStream): Message? {
        val type = input.read()
        if (type < 0) return null
        val size = input.readInt()
        check(size in 4..65_536)
        val bytes = ByteArray(size - 4)
        input.readFully(bytes)
        return Message(type, bytes)
    }

    fun write(output: DataOutputStream, message: Message) {
        output.writeByte(message.type)
        output.writeInt(message.bytes.size + 4)
        output.write(message.bytes)
        output.flush()
    }

    fun readyFrame(message: Message): ByteArray {
        check(message.type == 'Z'.code && message.bytes.contentEquals(byteArrayOf('I'.code.toByte())))
        return ByteBuffer.allocate(6).put('Z'.code.toByte()).putInt(5).put(message.bytes[0]).array()
    }

    fun requirePrimaryRoleRow(message: Message) {
        check(message.type == 'D'.code && message.bytes.size == 9)
        val row = ByteBuffer.wrap(message.bytes)
        check(row.short.toInt() == 1 && row.int == 3)
        val value = ByteArray(3)
        row.get(value)
        check(value.contentEquals("off".toByteArray(Charsets.US_ASCII)) && !row.hasRemaining())
    }

    fun requireRoleCommand(message: Message) {
        check(message.type == 'C'.code && message.bytes.contentEquals("SHOW\u0000".toByteArray(Charsets.US_ASCII)))
    }

    fun sqlFingerprint(message: Message): PgLifecycleDatabaseSql? {
        val sql = when (message.type.toChar()) {
            'Q' -> strings(message.bytes, 0).single()

            'P' -> {
                val nameEnd = message.bytes.indexOf(0)
                check(nameEnd in 0..127)
                stringAt(message.bytes, nameEnd + 1).first
            }

            else -> return null
        }
        check(sql.length <= 8_192)
        val normalized = sql.trim().trimEnd(';').uppercase(Locale.ROOT)
        return when {
            normalized == "SHOW TRANSACTION_READ_ONLY" -> PgLifecycleDatabaseSql.ROLE
            normalized == "SET SESSION CHARACTERISTICS AS TRANSACTION READ ONLY" -> PgLifecycleDatabaseSql.READ_ONLY
            normalized.contains("PG_TYPE") && normalized.contains("TYPNAME") && normalized.contains("OID") -> PgLifecycleDatabaseSql.CATALOG
            else -> PgLifecycleDatabaseSql.UNEXPECTED
        }
    }

    fun sqlState(message: Message): String? {
        check(message.type == 'E'.code)
        var position = 0
        var state: String? = null
        var fields = 0
        while (position < message.bytes.size && message.bytes[position] != 0.toByte()) {
            check(++fields <= 32)
            val kind = message.bytes[position++].toInt().toChar()
            val value = stringAt(message.bytes, position)
            if (kind == 'C') {
                check(state == null && value.first.matches(Regex("[0-9A-Z]{5}")))
                state = value.first
            }
            position = value.second
        }
        check(position == message.bytes.size - 1 && message.bytes.last() == 0.toByte())
        return state
    }

    private fun strings(bytes: ByteArray, start: Int): List<String> {
        val result = mutableListOf<String>()
        var position = start
        while (position < bytes.size) {
            check(result.size < 64)
            val value = stringAt(bytes, position)
            result.add(value.first)
            position = value.second
        }
        return result
    }

    private fun stringAt(bytes: ByteArray, start: Int): Pair<String, Int> {
        var end = start
        while (end < bytes.size && bytes[end] != 0.toByte()) end++
        if (end == bytes.size) throw EOFException("Incomplete synthetic protocol field.")
        check(end - start <= 8_192)
        return String(bytes, start, end - start, Charsets.UTF_8) to end + 1
    }
}

internal enum class PgLifecycleDatabaseSql {
    ROLE,
    READ_ONLY,
    CATALOG,
    UNEXPECTED,
}
