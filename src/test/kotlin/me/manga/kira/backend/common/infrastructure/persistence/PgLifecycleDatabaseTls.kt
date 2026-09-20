package me.manga.kira.backend.common.infrastructure.persistence

import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.MountableFile
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.sql.Statement
import java.util.UUID

/** One real-PG fixture's material only. Local material remains controller-owned until that server stops. */
internal class PgLifecycleDatabaseTls private constructor(val root: Path, private val controllerOwned: Boolean) : AutoCloseable {
    private val files = PgLifecycleTlsFiles(root)
    private val generator = if (controllerOwned) null else PgLifecycleTlsMaterialOwner(root)
    private val createdDirectories = ArrayList<Path>(4)
    private val exported = root.resolve("postgresql")
    private var prepared = false
    private var closed = false

    fun prepare() {
        check(!prepared && !closed)
        if (!controllerOwned) {
            createDirectory(root)
            createDirectory(root.resolve("home"))
            createDirectory(root.resolve("tmp"))
            checkNotNull(generator).stage(BootstrapProbeEnvironment.identity(root))
            createDirectory(exported)
            PgLifecycleTlsMaterial.fromPrepared(root).use { material ->
                material.exportPostgresqlServer(exported.resolve("server.crt"), exported.resolve("server.key"))
            }
        }
        check(Files.getPosixFilePermissions(root, NOFOLLOW_LINKS) == DIRECTORY_PERMISSIONS)
        PgLifecycleTlsMaterial.fromPrepared(root).use { it.verifyPostgresqlMaterial() }
        prepared = true
    }

    fun publicTrust(wrong: Boolean = false): ByteArray {
        check(prepared && !closed)
        return files.readVerified(if (wrong) PgLifecycleTlsAsset.WRONG_CA else PgLifecycleTlsAsset.ROOT_CA)
    }

    fun publicTrustParent(): Path {
        check(prepared && !closed)
        return root.resolve("trust-${UUID.randomUUID()}") // Cold path: the retained client owner creates it explicitly.
    }

    fun configureContainer(postgres: PostgreSQLContainer<*>) {
        check(prepared && !closed && !controllerOwned)
        postgres.withCopyFileToContainer(MountableFile.forHostPath(exported.resolve("server.crt"), 384), CONTAINER_CERTIFICATE)
            .withCopyFileToContainer(MountableFile.forHostPath(exported.resolve("server.key"), 384), CONTAINER_KEY)
            .withCommand(
                "sh",
                "-ec",
                "chown postgres:postgres $CONTAINER_CERTIFICATE $CONTAINER_KEY; " +
                    "exec docker-entrypoint.sh postgres -c max_connections=35 -c shared_buffers=64MB " +
                    "-c password_encryption=scram-sha-256 -c ssl=on " +
                    "-c ssl_cert_file=$CONTAINER_CERTIFICATE -c ssl_key_file=$CONTAINER_KEY",
            )
    }

    /** Independent superuser observation of this actual postmaster, never a candidate or descriptor-only assertion. */
    fun verifyServer(statement: Statement, data: Path?) {
        check(prepared && !closed)
        val certificate = data?.resolve("server.crt")?.toString() ?: CONTAINER_CERTIFICATE
        val key = data?.resolve("server.key")?.toString() ?: CONTAINER_KEY
        val expectedListen = if (data == null) "*" else LISTEN_ADDRESSES
        val publicCertificateHash = MessageDigest.getInstance("SHA-256").digest(files.readVerified(PgLifecycleTlsAsset.SERVER))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        statement.executeQuery(
            "SELECT current_setting('ssl'), current_setting('listen_addresses'), current_setting('ssl_cert_file'), " +
                "current_setting('ssl_key_file'), encode(sha256(pg_read_binary_file(current_setting('ssl_cert_file'))), 'hex'), " +
                "current_setting('password_encryption'), " +
                "(SELECT rolpassword LIKE 'SCRAM-SHA-256${'$'}%' FROM pg_authid WHERE rolname = '${PgLifecycleDatabaseSettings.CANDIDATE}'), " +
                "(SELECT count(*) > 0 AND bool_and(auth_method = 'scram-sha-256') FROM pg_hba_file_rules WHERE type LIKE 'host%'), " +
                "NOT EXISTS (SELECT 1 FROM pg_hba_file_rules WHERE error IS NOT NULL)",
        ).use { row ->
            check(row.next())
            check(row.getString(1) == "on" && row.getString(2) == expectedListen)
            check(row.getString(3) == certificate && row.getString(4) == key && row.getString(5) == publicCertificateHash)
            check(row.getString(6) == "scram-sha-256")
            for (column in 7..9) check(row.getBoolean(column) && !row.wasNull())
            check(!row.next())
        }
    }

    override fun close() {
        if (closed) return
        if (controllerOwned) {
            closed = true // Revoke this fixture view only. The controller still owns its running server and all files.
            return
        }
        generator?.close() // Refuse file deletion beneath an unended/uncertain material-generation actor.
        if (exported in createdDirectories) {
            Files.deleteIfExists(exported.resolve("server.key"))
            Files.deleteIfExists(exported.resolve("server.crt"))
        }
        createdDirectories.asReversed().forEach { Files.delete(it) } // Nonrecursive: retain unknown or unreleased client-trust material.
        closed = true
    }

    private fun createDirectory(path: Path) {
        Files.createDirectory(path, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS))
        createdDirectories.add(path)
    }

    companion object {
        const val CLASS_NAME = "me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedIT"
        const val LISTEN_ADDRESSES = "127.0.0.1,127.0.0.2"
        val CLASS_NAMES = setOf(
            CLASS_NAME,
            "me.manga.kira.backend.common.infrastructure.persistence.CatalogGenesisPublishInterruptionIT",
            "me.manga.kira.backend.complaint.journal.LiveJournalCoverageV1IT",
            "me.manga.kira.backend.complaint.journal.OwnerDeleteAllLiveJournalPublisherV1IT",
        )
        private const val CONTAINER_CERTIFICATE = "/tmp/kira-versionbound-pg/server.crt"
        private const val CONTAINER_KEY = "/tmp/kira-versionbound-pg/server.key"
        private val DIRECTORY_PERMISSIONS = PosixFilePermissions.fromString("rwx------")

        fun forFixture(fixtureClass: Class<*>?, controllerRoot: Path?): PgLifecycleDatabaseTls? {
            if (fixtureClass?.name !in CLASS_NAMES) return null
            val root = controllerRoot ?: Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath()
                .resolve("kira-versionbound-pg-tls-${UUID.randomUUID()}")
            return PgLifecycleDatabaseTls(root, controllerRoot != null)
        }
    }
}
