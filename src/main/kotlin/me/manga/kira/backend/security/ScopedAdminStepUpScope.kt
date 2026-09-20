package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhasePath
import java.time.Instant
import java.util.UUID

/** Internal grant identity. The HTTP allowlist defaults to SOURCE; complaint admission stays separately closed. */
internal enum class ScopedAdminStepUpScope(val storedName: String) {
    SOURCE("source-admin-mutation"),
    COMPLAINT("complaint-moderation-mutation"),
    ;

    internal val snapshotPath: PersistencePhasePath
        get() = when (this) {
            SOURCE -> PersistencePhasePath.SOURCE_STEP_UP_SNAPSHOT
            COMPLAINT -> PersistencePhasePath.COMPLAINT_STEP_UP_SNAPSHOT
        }

    internal val issuancePath: PersistencePhasePath
        get() = when (this) {
            SOURCE -> PersistencePhasePath.SOURCE_STEP_UP_ISSUANCE
            COMPLAINT -> PersistencePhasePath.COMPLAINT_STEP_UP_ISSUANCE
        }
}

/** The plaintext proof is returned once after committed, released completion; never a generated data-class diagnostic. */
internal class IssuedScopedAdminStepUp(val token: String, val expiresAt: Instant, val scope: ScopedAdminStepUpScope, val grantId: UUID) {
    override fun toString(): String = "IssuedScopedAdminStepUp(redacted)"
}
