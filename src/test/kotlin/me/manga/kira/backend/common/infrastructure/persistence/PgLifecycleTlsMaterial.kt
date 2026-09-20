package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.OpenOption
import java.nio.file.Path
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.PosixFilePermissions
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/** Child-side owner of this run's ephemeral server key and four public certificates. No resource/private-key constants. */
internal class PgLifecycleTlsMaterial private constructor(private val files: PgLifecycleTlsFiles, private val removeOnClose: Boolean = true) : AutoCloseable {
    private val closed = AtomicBoolean()

    fun rootCertificate(wrong: Boolean): Path {
        check(!closed.get())
        val asset = if (wrong) PgLifecycleTlsAsset.WRONG_CA else PgLifecycleTlsAsset.ROOT_CA
        files.readVerified(asset).fill(0) // Existing generated certificate, never a missing-path settings refusal.
        return files.path(asset)
    }

    fun serverContext(mode: PgLifecycleTlsMode): SSLContext {
        check(!closed.get())
        val root = certificate(PgLifecycleTlsAsset.ROOT_CA)
        val wrongRoot = certificate(PgLifecycleTlsAsset.WRONG_CA)
        val matched = certificate(PgLifecycleTlsAsset.SERVER)
        val wrongHost = certificate(PgLifecycleTlsAsset.WRONG_HOST)
        PgLifecycleTlsCertificateChecks.verify(root, wrongRoot, matched, wrongHost)
        val leaf = if (mode === PgLifecycleTlsMode.WRONG_HOST) wrongHost else matched
        val password = PgLifecycleTlsFiles.STORE_PASSWORD.toCharArray()
        try {
            val key = serverKey(password, matched)
            val store = KeyStore.getInstance("JKS").also { it.load(null, null) }
            store.setCertificateEntry(PgLifecycleTlsFiles.ROOT_ALIAS, root)
            store.setKeyEntry(PgLifecycleTlsFiles.SERVER_ALIAS, key, password, arrayOf(leaf, root))
            val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).also { it.init(store, password) }
            val trust = TrustManagerFactory.getInstance("PKIX").also { it.init(store) }
            return SSLContext.getInstance("TLS").also { it.init(keys.keyManagers, trust.trustManagers, null) }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Existing generated material only. The database fixture retains both destination files before this call. */
    fun exportPostgresqlServer(certificatePath: Path, privateKeyPath: Path) {
        check(!closed.get())
        val root = certificate(PgLifecycleTlsAsset.ROOT_CA)
        val wrongRoot = certificate(PgLifecycleTlsAsset.WRONG_CA)
        val matched = certificate(PgLifecycleTlsAsset.SERVER)
        val wrongHost = certificate(PgLifecycleTlsAsset.WRONG_HOST)
        PgLifecycleTlsCertificateChecks.verify(root, wrongRoot, matched, wrongHost)
        val password = PgLifecycleTlsFiles.STORE_PASSWORD.toCharArray()
        try {
            val key = serverKey(password, matched)
            check(key.format == "PKCS#8")
            val encoded = key.encoded
            val body = Base64.getMimeEncoder(64, byteArrayOf(10)).encode(encoded)
            val header = "-----BEGIN PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII)
            val footer = "\n-----END PRIVATE KEY-----\n".toByteArray(Charsets.US_ASCII)
            val pem = ByteArray(header.size + body.size + footer.size)
            header.copyInto(pem)
            body.copyInto(pem, header.size)
            footer.copyInto(pem, header.size + body.size)
            try {
                writePrivateFile(certificatePath, files.readVerified(PgLifecycleTlsAsset.SERVER))
                writePrivateFile(privateKeyPath, pem)
            } finally {
                encoded.fill(0)
                body.fill(0)
                pem.fill(0)
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /** Material checks only; this neither starts a TLS peer nor attests a PostgreSQL connection. */
    fun verifyPostgresqlMaterial() {
        check(!closed.get())
        val matched = certificate(PgLifecycleTlsAsset.SERVER)
        PgLifecycleTlsCertificateChecks.verify(
            certificate(PgLifecycleTlsAsset.ROOT_CA),
            certificate(PgLifecycleTlsAsset.WRONG_CA),
            matched,
            certificate(PgLifecycleTlsAsset.WRONG_HOST),
        )
        val password = PgLifecycleTlsFiles.STORE_PASSWORD.toCharArray()
        try {
            serverKey(password, matched)
        } finally {
            password.fill('\u0000')
        }
    }

    private fun writePrivateFile(path: Path, bytes: ByteArray) {
        val options = setOf<OpenOption>(CREATE_NEW, WRITE, NOFOLLOW_LINKS)
        Files.newByteChannel(path, options, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------"))).use { output ->
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) check(output.write(buffer) > 0)
        }
    }

    private fun serverKey(password: CharArray, leaf: X509Certificate): PrivateKey {
        val bytes = files.readVerified(PgLifecycleTlsAsset.SERVER_STORE)
        val store = KeyStore.getInstance("JKS")
        try {
            bytes.inputStream().use { store.load(it, password) }
        } finally {
            bytes.fill(0)
        }
        check(store.size() == 1 && store.isKeyEntry(PgLifecycleTlsFiles.SERVER_ALIAS))
        check(store.getCertificate(PgLifecycleTlsFiles.SERVER_ALIAS).publicKey.encoded.contentEquals(leaf.publicKey.encoded))
        val key = store.getKey(PgLifecycleTlsFiles.SERVER_ALIAS, password) as PrivateKey
        check(key.algorithm == "RSA")
        val challenge = "synthetic-ephemeral-key-match".toByteArray(Charsets.US_ASCII)
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(key)
            update(challenge)
            sign()
        }
        try {
            check(
                Signature.getInstance("SHA256withRSA").run {
                    initVerify(leaf.publicKey)
                    update(challenge)
                    verify(signature)
                },
            )
        } finally {
            signature.fill(0)
        }
        return key
    }

    private fun certificate(asset: PgLifecycleTlsAsset): X509Certificate {
        val bytes = files.readVerified(asset)
        val certificates = bytes.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificates(it) }
        return certificates.single() as X509Certificate
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        if (removeOnClose) {
            files.removeAll()
            println("PG_LIFECYCLE_TLS_MATERIAL_CLEANUP ephemeral_files=5 removed=true")
        }
    }

    companion object {
        /** Caller keeps the actual filesystem owner; opening this view does not transfer cleanup authority. */
        fun fromPrepared(root: Path): PgLifecycleTlsMaterial = PgLifecycleTlsMaterial(PgLifecycleTlsFiles(root), removeOnClose = false)

        fun inChild(): PgLifecycleTlsMaterial {
            val home = Path.of(System.getProperty("user.home"))
            check(home.isAbsolute && home.fileName.toString() == "home")
            return PgLifecycleTlsMaterial(PgLifecycleTlsFiles(home.parent))
        }
    }
}
