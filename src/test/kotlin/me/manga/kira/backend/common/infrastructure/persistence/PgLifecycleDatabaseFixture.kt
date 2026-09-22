package me.manga.kira.backend.common.infrastructure.persistence

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.Statement
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** JUnit owns its container; an explicitly bound local server remains controller-owned. No arbitrary external datasource. */
internal class PgLifecycleDatabaseFixture private constructor(
    private val fixtureClass: Class<*>?,
    private val child: PgLifecycleOwnedChildAttachmentV1?,
) : AutoCloseable {
    constructor(fixtureClass: Class<*>? = null) : this(fixtureClass, null)
    private val local = if (child == null) PgLifecycleControllerOwnedServer.load(fixtureClass) else null
    private val tls = child?.let { PgLifecycleDatabaseTls.borrowOwnedContainerMaterial(it.tlsRoot) }
        ?: PgLifecycleDatabaseTls.forFixture(fixtureClass, local?.tlsRoot())
    private val postgres = if (local == null && child == null) newPostgres() else null
    private lateinit var generation: Instant
    private var localVerified = false

    val host: String
        get() = postgres?.host ?: run {
            check(localVerified)
            child?.host ?: "127.0.0.1"
        }
    val port: Int
        get() = postgres?.firstMappedPort ?: run {
            check(localVerified)
            child?.port ?: requireNotNull(local).port
        }

    fun start() {
        try {
            child?.requireParent()
            tls?.prepare()
            postgres?.let { server ->
                tls?.configureContainer(server)
                server.start()
            }
            connection("w03o_bootstrap").use { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = 2
                    statement.executeQuery(
                        "SELECT pg_postmaster_start_time(), current_setting('server_version_num'), current_setting('server_encoding'), pg_is_in_recovery()",
                    ).use { result ->
                        check(result.next())
                        generation = result.getTimestamp(1).toInstant()
                        check(result.getString(2) == "170006" && result.getString(3) == "UTF8")
                        check(!result.getBoolean(4) && !result.wasNull()) { "Synthetic PostgreSQL was not positively witnessed as primary." }
                        check(!result.next())
                    }
                    local?.verify(statement, generation)
                    if (child == null) {
                        statement.execute(
                            "CREATE ROLE ${PgLifecycleDatabaseSettings.CANDIDATE} LOGIN PASSWORD '${PgLifecycleDatabaseSettings.CANDIDATE_PASSWORD}'",
                        )
                    } else {
                        check(generation == child.generation)
                        check(ColdFixtureFilesV1.sha256(checkNotNull(tls).publicTrust()) == child.trustSha256)
                        statement.executeQuery("SELECT rolcanlogin AND NOT rolsuper FROM pg_roles WHERE rolname = '${PgLifecycleDatabaseSettings.CANDIDATE}'").use { row ->
                            check(row.next() && row.getBoolean(1) && !row.wasNull() && !row.next())
                        }
                    }
                    tls?.verifyServer(statement, local?.tlsData())
                }
            }
            localVerified = local != null || child != null
            if (tls != null) verifySecondLoopback()
            child?.requireParent()
        } catch (failure: Throwable) {
            runCatching { close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    fun observer(nonce: String = UUID.randomUUID().toString()): PgLifecycleDatabaseObserver {
        check(postgres != null || localVerified)
        check(UUID.fromString(nonce).toString() == nonce)
        return PgLifecycleDatabaseObserver(connection("w03o_$nonce"), generation)
    }

    fun versionBoundTls(): PgLifecycleDatabaseTls {
        check(::generation.isInitialized && (postgres != null || localVerified))
        return checkNotNull(tls)
    }

    /** Real same-generation endpoint control: hostname-negative clients must not merely hit an unopened address. */
    private fun verifySecondLoopback() {
        check(host == "127.0.0.1" || host == "localhost")
        connection("w03o_tls_second_loopback", "127.0.0.2").use { connection ->
            connection.createStatement().use { statement ->
                statement.queryTimeout = 2
                statement.executeQuery("SELECT pg_postmaster_start_time(), current_database(), current_user, host(inet_server_addr())").use { row ->
                    check(row.next() && row.getTimestamp(1).toInstant() == generation)
                    check(row.getString(2) == PgLifecycleDatabaseSettings.DATABASE && row.getString(3) == PgLifecycleDatabaseSettings.OBSERVER)
                    if (local != null) check(row.getString(4) == "127.0.0.2")
                    check(!row.next())
                }
                checkNotNull(tls).verifyServer(statement, local?.tlsData())
            }
        }
    }

    private fun connection(application: String, selectedHost: String? = null): Connection {
        val properties = Properties().apply {
            setProperty("user", PgLifecycleDatabaseSettings.OBSERVER)
            setProperty("password", PgLifecycleDatabaseSettings.OBSERVER_PASSWORD)
            setProperty("ApplicationName", application)
            setProperty("assumeMinServerVersion", "17")
            setProperty("sslmode", "disable")
            setProperty("gssEncMode", "disable")
            setProperty("requireAuth", "scram-sha-256")
            setProperty("channelBinding", "disable")
            setProperty("loginTimeout", "4")
            setProperty("connectTimeout", "2")
            setProperty("socketTimeout", "2")
            setProperty("queryTimeout", "0") // Constructor control; observer statement timeouts are set only after actual connection return.
        }
        val url = if (selectedHost != null) {
            "jdbc:postgresql://$selectedHost:$port/${PgLifecycleDatabaseSettings.DATABASE}"
        } else {
            postgres?.jdbcUrl ?: "jdbc:postgresql://${child?.host ?: "127.0.0.1"}:${child?.port ?: requireNotNull(local).port}/${PgLifecycleDatabaseSettings.DATABASE}"
        }
        val connection = DriverManager.getConnection(url, properties)
        try {
            connection.autoCommit = true
            connection.setNetworkTimeout({ command -> command.run() }, 2_000)
            return connection
        } catch (failure: Throwable) {
            runCatching { connection.close() }.exceptionOrNull()?.let(failure::addSuppressed)
            throw failure
        }
    }

    override fun close() {
        localVerified = false
        postgres?.stop() // Local close revokes this fixture's endpoint; only the controller stops/deletes its server.
        tls?.close() // Never remove server material if the owned container's stop failed; local material stays controller-owned.
    }

    /** Only the actual owning synthetic container can export this TEST attachment. No endpoint setter. */
    fun exportColdChildAttachment(root: Path): Path {
        check(fixtureClass == TestOrdinaryDrainConnectedIT::class.java && postgres != null && local == null && child == null)
        check(::generation.isInitialized)
        val material = versionBoundTls()
        return PgLifecycleOwnedChildAttachmentV1.write(root, host, port, generation, material.root,
            ColdFixtureFilesV1.sha256(material.publicTrust()))
    }

    companion object {
        /** Nonowning child view; no CREATE ROLE, migration, seeding, container stop or TLS deletion. */
        fun attachColdChild(path: Path, expectedSha256: String): PgLifecycleDatabaseFixture {
            check(System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") == null)
            return PgLifecycleDatabaseFixture(TestOrdinaryDrainConnectedIT::class.java,
                PgLifecycleOwnedChildAttachmentV1.read(path, expectedSha256)).also { it.start() }
        }
    }

    private fun newPostgres(): PostgreSQLContainer<*> = PostgreSQLContainer(DockerImageName.parse("postgres:17.6-alpine"))
        .withDatabaseName(PgLifecycleDatabaseSettings.DATABASE)
        .withUsername(PgLifecycleDatabaseSettings.OBSERVER)
        .withPassword(PgLifecycleDatabaseSettings.OBSERVER_PASSWORD)
        .withEnv("POSTGRES_INITDB_ARGS", "--encoding=UTF8 --locale=C --auth-host=scram-sha-256")
        .withEnv("POSTGRES_HOST_AUTH_METHOD", "scram-sha-256")
        .withCommand("postgres", "-c", "max_connections=35", "-c", "shared_buffers=64MB", "-c", "password_encryption=scram-sha-256")
        .withReuse(false)
}

/** Private parent-owned TEST-container descriptor; entirely separate from the unchanged local-controller gate. */
private class PgLifecycleOwnedChildAttachmentV1(
    val host: String, val port: Int, val generation: Instant, val tlsRoot: Path, val trustSha256: String,
    private val parentPid: Long, private val parentStart: Instant,
) {
    fun requireParent() {
        val parent = ProcessHandle.current().parent().orElseThrow()
        check(parent.pid() == parentPid && parent.isAlive && parent.info().startInstant().orElseThrow() == parentStart)
    }

    companion object {
        fun write(root: Path, host: String, port: Int, generation: Instant, tlsRoot: Path, trustHash: String): Path {
            check(host in setOf("127.0.0.1", "localhost") && port in 1..65535)
            ColdFixtureFilesV1.directory(root); ColdFixtureFilesV1.directory(tlsRoot)
            val process = ProcessHandle.current()
            val values = listOf("kira-cold-owned-container-1", UUID.randomUUID().toString(), host, port.toString(),
                generation.toString(), tlsRoot.toString(), trustHash, process.pid().toString(), process.info().startInstant().orElseThrow().toString())
            check(values.all { '\n' !in it && '\r' !in it })
            return root.resolve("owned-container.txt").also { ColdFixtureFilesV1.write(it, (values.joinToString("\n") + "\n").toByteArray()) }
        }

        fun read(path: Path, expectedHash: String): PgLifecycleOwnedChildAttachmentV1 {
            check(path.fileName.toString() == "owned-container.txt" && expectedHash.matches(Regex("[0-9a-f]{64}")))
            val bytes = ColdFixtureFilesV1.read(path, 4096)
            check(ColdFixtureFilesV1.sha256(bytes) == expectedHash)
            val lines = bytes.toString(Charsets.UTF_8).removeSuffix("\n").split('\n')
            check(lines.size == 9 && lines[0] == "kira-cold-owned-container-1")
            check(UUID.fromString(lines[1]).toString() == lines[1])
            check(lines[2] in setOf("127.0.0.1", "localhost"))
            val port = lines[3].toInt().also { check(it in 1..65535 && it.toString() == lines[3]) }
            val tls = Path.of(lines[5]); ColdFixtureFilesV1.directory(tls)
            check(lines[6].matches(Regex("[0-9a-f]{64}")))
            return PgLifecycleOwnedChildAttachmentV1(lines[2], port, Instant.parse(lines[4]), tls, lines[6], lines[7].toLong(),
                Instant.parse(lines[8])).also { it.requireParent() }
        }
    }
}

/** Private synthetic inputs only. Bounded, stable, same-owner regular files; never upload the scratch tree. */
internal object ColdFixtureFilesV1 {
    val directoryMode = PosixFilePermissions.fromString("rwx------")
    private val fileMode = PosixFilePermissions.fromString("rw-------")
    fun directory(path: Path) {
        check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path)
        check(Files.isDirectory(path, NOFOLLOW_LINKS) && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == directoryMode)
        owner(path)
    }
    private fun owner(path: Path) {
        val expected = path.fileSystem.userPrincipalLookupService.lookupPrincipalByName(System.getProperty("user.name"))
        check(Files.getOwner(path, NOFOLLOW_LINKS) == expected)
    }
    fun write(path: Path, bytes: ByteArray) {
        directory(path.parent)
        Files.newByteChannel(path, setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, NOFOLLOW_LINKS),
            PosixFilePermissions.asFileAttribute(fileMode)).use { channel ->
            val buffer = java.nio.ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) channel.write(buffer)
        }
    }
    fun read(path: Path, maximum: Int = 16 * 1024 * 1024): ByteArray {
        directory(path.parent)
        check(path.toRealPath() == path && Files.getPosixFilePermissions(path, NOFOLLOW_LINKS) == fileMode)
        owner(path)
        check(Files.getAttribute(path, "unix:nlink", NOFOLLOW_LINKS) == 1)
        val before = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(before.isRegularFile && before.fileKey() != null && before.size() in 1..maximum.toLong())
        val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(maximum + 1) }
        val after = Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        check(after.isRegularFile && after.fileKey() == before.fileKey() && after.size() == before.size() &&
            after.lastModifiedTime() == before.lastModifiedTime() && bytes.size.toLong() == before.size())
        return bytes
    }
    fun sha256(bytes: ByteArray): String = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
}

/** Private Linux gate input, not an endpoint configuration API or a server lifecycle implementation. */
private class PgLifecycleControllerOwnedServer(
    val port: Int,
    private val run: String,
    private val className: String,
    private val nonce: String,
    private val pid: Long,
    private val start: Long,
    private val generation: Instant,
    private val data: Path,
) {
    fun tlsRoot(): Path? = if (className in PgLifecycleDatabaseTls.CLASS_NAMES) {
        Path.of("/tmp/kcg-$run/${className.substringAfterLast('.')}-tls")
    } else {
        null
    }

    fun tlsData(): Path? = if (className in PgLifecycleDatabaseTls.CLASS_NAMES) data else null

    fun verify(statement: Statement, actualGeneration: Instant) {
        check(actualGeneration == generation)
        requireOwnedPath(data.parent, 1_000, directory = true)
        requireOwnedPath(data, 1_000, directory = true)
        requireOwnedPath(data.parent.resolve("sockets"), 1_000, directory = true)
        tlsRoot()?.let { root ->
            requireOwnedPath(root, 0, directory = true)
            requireOwnedPath(data.resolve("server.crt"), 1_000, directory = false)
            requireOwnedPath(data.resolve("server.key"), 1_000, directory = false)
        }
        val postmaster = readOwnedFile(data.resolve("postmaster.pid"), 1_000)
        val lines = postmaster.removeSuffix("\n").split('\n')
        check(lines.size == 8)
        check(lines[0] == pid.toString() && lines[1] == data.toString())
        check(lines[2] == start.toString() && lines[3] == port.toString())
        check(lines[4] == data.parent.resolve("sockets").toString() && lines[5] == "127.0.0.1" && lines[7].trim() == "ready")
        statement.executeQuery(WITNESS_QUERY).use { result ->
            check(result.next())
            check(result.getString(1) == run && result.getString(2) == className && result.getString(3) == nonce)
            check(result.getString(4) == data.toString() && result.getInt(5) == port)
            check(result.getString(6) == "127.0.0.1")
            check(result.getString(7) == PgLifecycleDatabaseSettings.DATABASE && result.getString(8) == PgLifecycleDatabaseSettings.OBSERVER)
            check(result.getString(9) == "scram-sha-256")
            check(result.getBoolean(10) && !result.wasNull())
            check(result.getString(11) == postmaster)
            check(!result.next())
        }
    }

    companion object {
        fun load(fixtureClass: Class<*>?): PgLifecycleControllerOwnedServer? {
            val run = System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") ?: return null
            check(run.matches(Regex("[0-9a-f]{32}")))
            val selected = requireNotNull(fixtureClass)
            check(
                selected.name in setOf(
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintGrantCleanupIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinarySourceGrantCleanupIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinarySourceGrantCleanupOwnershipIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ScopedAdminStepUpIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinaryComplaintAuditIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.SourceStepUpCleanupIT",
                    "me.manga.kira.backend.sourceconfig.admin.AdminStepUpIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinaryComplaintRecoveryIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintPartialRecoveryMigrationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinaryComplaintTestReserveIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestTerminalCapacityIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinaryComplaintInstallationEnrollmentIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.InstallationDeletionPreflightIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllVerificationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllApplyIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllApplyOutcomeIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllBoundReplayIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllHttpIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllContinuationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllJournalOwnershipIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OwnerDeleteAllAuthorizationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.OrdinaryComplaintTestInstallationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintSessionAdmissionIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintEnrollmentAdmissionIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintInstallationCurrentStateIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerHistoryIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerDetailIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintAdminReadIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintAdminContentIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintAdminStatusIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintAdminBatchStatusIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintAdminDeleteIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintAdminDeleteRecoveryIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintInstallationHttpIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerCreateIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerReplyIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerEditIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerDeleteIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintOwnerDeleteRecoveryIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintInstallationMeHttpIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.DeletionPoolPreparationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.DeletionComplaintAuditIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.DeletionFencePrefixIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.ComplaintMaintenanceFencePrefixIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.DeletionControlSnapshotIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.CatalogCoordinatorResourcesIT",
                    "me.manga.kira.backend.complaint.catalog.JdbcCatalogSnapshotIT",
                    "me.manga.kira.backend.complaint.journal.LiveJournalCoverageV1IT",
                    "me.manga.kira.backend.complaint.journal.OwnerDeleteAllJournalPublisherV1IT",
                    "me.manga.kira.backend.complaint.journal.TestOwnerDeleteJournalPublisherV1IT",
                    "me.manga.kira.backend.complaint.journal.OwnerDeleteAllLiveJournalPublisherV1IT",
                    "me.manga.kira.backend.common.infrastructure.persistence.PersistencePgOwnedCutIntegrationTest",
                    "me.manga.kira.backend.common.infrastructure.persistence.PersistencePgNativePhysicalCloseIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.CatalogGenesisPublishInterruptionIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainConnectedIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestOrdinaryDrainOwnerFamiliesIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestInstallationManifestPrepareIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestInstallationManifestPublicationIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestRunErasureIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestRunErasureGuardIT",
                    "me.manga.kira.backend.common.infrastructure.persistence.TestRunErasureRecoveryIT",
                    "me.manga.kira.backend.complaint.journal.TestOrdinaryInventoryReaderV1ConnectedIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveInitialCheckpointIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestActiveRecurrentIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointCreateIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredOwnerMeReplyEditHttpIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminReadHttpIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminContentHttpIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredAdminStatusHttpIT",
                    "me.manga.kira.backend.complaint.infrastructure.reconciliation.TestRegisteredInitialCheckpointDeletionIT",
                    PgLifecycleDatabaseTls.CLASS_NAME,
                ),
            )
            val root = Path.of("/tmp/kcg-$run")
            requireOwnedPath(root, 0, directory = true)
            val keys = listOf("format", "run", "class", "nonce", "port", "pid", "start", "generation", "data")
            val lines = readOwnedFile(root.resolve("${selected.simpleName}.descriptor"), 0).removeSuffix("\n").split('\n')
            check(lines.size == keys.size)
            val values = lines.mapIndexed { index, line ->
                val prefix = "${keys[index]}="
                check(line.startsWith(prefix))
                line.removePrefix(prefix)
            }
            check(values[0] == "kira-pg-lifecycle-local-1" && values[1] == run && values[2] == selected.name)
            check(values[3].matches(Regex("[0-9a-f]{64}")))
            val port = positiveLong(values[4])
            check(port <= 65_535)
            val pid = positiveLong(values[5])
            check(pid <= Int.MAX_VALUE)
            val start = positiveLong(values[6])
            val micros = positiveLong(values[7])
            val generation = Instant.ofEpochSecond(micros / 1_000_000, (micros % 1_000_000) * 1_000)
            val data = Path.of("/tmp/kcg-$run-${selected.simpleName}/data")
            check(values[8] == data.toString())
            requireOwnedPath(data.parent, 1_000, directory = true)
            requireOwnedPath(data, 1_000, directory = true)
            return PgLifecycleControllerOwnedServer(port.toInt(), run, selected.name, values[3], pid, start, generation, data)
        }

        private fun positiveLong(value: String): Long {
            val number = requireNotNull(value.toLongOrNull())
            check(number > 0 && number.toString() == value)
            return number
        }

        private fun requireOwnedPath(path: Path, uid: Int, directory: Boolean) {
            check(path.isAbsolute && path.normalize() == path && path.toRealPath() == path)
            check(if (directory) Files.isDirectory(path, NOFOLLOW_LINKS) else Files.isRegularFile(path, NOFOLLOW_LINKS))
            val attributes = Files.readAttributes(path, "unix:uid,gid,mode,nlink", NOFOLLOW_LINKS)
            check(attributes["uid"] == uid && attributes["gid"] == uid)
            check(((attributes.getValue("mode") as Int) and 0xFFF) == if (directory) 448 else 384)
            if (!directory) check(attributes["nlink"] == 1)
        }

        private fun readOwnedFile(path: Path, uid: Int): String {
            requireOwnedPath(path, uid, directory = false)
            val bytes = Files.newInputStream(path, NOFOLLOW_LINKS).use { it.readNBytes(2_049) }
            check(bytes.size in 1..2_048 && bytes.last() == 10.toByte())
            check(bytes.all { it == 10.toByte() || it.toInt() in 32..126 })
            return bytes.toString(Charsets.US_ASCII)
        }

        private val WITNESS_QUERY = """
            SELECT current_setting('kira_fixture.run', true), current_setting('kira_fixture.class', true),
                   current_setting('kira_fixture.nonce', true), current_setting('data_directory'), inet_server_port(),
                   host(inet_server_addr()), current_database(), current_user, current_setting('password_encryption'),
                   (SELECT rolpassword LIKE 'SCRAM-SHA-256${'$'}%' FROM pg_authid WHERE rolname = current_user),
                   pg_read_file('postmaster.pid', 0, 2048)
        """.trimIndent()
    }
}
