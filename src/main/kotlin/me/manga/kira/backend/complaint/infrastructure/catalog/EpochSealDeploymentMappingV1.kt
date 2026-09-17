package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.complaint.domain.catalog.InitialCatalogPrincipalV1
import me.manga.kira.backend.complaint.domain.catalog.InitialEventRoleV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar

/** Exact commercial IAM identity declaration. Names/references alone never establish effective principal isolation. */
internal class EpochSealProviderPrincipalV1 private constructor(val kind: Kind, val accountId: String, val arn: String, val stableId: String) {
    private val name = arn.substringAfterLast('/')

    /** Reject obvious aliases, including alternate paths or a recreated same-named principal. Not policy qualification. */
    internal fun aliases(other: EpochSealProviderPrincipalV1): Boolean = arn == other.arn || stableId == other.stableId || sameAccountName(other)

    private fun sameAccountName(other: EpochSealProviderPrincipalV1): Boolean =
        accountId == other.accountId && kind == other.kind && name.equals(other.name, ignoreCase = true)

    internal fun callerArn(sessionName: String?): String {
        requireSessionName(sessionName)
        return if (kind == Kind.ROLE) "arn:aws:sts::$accountId:assumed-role/$name/$sessionName" else arn
    }

    internal fun callerUserId(sessionName: String?): String {
        requireSessionName(sessionName)
        return if (kind == Kind.ROLE) "$stableId:$sessionName" else stableId
    }

    private fun requireSessionName(sessionName: String?) {
        require(if (kind == Kind.USER) sessionName == null else sessionName != null && SESSION.matches(sessionName)) {
            INVALID_EPOCH_SEAL_ACQUISITION
        }
    }

    override fun toString(): String = "EpochSealProviderPrincipalV1(declared-stable-identity,redacted,no-policy-proof)"

    enum class Kind { ROLE, USER }

    companion object {
        fun role(arn: String, roleId: String): EpochSealProviderPrincipalV1 = create(Kind.ROLE, arn, roleId, ROLE, ROLE_ID)

        fun user(arn: String, userId: String): EpochSealProviderPrincipalV1 = create(Kind.USER, arn, userId, USER, USER_ID)

        private fun create(kind: Kind, arn: String, id: String, pattern: Regex, idPattern: Regex): EpochSealProviderPrincipalV1 {
            require(arn.length in 1..512 && id.length == 21) { INVALID_EPOCH_SEAL_ACQUISITION }
            val match = pattern.matchEntire(arn)
            require(match != null && idPattern.matches(id)) { INVALID_EPOCH_SEAL_ACQUISITION }
            return EpochSealProviderPrincipalV1(kind, checkNotNull(match).groupValues[1], arn, id)
        }

        private val ROLE = Regex("arn:aws:iam::([0-9]{12}):role/(?:[A-Za-z0-9+=,.@_-]+/)*[A-Za-z0-9+=,.@_-]{1,64}")
        private val USER = Regex("arn:aws:iam::([0-9]{12}):user/(?:[A-Za-z0-9+=,.@_-]+/)*[A-Za-z0-9+=,.@_-]{1,64}")
        private val ROLE_ID = Regex("AROA[A-Z0-9]{17}")
        private val USER_ID = Regex("AIDA[A-Z0-9]{17}")
        private val SESSION = Regex("[A-Za-z0-9+=,.@_-]{2,64}")
    }
}

/** J inventory references mapped by the independent deployment channel, not inferred from string shapes. */
internal class EpochSealEventPrincipalV1(val reference: InitialEventRoleV1, val principal: EpochSealProviderPrincipalV1) {
    init {
        require(OfflineBootstrapGrammar.referenceId(reference.roleId) && OfflineBootstrapGrammar.referenceId(reference.credentialId)) {
            INVALID_EPOCH_SEAL_ACQUISITION
        }
        requireEpochSealPolicyReference(reference.policy)
    }

    override fun toString(): String = "EpochSealEventPrincipalV1(declared-mapping,redacted)"
}

internal class EpochSealCatalogPrincipalV1(val reference: InitialCatalogPrincipalV1, val principal: EpochSealProviderPrincipalV1) {
    init {
        require(OfflineBootstrapGrammar.referenceId(reference.principalId)) { INVALID_EPOCH_SEAL_ACQUISITION }
        requireEpochSealPolicyReference(reference.policy)
    }

    override fun toString(): String = "EpochSealCatalogPrincipalV1(declared-mapping,redacted)"
}

/** Stable source/origin inventory only. The version is NOT an observed AWS session or an IAM policy-version ID. */
internal class EpochSealBootstrapOriginV1(
    val originId: String,
    val version: Long,
    val credentialReferenceId: String,
    val principal: EpochSealProviderPrincipalV1,
) {
    init {
        require(OfflineBootstrapGrammar.referenceId(originId) && version > 0 && OfflineBootstrapGrammar.referenceId(credentialReferenceId)) {
            INVALID_EPOCH_SEAL_ACQUISITION
        }
    }

    override fun toString(): String = "EpochSealBootstrapOriginV1(declared-origin,redacted,no-credential-provenance-proof)"
}

/**
 * Independently administered cold deployment inputs. Construction validates consistency only;
 * neither these references/hashes nor D/G1 naming them proves their installation or effective policies.
 * No new installation receipt, issuer, revocation protocol or caller 'verified' flag is introduced.
 */
internal class EpochSealDeploymentMappingV1(
    val ordinary: EpochSealEventPrincipalV1,
    val sealTerminal: EpochSealEventPrincipalV1,
    val recovery: EpochSealEventPrincipalV1,
    val catalogPut: EpochSealCatalogPrincipalV1,
    val catalogSign: EpochSealCatalogPrincipalV1,
    val bootstrap: EpochSealBootstrapOriginV1,
    val installedPolicyBundle: InitialPolicyReferenceV1,
) {
    init {
        require(sealTerminal.principal.kind == EpochSealProviderPrincipalV1.Kind.ROLE) { INVALID_EPOCH_SEAL_ACQUISITION }
        requireEpochSealPolicyReference(installedPolicyBundle)
        val events = listOf(ordinary, sealTerminal, recovery)
        val roles = events.map { it.reference.roleId } + catalogPut.reference.principalId + catalogSign.reference.principalId
        val credentials = events.map { it.reference.credentialId } + bootstrap.credentialReferenceId
        require(roles.distinct().size == roles.size && credentials.distinct().size == credentials.size && credentials.none { it in roles }) {
            INVALID_EPOCH_SEAL_ACQUISITION
        }
        val policies = events.map { it.reference.policy.policyId } + catalogPut.reference.policy.policyId + catalogSign.reference.policy.policyId
        require(policies.distinct().size == policies.size) { INVALID_EPOCH_SEAL_ACQUISITION }
        val principals = events.map { it.principal } + catalogPut.principal + catalogSign.principal + bootstrap.principal
        principals.indices.forEach { left ->
            for (right in left + 1 until principals.size) {
                require(!principals[left].aliases(principals[right])) { INVALID_EPOCH_SEAL_ACQUISITION }
            }
        }
    }

    override fun toString(): String = "EpochSealDeploymentMappingV1(independent-input-required,redacted,no-installed-policy-proof)"
}

private fun requireEpochSealPolicyReference(value: InitialPolicyReferenceV1) {
    require(OfflineBootstrapGrammar.referenceId(value.policyId) && value.version > 0 && OfflineBootstrapGrammar.sha256(value.sha256)) {
        INVALID_EPOCH_SEAL_ACQUISITION
    }
}

internal const val INVALID_EPOCH_SEAL_ACQUISITION = "Invalid retained epoch seal acquisition configuration"
