package me.manga.kira.backend.common.infrastructure.persistence

import java.security.SignatureException
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.time.Instant

/** Validate semantics independently of per-generation byte hashes; no client trust-store or hostname bypass. */
internal object PgLifecycleTlsCertificateChecks {
    fun verify(root: X509Certificate, wrongRoot: X509Certificate, matched: X509Certificate, wrongHost: X509Certificate) {
        listOf(root, wrongRoot, matched, wrongHost).forEach { certificate ->
            certificate.checkValidity()
            check(certificate.version == 3 && certificate.sigAlgOID == "1.2.840.113549.1.1.11")
            check((certificate.publicKey as RSAPublicKey).modulus.bitLength() == 2_048)
            check(certificate.notBefore.toInstant() == Instant.parse("2025-01-01T00:00:00Z"))
            check(certificate.notAfter.toInstant() == Instant.parse("2055-01-01T00:00:00Z"))
        }
        listOf(root, wrongRoot).forEach { certificate ->
            check(certificate.basicConstraints == 0 && certificate.subjectX500Principal == certificate.issuerX500Principal)
            check(usages(certificate) == setOf(5, 6) && "2.5.29.19" in certificate.criticalExtensionOIDs)
            certificate.verify(certificate.publicKey)
        }
        check(root.subjectX500Principal != wrongRoot.subjectX500Principal)
        check(!root.publicKey.encoded.contentEquals(wrongRoot.publicKey.encoded))
        check(matched.publicKey.encoded.contentEquals(wrongHost.publicKey.encoded))
        listOf(matched, wrongHost).forEach { certificate ->
            check(certificate.basicConstraints == -1 && certificate.issuerX500Principal == root.subjectX500Principal)
            check(usages(certificate) == setOf(0, 2) && certificate.extendedKeyUsage == listOf("1.3.6.1.5.5.7.3.1"))
            certificate.verify(root.publicKey)
            check(runCatching { certificate.verify(wrongRoot.publicKey) }.exceptionOrNull() is SignatureException)
        }
        names(matched, "127.0.0.1", "localhost")
        names(wrongHost, "127.0.0.2", "wrong-host.synthetic.invalid")
    }

    private fun usages(certificate: X509Certificate): Set<Int> = certificate.keyUsage.withIndex().filter { it.value }.map { it.index }.toSet()

    private fun names(certificate: X509Certificate, ip: String, dns: String) {
        val alternatives = certificate.subjectAlternativeNames
        check(alternatives.size == 2 && alternatives.any { it == listOf(7, ip) } && alternatives.any { it == listOf(2, dns) })
        check(certificate.subjectX500Principal.name == "CN=$dns,O=Kira Synthetic Tests")
    }
}
