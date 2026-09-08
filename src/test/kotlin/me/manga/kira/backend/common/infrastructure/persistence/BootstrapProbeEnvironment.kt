package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.file.Path
import java.util.concurrent.TimeUnit

/** Synthetic fixture inputs only; neither public UID metadata nor this map attests native isolation. */
internal object BootstrapProbeEnvironment {
    private const val NON_MAC_IDENTITY = "non-macos"
    private var cachedNumericIdentity: String? = null

    @Synchronized
    fun identity(root: Path): String {
        if (!isMac()) return NON_MAC_IDENTITY
        cachedNumericIdentity?.let { return it }
        val real = query(root, BootstrapUidQuery.REAL)
        val effective = query(root, BootstrapUidQuery.EFFECTIVE)
        // Both query owners have closed successfully before any identity is cached.
        return sameIdentity(real, effective).also { cachedNumericIdentity = it }
    }

    fun expected(root: Path, identity: String): Map<String, String> {
        check(root.isAbsolute) { "Bootstrap synthetic root must be absolute." }
        val home = root.resolve("home").toString()
        return if (isMac()) {
            requireCanonicalIdentity(identity)
            mapOf("HOME" to home, "CFFIXED_USER_HOME" to home, "__CF_USER_TEXT_ENCODING" to "$identity:0:0")
        } else {
            check(identity == NON_MAC_IDENTITY) { "Bootstrap platform identity is invalid." }
            mapOf("HOME" to home)
        }
    }

    fun matches(root: Path, identity: String, actual: Map<String, String>): Boolean {
        val expected = expected(root, identity)
        // Iterate actual literal keys; native environment maps can have unusual lookup/equality semantics.
        return actual.size == expected.size && actual.entries.all { (key, value) -> expected[key] == value }
    }

    fun parseRecord(record: ByteArray): String {
        check(record.size in 2..11 && record.last() == '\n'.code.toByte()) { "Bootstrap numeric identity record is invalid." }
        val digits = record.dropLast(1)
        check(digits.all { it.toInt() in '0'.code..'9'.code }) { "Bootstrap numeric identity record is invalid." }
        return record.copyOf(record.size - 1).toString(Charsets.US_ASCII).also(::requireCanonicalIdentity)
    }

    fun sameIdentity(real: String, effective: String): String {
        requireCanonicalIdentity(real)
        requireCanonicalIdentity(effective)
        check(real == effective) { "Bootstrap numeric identities differ." }
        return real
    }

    private fun requireCanonicalIdentity(identity: String) {
        check(
            identity.isNotEmpty() && identity.length <= 10 && identity.all { it in '0'..'9' } &&
                (identity.length == 1 || identity.first() != '0') && identity.toIntOrNull() != null,
        ) { "Bootstrap numeric identity is invalid." }
    }

    private fun isMac(): Boolean = System.getProperty("os.name") == "Mac OS X"

    private fun query(root: Path, kind: BootstrapUidQuery): String {
        check(root.isAbsolute) { "Bootstrap synthetic root must be absolute." }
        val home = root.resolve("home").toString()
        val builder = ProcessBuilder(listOf("/usr/bin/id") + kind.options)
            .directory(root.toFile())
            .redirectError(ProcessBuilder.Redirect.DISCARD)
        builder.environment().apply {
            clear()
            putAll(mapOf("HOME" to home, "CFFIXED_USER_HOME" to home, "LC_ALL" to "C"))
        }
        return BootstrapUidQueryProcess(builder.start(), kind).use { it.readIdentity() }
    }
}

private enum class BootstrapUidQuery(val options: List<String>) {
    REAL(listOf("-r", "-u")),
    EFFECTIVE(listOf("-u")),
}

private class BootstrapUidQueryProcess(private val process: Process, private val kind: BootstrapUidQuery) : AutoCloseable {
    fun readIdentity(): String = observeBootstrapProcess {
        process.outputStream.close()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Bootstrap numeric query observation timed out." }
        check(process.exitValue() == 0) { "Bootstrap numeric query rejected." }
        // A byte cap alone does not bound time: never read before observing the fixed utility's exit.
        BootstrapProbeEnvironment.parseRecord(process.inputStream.readNBytes(12))
    }

    override fun close() {
        val exit = reapBootstrapProcess(process)
        println("BOOTSTRAP_UID_CLEANUP kind=${kind.name} pid=${process.pid()} exit=$exit exit_observed=true alive=false")
    }
}
