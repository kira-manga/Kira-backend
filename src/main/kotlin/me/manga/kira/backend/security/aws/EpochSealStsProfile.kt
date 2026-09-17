package me.manga.kira.backend.security.aws

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import me.manga.kira.backend.security.EpochSealAttemptV1
import java.util.Base64

/** Finite lower-protocol settings, not a hard native/DNS completion guarantee or a DB lease. */
internal class AwsEpochSealStsLimits(
    val requestTimeoutMillis: Int = 2500,
    val connectTimeoutMillis: Int = 1000,
    val readTimeoutMillis: Int = 1000,
    val maxResponseBytes: Int = 32 * 1024,
    val clockUncertaintyMillis: Long = 1000,
) {
    init {
        requireEpochSealSts(requestTimeoutMillis in 1..2500, EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(connectTimeoutMillis in 1..requestTimeoutMillis, EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(readTimeoutMillis in 1..requestTimeoutMillis, EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(maxResponseBytes in 1..32 * 1024, EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(clockUncertaintyMillis in 0..60_000, EpochSealStsFailure.INVALID_INPUT)
    }

    override fun toString(): String = "AwsEpochSealStsLimits(bounded,no-authority)"
}

/**
 * Exact lower-protocol expectations, NOT their independent installation/origin/policy qualification.
 * J's opaque inventory reference strings are deliberately not interpreted as ARNs or stable RoleIds.
 */
internal class AwsEpochSealStsBinding(
    val sourceAccountId: String,
    val sourceArn: String,
    val sourceUserId: String,
    val targetRoleArn: String,
    val targetRoleId: String,
) {
    val targetAccountId: String
    private val targetRoleName: String

    init {
        requireEpochSealSts(sourceAccountId.matches(ACCOUNT), EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(sourceArn.length <= 512 && sourceUserId.length <= 128, EpochSealStsFailure.INVALID_INPUT)
        val role = TARGET_ROLE.matchEntire(targetRoleArn.take(513))
        requireEpochSealSts(targetRoleArn.length <= 512 && role != null && ROLE_ID.matches(targetRoleId), EpochSealStsFailure.INVALID_INPUT)
        targetAccountId = checkNotNull(role).groupValues[1]
        targetRoleName = targetRoleArn.substringAfterLast('/')
        val sourceRole = SOURCE_ROLE.matchEntire(sourceArn)
        val sourceUser = SOURCE_USER.matchEntire(sourceArn)
        if (sourceRole != null) {
            requireEpochSealSts(sourceRole.groupValues[1] == sourceAccountId, EpochSealStsFailure.INVALID_INPUT)
            requireEpochSealSts(
                sourceUserId.substringBefore(':').matches(ROLE_ID) && sourceUserId.substringAfter(':', "") == sourceRole.groupValues[3],
                EpochSealStsFailure.INVALID_INPUT,
            )
            requireEpochSealSts(
                sourceAccountId != targetAccountId || sourceUserId.substringBefore(':') != targetRoleId,
                EpochSealStsFailure.INVALID_INPUT,
            )
        } else {
            requireEpochSealSts(sourceUser != null && sourceUser.groupValues[1] == sourceAccountId, EpochSealStsFailure.INVALID_INPUT)
            requireEpochSealSts(sourceUserId.matches(USER_ID), EpochSealStsFailure.INVALID_INPUT)
        }
    }

    fun targetArn(sessionName: String): String = "arn:aws:sts::$targetAccountId:assumed-role/$targetRoleName/$sessionName"

    override fun toString(): String = "AwsEpochSealStsBinding(declared-expectations,redacted,no-policy-proof)"

    private companion object {
        val ACCOUNT = Regex("[0-9]{12}")
        val ROLE_ID = Regex("AROA[A-Z0-9]{17}")
        val USER_ID = Regex("AIDA[A-Z0-9]{17}")
        val TARGET_ROLE = Regex("arn:aws:iam::([0-9]{12}):role/(?:[A-Za-z0-9+=,.@_-]+/)*[A-Za-z0-9+=,.@_-]{1,64}")
        val SOURCE_ROLE = Regex("arn:aws:sts::([0-9]{12}):assumed-role/([A-Za-z0-9+=,.@_-]{1,64})/([A-Za-z0-9+=,.@_-]{2,64})")
        val SOURCE_USER = Regex("arn:aws:iam::([0-9]{12}):user/(?:[A-Za-z0-9+=,.@_-]+/)*[A-Za-z0-9+=,.@_-]{1,64}")
    }
}

/** Fixed exact-key profile. Neither valid syntax nor a supplied key proves a committed canonical intent. */
internal object EpochSealStsPolicy {
    const val SESSION_SECONDS = 900
    const val MAX_POLICY_CHARS = 2048

    fun forKey(journal: ComplaintJournalConfigurationV1, key: String): String {
        val declaration = journal.declaration()
        val writer = declaration.writer.generationId
        val prefix = "${OfflineBootstrapGrammar.sealTerminalPrefix(writer)}writer/$writer/epoch/"
        requireEpochSealSts(key.length in 1..1024 && key.startsWith(prefix), EpochSealStsFailure.INVALID_INPUT)
        val parts = key.removePrefix(prefix).split('/')
        requireEpochSealSts(parts.size == 3, EpochSealStsFailure.INVALID_INPUT)
        val epoch = parts[0].toLongOrNull()
        requireEpochSealSts(epoch != null && epoch > 0 && epoch.toString().padStart(19, '0') == parts[0], EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(parts[1] in declaration.routing.keys.map { it.keyId }, EpochSealStsFailure.INVALID_INPUT)
        requireEpochSealSts(parts[2].length == 43 && parts[2].all { it in URL_ALPHABET }, EpochSealStsFailure.INVALID_INPUT)
        val decoded = Base64.getUrlDecoder().decode(parts[2])
        try {
            requireEpochSealSts(
                decoded.size == 32 && Base64.getUrlEncoder().withoutPadding().encodeToString(decoded) == parts[2],
                EpochSealStsFailure.INVALID_INPUT,
            )
        } finally {
            decoded.fill(0)
        }
        val bucket = "arn:aws:s3:::${declaration.journalLocation.bucket}"
        val objectArn = "$bucket/$key"
        val kms = declaration.encryption.keyArn
        // The current KMS context is one opaque encoded value. No decoded field is invented as an IAM condition.
        val policy = buildString {
            append("""{"Version":"2012-10-17","Statement":[{"Effect":"Deny","NotAction":[$OBJECT_AND_KMS_ACTIONS,""")
            append(""""s3:ListBucketVersions","sts:GetCallerIdentity"],"Resource":"*"},""")
            append("""{"Effect":"Deny","Action":["s3:*","kms:*"],"NotResource":[${quote(bucket)},${quote(objectArn)},${quote(kms)}]},""")
            append("""{"Effect":"Deny","Action":"s3:ListBucketVersions","Resource":${quote(bucket)},""")
            append(""""Condition":{"StringNotEqualsIfExists":{"s3:prefix":${quote(key)}}}},""")
            append("""{"Effect":"Allow","Action":[$OBJECT_AND_KMS_ACTIONS],"Resource":[${quote(objectArn)},${quote(kms)}]},""")
            append("""{"Effect":"Allow","Action":"s3:ListBucketVersions","Resource":${quote(bucket)},""")
            append(""""Condition":{"StringEquals":{"s3:prefix":${quote(key)}}}}]}""")
        }
        // Never strip statements, substitute wildcards or retry with a wider policy on plaintext/packed overflow.
        requireEpochSealSts(policy.length <= MAX_POLICY_CHARS, EpochSealStsFailure.INVALID_INPUT)
        return policy
    }

    private fun quote(value: String): String {
        requireEpochSealSts(value.all { it in '!'..'~' && it !in "\"\\*?$" }, EpochSealStsFailure.INVALID_INPUT)
        return "\"$value\""
    }

    private const val OBJECT_AND_KMS_ACTIONS = "\"s3:PutObject\",\"s3:PutObjectRetention\",\"s3:GetObjectVersion\",\"s3:GetObjectRetention\"," +
        "\"kms:GenerateDataKey\",\"kms:Decrypt\""
    private const val URL_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"
}

/** A stricter acquisition slice retains, never restarts, the original enclosing seal attempt. */
internal class EpochSealStsAcquisition(val original: EpochSealAttemptV1, private val nanoTime: () -> Long) {
    private val started = nanoTime()
    private var lastElapsed = 0L
    private var failed = false

    @Synchronized
    fun remainingMillis(ceiling: Int): Int {
        requireConnectionFree()
        if (Thread.currentThread().isInterrupted) throw InterruptedException()
        val elapsed = nanoTime() - started
        if (elapsed < lastElapsed || elapsed < 0 || elapsed >= 9_000_000_000L) failed = true
        requireEpochSealSts(!failed, EpochSealStsFailure.DEADLINE_EXHAUSTED)
        lastElapsed = elapsed
        val remaining = ((9_000_000_000L - elapsed) / 1_000_000).toInt()
        requireEpochSealSts(remaining > 0, EpochSealStsFailure.DEADLINE_EXHAUSTED)
        return original.remainingMillis(minOf(ceiling, remaining))
    }

    override fun toString(): String = "EpochSealStsAcquisition(original-budget,no-lease-authority)"
}

internal class EpochSealStsCall(
    val action: String,
    val parameters: Map<String, String>,
    private val acquisition: EpochSealStsAcquisition,
    private val limitMillis: Int,
    private val nanoTime: () -> Long,
) {
    private val started = nanoTime()
    private var lastElapsed = 0L
    private var failed = false

    @Synchronized
    fun remainingMillis(): Int {
        val elapsed = nanoTime() - started
        if (elapsed < lastElapsed || elapsed < 0 || elapsed >= limitMillis * 1_000_000L) failed = true
        requireEpochSealSts(!failed, EpochSealStsFailure.DEADLINE_EXHAUSTED)
        lastElapsed = elapsed
        val remaining = ((limitMillis * 1_000_000L - elapsed) / 1_000_000).toInt()
        requireEpochSealSts(remaining > 0, EpochSealStsFailure.DEADLINE_EXHAUSTED)
        return acquisition.remainingMillis(remaining)
    }

    fun check() {
        remainingMillis()
    }
    override fun toString(): String = "EpochSealStsCall(fixed-query,redacted)"
}
