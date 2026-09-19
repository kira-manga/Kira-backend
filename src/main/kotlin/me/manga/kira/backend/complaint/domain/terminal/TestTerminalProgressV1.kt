package me.manga.kira.backend.complaint.domain.terminal

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The complete prefix, not the role of one seal or only the dedicated terminal epoch. */
@Serializable
internal enum class TestTerminalDenialPrefixV1 { ORDINARY, SEAL_TERMINAL }

/**
 * Bounded observations intended for the existing SEALED run blob, never a partial DenialSet.
 * No factory here proves SEALED state, a current fence, source completeness, evidence or recycle eligibility.
 */
@Serializable
@Suppress("LongParameterList") // Required flattened wire context; no generic document/discriminator registry.
internal class TestTerminalProgressV1 private constructor(
    val schemaVersion: Int,
    val documentKind: String,
    val dataScopeId: String,
    val activationCatalogGeneration: Long,
    val activationCatalogSha256: String,
    val configurationSha256: String,
    val terminalEncodingSha256: String,
    @SerialName("completedCuts") private val storedCuts: List<TestTerminalCompletedCutV1>,
    @SerialName("installationReads") private val storedReads: List<TestTerminalInstallationReadV1>,
) {
    init {
        requireTestTerminal(schemaVersion == 1 && documentKind == DOCUMENT_KIND)
        context()
        requireTestTerminal(storedCuts.size <= MAX_COMPLETED_CUTS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(storedReads.size <= MAX_INSTALLATION_READS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(storedReads.isEmpty() || storedReads.size == MAX_INSTALLATION_READS)
        validateCuts()
        validateReads()
    }

    fun context(): TestTerminalRunContextV1 = TestTerminalRunContextV1(
        dataScopeId, activationCatalogGeneration, activationCatalogSha256, configurationSha256, terminalEncodingSha256,
    )

    fun completedCuts(): List<TestTerminalCompletedCutV1> = storedCuts.toList()
    fun installationReads(): List<TestTerminalInstallationReadV1> = storedReads.toList()

    private fun validateCuts() {
        val writers = HashSet<String>()
        val scanIds = HashSet<String>()
        var previous: TestTerminalCompletedCutV1? = null
        storedCuts.forEach { cut ->
            writers.add(cut.writerGeneration)
            requireTestTerminal(writers.size <= TestTerminalProfileV1.MAX_DENIAL_RANGES, TestTerminalFailureV1.LIMIT_EXCEEDED)
            requireTestTerminal(scanIds.add(cut.scanId))
            previous?.let { prior ->
                // Canonical lowercase UUID text has PostgreSQL's unsigned byte order, unlike UUID.compareTo.
                val order = prior.writerGeneration.compareTo(cut.writerGeneration)
                requireTestTerminal(order < 0 || (
                    order == 0 && prior.prefixKind == TestTerminalDenialPrefixV1.ORDINARY &&
                        cut.prefixKind == TestTerminalDenialPrefixV1.SEAL_TERMINAL
                    ))
                if (order == 0) requireTestTerminal(prior.denial.roleId != cut.denial.roleId)
            }
            previous = cut
        }
    }

    private fun validateReads() {
        if (storedReads.isEmpty()) return
        val first = storedReads[0]
        val second = storedReads[1]
        requireTestTerminal(second.startedAtEpochSecond >= first.completedAtEpochSecond)
        // Every binding, high-water field, root and count must agree; only observation times may differ.
        requireTestTerminal(first.copy(
            startedAtEpochSecond = second.startedAtEpochSecond,
            completedAtEpochSecond = second.completedAtEpochSecond,
        ) == second)
    }

    override fun toString(): String = "TestTerminalProgressV1(redacted,observations-only,no-terminal-or-recycle-authority)"

    companion object {
        const val DOCUMENT_KIND = "TEST_TERMINAL_PROGRESS_V1"
        const val MAX_COMPLETED_CUTS = 2 * TestTerminalProfileV1.MAX_DENIAL_RANGES
        const val MAX_INSTALLATION_READS = 2

        /** Conservative actual kcj-1 bytes: 30 complete cuts, two reads, full-width integers/ASCII fields. */
        const val MAX_CANONICAL_BYTES = 51_291

        fun create(
            context: TestTerminalRunContextV1,
            completedCuts: List<TestTerminalCompletedCutV1>,
            installationReads: List<TestTerminalInstallationReadV1>,
        ): TestTerminalProgressV1 = TestTerminalProgressV1(
            1, DOCUMENT_KIND, context.dataScopeId, context.activationCatalogGeneration, context.activationCatalogSha256,
            context.configurationSha256, context.terminalEncodingSha256,
            TestTerminalSyntaxV1.snapshot(completedCuts, MAX_COMPLETED_CUTS),
            TestTerminalSyntaxV1.snapshot(installationReads, MAX_INSTALLATION_READS),
        )
    }
}

/**
 * The two original COMPLETE scan rows share scanId and these bindings; their pass numbers are 1 and 2.
 * framedByteCount declares the equal LP32 totals, NOT inventory byteCount, raw-proof size or a row price.
 * The real producer must authenticate the pair/range/evidence before preserving it and deleting any row.
 */
@Serializable
@Suppress("LongParameterList")
internal data class TestTerminalCompletedCutV1(
    val writerGeneration: String,
    val prefixKind: TestTerminalDenialPrefixV1,
    val epochStartInclusive: Long,
    val epochEndInclusive: Long,
    val scanId: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val desiredGeneration: Long,
    val fencingToken: Long,
    val framedByteCount: Long,
    val denial: TestTerminalDenialCutV1,
) {
    init {
        TestTerminalSyntaxV1.uuid(writerGeneration)
        TestTerminalSyntaxV1.uuid(scanId)
        TestTerminalSyntaxV1.uuid(databaseIdentity)
        TestTerminalSyntaxV1.uuid(restoreIdentity)
        requireTestTerminal(epochStartInclusive > 0 && epochEndInclusive >= epochStartInclusive)
        requireTestTerminal(desiredGeneration > 0 && fencingToken > 0 && framedByteCount > 0)
    }

    override fun toString(): String = "TestTerminalCompletedCutV1(redacted,declaration-only,no-scan-or-denial-proof)"
}

/**
 * One complete supplied source-read observation, not a DB read or a checkpoint.
 * chunkSetSha256 names the deterministic plaintext grouping, never the final object/version chunksSha256.
 * The existing installation fold supplies the common context-bound root after comparing both passes.
 */
@Serializable
@Suppress("LongParameterList")
internal data class TestTerminalInstallationReadV1(
    val databaseIdentity: String,
    val restoreIdentity: String,
    val desiredGeneration: Long,
    val fencingToken: Long,
    val startedAtEpochSecond: Long,
    val completedAtEpochSecond: Long,
    val sourceHighWater: TestTerminalSourceHighWaterV1,
    val installationCount: Long,
    val retiredCount: Long,
    val deletedCount: Long,
    val chunkCount: Int,
    val installationsSha256: String,
    val installationsFramedBytes: Long,
    val chunkSetSha256: String,
    val chunkSetFramedBytes: Long,
) {
    init {
        TestTerminalSyntaxV1.uuid(databaseIdentity)
        TestTerminalSyntaxV1.uuid(restoreIdentity)
        requireTestTerminal(desiredGeneration > 0 && fencingToken > 0)
        requireTestTerminal(startedAtEpochSecond >= 0 && completedAtEpochSecond >= startedAtEpochSecond)
        requireTestTerminal(installationCount == sourceHighWater.reservationCount)
        requireTestTerminal(TestTerminalSyntaxV1.add(retiredCount, deletedCount) == installationCount)
        requireTestTerminal(chunkCount == TestTerminalSyntaxV1.chunkCount(installationCount))
        TestTerminalSyntaxV1.hash(installationsSha256)
        TestTerminalSyntaxV1.hash(chunkSetSha256)
        requireTestTerminal(installationsFramedBytes > 0 && chunkSetFramedBytes > 0)
    }

    override fun toString(): String = "TestTerminalInstallationReadV1(redacted,declaration-only,no-source-read-proof)"
}

/**
 * Fixed source: every complaint_installation_ids row for the run, including no-credential reservations.
 * Order is canonical lowercase UUID text / unsigned bytes, matching PostgreSQL, not Java UUID.compareTo.
 * The greatest UUID is only a sorted full-set end key, NOT a temporal watermark or resume cursor.
 * sourceSha256 declares the complete membership/raw-state fold under the read's barrier binding.
 */
@Serializable
internal data class TestTerminalSourceHighWaterV1(
    val enrolledCount: Long,
    val reservationCount: Long,
    val greatestReservationId: String,
    val sourceSha256: String,
    val framedByteCount: Long,
) {
    init {
        requireTestTerminal(enrolledCount in 0..TestTerminalProfileV1.MAX_INSTALLATIONS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(reservationCount in 0..TestTerminalProfileV1.MAX_INSTALLATIONS, TestTerminalFailureV1.LIMIT_EXCEEDED)
        requireTestTerminal(enrolledCount == reservationCount)
        if (reservationCount == 0L) {
            requireTestTerminal(greatestReservationId.isEmpty())
        } else {
            TestTerminalSyntaxV1.uuid(greatestReservationId)
        }
        TestTerminalSyntaxV1.hash(sourceSha256)
        requireTestTerminal(framedByteCount > 0)
    }

    override fun toString(): String = "TestTerminalSourceHighWaterV1(redacted,declaration-only,not-a-temporal-watermark)"
}
