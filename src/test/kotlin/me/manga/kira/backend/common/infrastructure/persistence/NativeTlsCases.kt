package me.manga.kira.backend.common.infrastructure.persistence

internal class NativeSslCase(val label: String, val raw: String?, val expected: String?) {
    override fun toString(): String = label
}

internal class NativeTlsIdentityCase(
    val label: String,
    val cert: String?,
    val key: String?,
    val validWithoutPassword: Boolean = false,
    val validWithPassword: Boolean = false,
) {
    override fun toString(): String = label
}

internal object NativeTlsCases {
    val modes = listOf(
        NativeSslCase("disable", "disable", "DISABLE"),
        NativeSslCase("allow", "allow", "ALLOW"),
        NativeSslCase("prefer", "prefer", "PREFER"),
        NativeSslCase("require", "require", "REQUIRE"),
        NativeSslCase("verify-ca", "verify-ca", "VERIFY_CA"),
        NativeSslCase("verify-full", "verify-full", "VERIFY_FULL"),
        NativeSslCase("uppercase-disable", "DISABLE", "DISABLE"),
        NativeSslCase("uppercase-allow", "ALLOW", "ALLOW"),
        NativeSslCase("uppercase-prefer", "PREFER", "PREFER"),
        NativeSslCase("uppercase-require", "REQUIRE", "REQUIRE"),
        NativeSslCase("uppercase-verify-ca", "VERIFY-CA", "VERIFY_CA"),
        NativeSslCase("uppercase-verify-full", "VERIFY-FULL", "VERIFY_FULL"),
        NativeSslCase("dotless-i", "ver\u0131fy-ca", "VERIFY_CA"),
        NativeSslCase("dotted-i", "d\u0130sable", "DISABLE"),
        NativeSslCase("empty", "", null),
        NativeSslCase("leading-space", " disable", null),
        NativeSslCase("trailing-space", "verify-full ", null),
        NativeSslCase("tab", "\tprefer", null),
        NativeSslCase("nul", "disable\u0000", null),
        NativeSslCase("nbsp", "\u00a0require", null),
        NativeSslCase("unknown", "synthetic-mode", null),
        NativeSslCase("underscore", "verify_full", null),
        NativeSslCase("abbreviation", "verify", null),
    )

    val fallback = listOf(
        NativeSslCase("absent", null, "PREFER"),
        NativeSslCase("empty", "", "VERIFY_FULL"),
        NativeSslCase("true", "true", "VERIFY_FULL"),
        NativeSslCase("uppercase-true", "TRUE", "VERIFY_FULL"),
        NativeSslCase("mixed-true", "TrUe", "VERIFY_FULL"),
        NativeSslCase("false", "false", "PREFER"),
        NativeSslCase("uppercase-false", "FALSE", "PREFER"),
        NativeSslCase("padded-true", " true ", "PREFER"),
        NativeSslCase("leading-space", " true", "PREFER"),
        NativeSslCase("trailing-space", "true ", "PREFER"),
        NativeSslCase("number-one", "1", "PREFER"),
        NativeSslCase("number-zero", "0", "PREFER"),
        NativeSslCase("yes", "yes", "PREFER"),
        NativeSslCase("blank", " ", "PREFER"),
        NativeSslCase("nul-true", "\u0000true", "PREFER"),
        NativeSslCase("true-nul", "true\u0000", "PREFER"),
        NativeSslCase("unknown", "synthetic-boolean", "PREFER"),
    )

    val identities = listOf(
        NativeTlsIdentityCase("both-absent", null, null),
        NativeTlsIdentityCase("absent-cert-empty-key", null, ""),
        NativeTlsIdentityCase("empty-cert-absent-key", "", null),
        NativeTlsIdentityCase("explicit-no-key", "", "", validWithoutPassword = true, validWithPassword = true),
        NativeTlsIdentityCase("explicit-pkcs8", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.pk8", validWithPassword = true),
        NativeTlsIdentityCase("empty-cert-key", "", "/synthetic-unopened/key.pk8"),
        NativeTlsIdentityCase("cert-empty-key", "/synthetic-unopened/cert.crt", ""),
        NativeTlsIdentityCase("absent-cert-key", null, "/synthetic-unopened/key.pk8"),
        NativeTlsIdentityCase("cert-absent-key", "/synthetic-unopened/cert.crt", null),
        NativeTlsIdentityCase("relative-cert", "synthetic-cert.crt", "/synthetic-unopened/key.pk8"),
        NativeTlsIdentityCase("relative-key", "/synthetic-unopened/cert.crt", "synthetic-key.pk8"),
        NativeTlsIdentityCase("nul-cert", "/synthetic\u0000/cert.crt", "/synthetic-unopened/key.pk8"),
        NativeTlsIdentityCase("nul-key", "/synthetic-unopened/cert.crt", "/synthetic\u0000/key.pk8"),
        NativeTlsIdentityCase("pkcs12", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.p12"),
        NativeTlsIdentityCase("pfx", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.pfx"),
        NativeTlsIdentityCase("pem", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.pem"),
        NativeTlsIdentityCase("key", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.key"),
        NativeTlsIdentityCase("uppercase-suffix", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.PK8"),
        NativeTlsIdentityCase("extra-suffix", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key.pk8.other"),
        NativeTlsIdentityCase("no-suffix", "/synthetic-unopened/cert.crt", "/synthetic-unopened/key"),
        NativeTlsIdentityCase("syntactic-posix-root-cert", "/", "/synthetic-unopened/key.pk8", validWithPassword = true),
    )
}
