package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.ColdFixtureFilesV1
import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainConnectedIT
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import me.manga.kira.backend.common.infrastructure.persistence.ordinaryCleanupReader
import me.manga.kira.backend.complaint.catalog.ColdSqlObservationV1
import me.manga.kira.backend.complaint.catalog.ColdTestProcessEntryV1
import me.manga.kira.backend.complaint.catalog.ComplaintTestColdProcessSupportV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledOnOs
import org.junit.jupiter.api.condition.OS
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.springframework.jdbc.core.JdbcTemplate
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import kotlin.system.exitProcess

/**
 * SOURCE_ONLY / NOT_COMPILED / NOT_RUN. One fixed TEST child, genuine PG/TLS/SDKs and synthetic
 * raw HTTP. Its explicit exit contains retained B; it proves neither product cleanup nor STOPPED,
 * recovery or AWS/launch authority. Fit within the unchanged 180-second child bound is unqualified.
 */
@Execution(ExecutionMode.SAME_THREAD)
@EnabledOnOs(OS.LINUX, OS.MAC)
class TestActiveServicePoisonProcessCasesV1 {
    @Test fun firstScannerStsCloseRefusalRetainsServiceParentsWithoutRedispatch() {
        assumeTrue(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") == null,
            "This fixed child owns a Testcontainers database; the strict local-controller lane is unchanged and supplies no coverage here.")
        val root = Files.createTempDirectory(Path.of(System.getProperty("user.home")).toRealPath(), "kira-service-close-child-",
            PosixFilePermissions.asFileAttribute(ColdFixtureFilesV1.directoryMode))
        val rootKey = Files.readAttributes(root, PosixFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()
        val children = mutableListOf<Process>()
        val database = PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java)
        var jdbc: JdbcTemplate? = null
        var generation: Instant? = null
        var backing: ServiceRecurrentCloseTlsBackingV1? = null
        var evidence: ServiceRecurrentCloseEvidenceV1? = null
        var failure: Throwable? = null
        try {
            database.start()
            val observer = JdbcTemplate(ordinaryCleanupReader(database)).also { jdbc = it }
            generation = ColdSqlObservationV1.generation(observer)
            backing = ServiceRecurrentCloseTlsBackingV1(database.versionBoundTls().root)
            val descriptor = database.exportColdChildAttachment(root)
            val hash = ColdFixtureFilesV1.sha256(ColdFixtureFilesV1.read(descriptor, 4096))
            val child = ComplaintTestColdProcessSupportV1.runChild(root, hash, ServiceRecurrentCloseProtocolV1.STAGE, false,
                children, ColdTestProcessEntryV1.ACTIVE_SERVICE_RECURRENT_CLOSE_RETAINED)
            assertSame(child.first, children.single()); assertFalse(child.first.isAlive)
            assertEquals(0, child.first.exitValue())
            evidence = ServiceRecurrentCloseProtocolV1.read(root, hash, child.first.pid(), child.second)
        } catch (problem: Throwable) {
            failure = problem
            throw problem
        } finally {
            var interrupted = Thread.interrupted()
            try {
                // Exceptional force is containment of THIS owned child only, and never repairs the failed case.
                children.forEach(ComplaintTestColdProcessSupportV1::stopOwnedChild)
                interrupted = Thread.interrupted() || interrupted
                check(children.none(Process::isAlive))
                val observer = checkNotNull(jdbc) { "Unobserved fixture setup; retain private backing." }
                ColdSqlObservationV1.noRuntimeSessions(observer)
                assertEquals(checkNotNull(generation), ColdSqlObservationV1.generation(observer))
                // No child original is closed/released here. Only identified TEST files may be disposed
                // after independent process/session/generation containment. Unknown evidence stays held.
                checkNotNull(backing).disposeChildTrust(evidence)
                database.close() // Actual parent container stop, then its ordinary server-material owner.
                ColdFixtureFilesV1.directory(root)
                check(rootKey != null && Files.readAttributes(root, PosixFileAttributes::class.java, NOFOLLOW_LINKS).fileKey() == rootKey)
                Files.walk(root).use { paths -> paths.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
            } catch (cleanup: Throwable) {
                if (cleanup is InterruptedException) interrupted = true
                if (failure == null) throw cleanup else checkNotNull(failure).addSuppressed(cleanup)
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}

/** Fixed TEST main only; no alternate stage, flag, application entrypoint or endpoint override. */
internal object TestActiveServicePoisonProcessV1 {
    @JvmStatic
    fun main(args: Array<String>) {
        var root: Path? = null
        try {
            require(args.size == 4 && args[2] == ServiceRecurrentCloseProtocolV1.STAGE && args[3] == "false")
            val selected = Path.of(args[0])
            ColdFixtureFilesV1.directory(selected)
            check(selected == Path.of(System.getProperty("user.home")).toRealPath())
            root = selected
            val hash = args[1].also { check(it.matches(Regex("[0-9a-f]{64}"))) }
            PgLifecycleDatabaseFixture.attachColdChild(selected.resolve("owned-container.txt"), hash).use { database ->
                val backing = ServiceRecurrentCloseTlsBackingV1(database.versionBoundTls().root)
                VersionBoundPersistenceConnectedFixture(database, testActivation = true, activeFirstCut = true).use { tls ->
                    tls.bind()
                    withRecurrentFixture(tls, applied = false) { precursor ->
                        // This innermost boundary MUST NOT unwind retained B through A's destructive
                        // row/trigger teardown, even if an assertion, observation or record write fails.
                        try {
                            val retained = TestActiveServiceFixtureV1(precursor)
                                .serveWithFirstScannerCloseRefusal(selected.resolve("service-recurrent-close-manifest.json"))
                            val evidence = backing.evidence(retained)
                            val child = ProcessHandle.current()
                            ServiceRecurrentCloseProtocolV1.write(selected, hash, child.pid(), child.info().startInstant().orElseThrow(), evidence)
                            exitProcess(0) // Explicit TEST containment ONLY, after actual service finally and every assertion.
                        } catch (problem: Throwable) {
                            containFailure(selected, problem) // Exit1 here, not after an outer teardown exception.
                        }
                    }
                }
            }
            error("Fixed child must exit at its retained-service containment boundary.")
        } catch (problem: Throwable) {
            containFailure(root, problem) // Setup failed before a B-bearing callback; never a negative-test PASS.
        }
    }

    private fun containFailure(root: Path?, problem: Throwable): Nothing {
        try {
            if (root != null) {
                val safe = ("stage=${ServiceRecurrentCloseProtocolV1.STAGE} type=${problem.javaClass.name}\n" +
                    problem.stackTrace.take(10).joinToString("\n") { "${it.className}.${it.methodName}:${it.lineNumber}" })
                    .take(4096).toByteArray(Charsets.UTF_8).take(4096).toByteArray()
                ColdFixtureFilesV1.write(root.resolve("${ServiceRecurrentCloseProtocolV1.STAGE}-failure.txt"), safe)
            }
        } catch (_: Throwable) { /* No alternate destination, raw messages, rows, secrets or terminal output. */ }
        exitProcess(1)
    }
}

private data class ServiceRecurrentCloseEvidenceV1(val tlsRootKey: String, val retainedTrust: String, val trustParents: Map<String, String>)

/** Bounded TEST observation, not a transferable product receipt or a child-supplied deletion path. */
private object ServiceRecurrentCloseProtocolV1 {
    const val STAGE = "service-recurrent-close"
    const val MAX_TRUST_PARENTS = 16
    private const val RECORD = "service-recurrent-close-observation.txt"
    private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
    fun requireTrustName(value: String) { check(value.matches(Regex("trust-$UUID"))) }
    fun requireGenerationName(value: String) { check(value.matches(Regex("kira-pg-trust-$UUID"))) }
    fun key(value: Any?): String = checkNotNull(value).toString().also {
        check(it.length in 1..128 && it.all { char -> char in ' '..'~' && char != '\t' })
    }
    private fun header(hash: String, pid: Long, start: Instant) = listOf("kira-service-retained-observation-1", hash,
        pid.toString(), start.toString(), STAGE, "CUSTODY_RETAINED", "CLEANUP_UNPROVEN", "assertions-completed;containment-only;not-product-cleanup")
    private fun bytes(hash: String, pid: Long, start: Instant, evidence: ServiceRecurrentCloseEvidenceV1): ByteArray {
        requireTrustName(evidence.retainedTrust); key(evidence.tlsRootKey)
        check(evidence.trustParents.size in 1..MAX_TRUST_PARENTS && evidence.retainedTrust in evidence.trustParents)
        val trust = evidence.trustParents.toSortedMap().map { (name, identity) -> requireTrustName(name); key(identity); "$name\t$identity" }
        return (header(hash, pid, start) + listOf(evidence.tlsRootKey, evidence.retainedTrust, trust.size.toString()) + trust)
            .joinToString("\n", postfix = "\n").toByteArray(Charsets.UTF_8).also { check(it.size in 1..4096) }
    }
    fun write(root: Path, hash: String, pid: Long, start: Instant, evidence: ServiceRecurrentCloseEvidenceV1) =
        ColdFixtureFilesV1.write(root.resolve(RECORD), bytes(hash, pid, start, evidence)) // private0600 / CREATE_NEW
    fun read(root: Path, hash: String, pid: Long, start: Instant): ServiceRecurrentCloseEvidenceV1 {
        val raw = ColdFixtureFilesV1.read(root.resolve(RECORD), 4096)
        val lines = raw.toString(Charsets.UTF_8).removeSuffix("\n").split('\n')
        check(lines.size in 12..(11 + MAX_TRUST_PARENTS) && lines.take(8) == header(hash, pid, start))
        val count = lines[10].toIntOrNull()
        check(count != null && count in 1..MAX_TRUST_PARENTS && lines[10] == count.toString() && lines.size == 11 + count)
        val entries = lines.drop(11).map { line ->
            val pair = line.split('\t'); check(pair.size == 2); requireTrustName(pair[0]); key(pair[1]); pair[0] to pair[1]
        }
        check(entries.map { it.first }.distinct().size == count)
        val evidence = ServiceRecurrentCloseEvidenceV1(lines[8], lines[9], entries.toMap())
        check(raw.contentEquals(bytes(hash, pid, start, evidence))) // Exact canonical bounded record; no raw content in errors.
        return evidence
    }
}

/** Narrow fixed-case TEST artifact disposal. Never closes a product owner or accepts an absolute child deletion target. */
private class ServiceRecurrentCloseTlsBackingV1(private val root: Path) {
    private val rootIdentity = node(root).also { ColdFixtureFilesV1.directory(root) }
    private val baseline = children(root, 32).associateWith(::node)

    fun evidence(retained: Path): ServiceRecurrentCloseEvidenceV1 {
        check(retained.parent == root)
        ServiceRecurrentCloseProtocolV1.requireTrustName(retained.fileName.toString())
        val parents = newParents()
        check(retained in parents)
        parents.forEach { tree(it) } // Observe the actual bounded no-link tree, without deleting or closing it.
        return ServiceRecurrentCloseEvidenceV1(ServiceRecurrentCloseProtocolV1.key(rootIdentity.attributes.fileKey()),
            retained.fileName.toString(), parents.associate { it.fileName.toString() to ServiceRecurrentCloseProtocolV1.key(node(it).attributes.fileKey()) })
    }

    fun disposeChildTrust(evidence: ServiceRecurrentCloseEvidenceV1?) {
        val parents = newParents()
        if (evidence == null) {
            check(parents.isEmpty()) { "No bound child evidence for retained trust; keep private backing." }
            return
        }
        check(evidence.tlsRootKey == ServiceRecurrentCloseProtocolV1.key(rootIdentity.attributes.fileKey()))
        check(evidence.retainedTrust in evidence.trustParents)
        check(parents.map { it.fileName.toString() }.toSet() == evidence.trustParents.keys)
        val captured = parents.flatMap { parent ->
            tree(parent).also { nodes ->
                check(ServiceRecurrentCloseProtocolV1.key(nodes.first().attributes.fileKey()) == evidence.trustParents.getValue(parent.fileName.toString()))
            }
        }
        captured.asReversed().forEach { selected ->
            requireRootAndBaseline()
            captured.filter { it.attributes.isDirectory && selected.path.startsWith(it.path) && it.path != selected.path }.forEach { it.requireSame() }
            selected.requireSame()
            Files.delete(selected.path) // Known regular PEM / known empty directory only; never recursive or link-following.
        }
        check(newParents().isEmpty())
    }

    private fun newParents(): List<Path> {
        requireRootAndBaseline()
        return children(root, 32).filter { it !in baseline }.also { paths ->
            check(paths.size <= ServiceRecurrentCloseProtocolV1.MAX_TRUST_PARENTS)
            paths.forEach { ServiceRecurrentCloseProtocolV1.requireTrustName(it.fileName.toString()); ColdFixtureFilesV1.directory(it) }
        }
    }
    private fun requireRootAndBaseline() {
        ColdFixtureFilesV1.directory(root); rootIdentity.requireSame()
        baseline.values.forEach { it.requireSame() }
    }
    private fun tree(parent: Path): List<Node> {
        ColdFixtureFilesV1.directory(parent)
        val selected = mutableListOf(node(parent))
        children(parent, 4).forEach { generation ->
            ServiceRecurrentCloseProtocolV1.requireGenerationName(generation.fileName.toString())
            ColdFixtureFilesV1.directory(generation); selected.add(node(generation))
            children(generation, 1).forEach { pem ->
                check(pem.fileName.toString() == "roots.pem")
                val observed = node(pem)
                check(observed.attributes.isRegularFile && observed.attributes.size() in 0..262_144L)
                check(observed.attributes.permissions() in setOf(PosixFilePermissions.fromString("r--------"), PosixFilePermissions.fromString("rw-------")))
                check(Files.getAttribute(pem, "unix:nlink", NOFOLLOW_LINKS) == 1)
                selected.add(observed)
            }
        }
        return selected
    }
    private fun children(path: Path, maximum: Int): List<Path> {
        ColdFixtureFilesV1.directory(path)
        val paths = mutableListOf<Path>()
        Files.newDirectoryStream(path).use { stream -> stream.forEach { child -> check(paths.size < maximum); paths.add(child) } }
        return paths.sortedBy { it.fileName.toString() }
    }
    private fun node(path: Path): Node {
        val attributes = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
        check(!attributes.isSymbolicLink && (attributes.isDirectory || attributes.isRegularFile) && attributes.fileKey() != null)
        check(Files.getOwner(root, NOFOLLOW_LINKS) == attributes.owner())
        return Node(path, attributes)
    }
    private class Node(val path: Path, val attributes: PosixFileAttributes) {
        fun requireSame() {
            val current = Files.readAttributes(path, PosixFileAttributes::class.java, NOFOLLOW_LINKS)
            check(!current.isSymbolicLink && current.fileKey() == attributes.fileKey() && current.owner() == attributes.owner() &&
                current.permissions() == attributes.permissions() && current.isDirectory == attributes.isDirectory && current.isRegularFile == attributes.isRegularFile)
            if (current.isRegularFile) check(current.size() == attributes.size() && current.lastModifiedTime() == attributes.lastModifiedTime() &&
                Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) == 1)
        }
    }
}
