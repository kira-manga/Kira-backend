package me.manga.kira.backend.database

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.images.builder.Transferable
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

internal const val MAX_SYNTHETIC_DUMP_BYTES = 2 * 1024 * 1024

/** Do not use exec stdout for dumps: Testcontainers' log consumer strips ANSI sequences. */
internal fun restoreDatabaseDump(source: PostgreSQLContainer<*>, target: PostgreSQLContainer<*>) {
    OwnedDumpFile(source).use { from ->
        OwnedDumpFile(target).use { to ->
            // The shell receives only static code and positional arguments; files remain synthetic.
            val dump = source.execInContainer(
                "sh", "-c", "umask 077; exec pg_dump \"\$@\"", "kira-dump",
                "-U", source.username, "-d", source.databaseName, "--no-owner", "--no-acl", "-f", from.path,
            )
            assertEquals(0, dump.exitCode, dump.stderr)
            val sourceSize = from.size()
            assertTrue(sourceSize in 1..MAX_SYNTHETIC_DUMP_BYTES, "Synthetic dump exceeds its bounded fixture budget")
            val sourceHash = from.sha256()
            val bytes = source.copyFileFromContainer(from.path) { readBoundedDump(it) }
            assertEquals(sourceSize, bytes.size)
            assertEquals(sourceHash, sha256(bytes), "Source-file to host transport must be byte-exact")
            assertTrue(
                bytes.toString(Charsets.UTF_8).contains("set_config('search_path', '', false)"),
                "Use the normal secure restore search path",
            )
            target.copyFileToContainer(Transferable.of(bytes, 0b110_000_000), to.path)
            assertEquals(sourceSize, to.size())
            assertEquals(sourceHash, to.sha256(), "Host to target-file transport must be byte-exact")
            val restore = target.execInContainer(
                "psql", "-v", "ON_ERROR_STOP=1", "-U", target.username, "-d", target.databaseName, "-f", to.path,
            )
            assertEquals(0, restore.exitCode, restore.stderr)
        }
    }
}

internal fun readBoundedDump(input: InputStream): ByteArray = input.readNBytes(MAX_SYNTHETIC_DUMP_BYTES + 1).also {
    require(it.size <= MAX_SYNTHETIC_DUMP_BYTES) { "Synthetic dump exceeds its bounded fixture budget" }
}

private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

private class OwnedDumpFile(private val container: PostgreSQLContainer<*>) : AutoCloseable {
    val path = "/tmp/kira-fixture-${UUID.randomUUID()}.sql"

    fun size(): Int {
        val result = container.execInContainer("wc", "-c", path)
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout.trim().substringBefore(' ').toInt()
    }

    fun sha256(): String {
        val result = container.execInContainer("sha256sum", path)
        assertEquals(0, result.exitCode, result.stderr)
        return result.stdout.substringBefore(' ').also { assertTrue(it.matches(Regex("[a-f0-9]{64}"))) }
    }

    override fun close() {
        val cleanup = container.execInContainer("rm", "-f", path)
        assertEquals(0, cleanup.exitCode, cleanup.stderr)
        val absent = container.execInContainer("test", "!", "-e", path)
        assertEquals(0, absent.exitCode, "Owned synthetic dump file must be absent after cleanup")
    }
}
