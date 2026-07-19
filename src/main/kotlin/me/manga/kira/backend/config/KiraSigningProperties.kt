package me.manga.kira.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/** Ed25519 document-signing configuration. Private material is environment-only. */
@Validated
@ConfigurationProperties(prefix = "kira.signing")
data class KiraSigningProperties(
    val enabled: Boolean = false,
    val activeKeyId: String? = null,
    val privateKey: String? = null,
    val verificationKeys: List<VerificationKey> = emptyList(),
) {
    data class VerificationKey(val keyId: String = "", val publicKey: String = "")
}
