package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.time.Instant
import java.util.Collections

/** One bounded recurrent comparison format. The original initial-empty format is not widened. */
internal class TestActiveRecurrentCheckpointDocumentV1(
    val identity: TestActiveCheckpointHistoryV1.Identity,
    val fencingToken: Long,
    val scanId: String,
    val predecessorCheckpointSha256: String,
    val sealHistorySha256: String,
    ranges: List<Range>,
    val first: Pass,
    val second: Pass,
) {
    val ranges: List<Range> = Collections.unmodifiableList(ranges.toList())
    val cutoffEpoch: Long get() = this.ranges.last().epochEnd
    val sealOperationToken: String get() = this.ranges.last().operationToken
    val startedAt: Instant get() = first.startedAt
    val completedAt: Instant get() = second.completedAt
    val objectCount: Long get() = second.objectCount
    val byteCount: Long get() = second.byteCount

    init {
        require(this.ranges.size in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS)
        require(fencingToken > 0 && OfflineBootstrapGrammar.uuidV4(scanId) && scanId == sealOperationToken)
        require(TestActiveRecurrentJsonV1.hash(predecessorCheckpointSha256) && TestActiveRecurrentJsonV1.hash(sealHistorySha256))
        this.ranges.forEachIndexed { i, range ->
            require(range.ordinal == i + 1 && range.epochStart == if (i == 0) 1L else this.ranges[i - 1].epochEnd + 1)
        }
        require(this.ranges.first().epochEnd == 1L && this.ranges.map { it.operationToken }.distinct().size == this.ranges.size)
        require(second.startedAt >= first.completedAt && first.sameInventory(second))
        require(objectCount == this.ranges.fold(0L) { count, range -> Math.addExact(count, range.eventCount) })
    }

    fun requireHistory(history: TestActiveCheckpointHistoryV1) {
        require(identity.same(history.first.identity) && sealHistorySha256 == history.rootSha256 && ranges.size == history.entries.size)
        ranges.zip(history.entries).forEach { (range, entry) -> require(range.json() == Range.from(entry).json()) }
        require(fencingToken > history.last.preparingFencingToken && startedAt >= history.last.verifiedAt)
    }

    fun canonicalBytes(): ByteArray = TestActiveRecurrentJsonV1.bytes(buildJsonObject {
        put("kind", "kira-complaint-reconciliation-checkpoint"); put("schemaVersion", 1); put("canonicalizerId", "kcj-1"); put("profile", PROFILE)
        put("identity", identity.json()); put("fencingToken", fencingToken); put("scanId", scanId)
        put("cutoffEpoch", cutoffEpoch); put("sealOperationToken", sealOperationToken)
        put("predecessorCheckpointSha256", predecessorCheckpointSha256); put("sealHistorySha256", sealHistorySha256)
        put("ranges", buildJsonArray { ranges.forEach { add(it.json()) } })
        put("passes", buildJsonArray { add(first.json(1)); add(second.json(2)) })
        put("startedAt", startedAt.toString()); put("completedAt", completedAt.toString())
        put("objectCount", objectCount); put("byteCount", byteCount); put("result", "SUCCESS")
    }).also { require(it.size in 1..TestActiveRecurrentStorageV1.MAX_CHECKPOINT_BYTES) }

    class Range(val ordinal: Int, val source: TestActiveCheckpointHistoryV1.Source, val operationToken: String,
        val epochStart: Long, val epochEnd: Long, val historyEntrySha256: String, val sealCanonicalSha256: String,
        val manifestSha256: String, val eventCount: Long, val manifestFramedBytes: Long) {
        init {
            require(ordinal in 1..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS && (ordinal == 1) == (source == TestActiveCheckpointHistoryV1.Source.V26_INITIAL))
            require(OfflineBootstrapGrammar.uuidV4(operationToken) && epochStart > 0 && epochEnd in epochStart until Long.MAX_VALUE)
            require(listOf(historyEntrySha256, sealCanonicalSha256, manifestSha256).all(TestActiveRecurrentJsonV1::hash) && eventCount >= 0 && manifestFramedBytes > 0)
        }
        fun json(): JsonObject = buildJsonObject {
            put("ordinal", ordinal); put("source", source.name); put("operationToken", operationToken)
            put("epochStart", epochStart); put("epochEnd", epochEnd); put("historyEntrySha256", historyEntrySha256)
            put("sealCanonicalSha256", sealCanonicalSha256); put("manifestSha256", manifestSha256)
            put("eventCount", eventCount); put("manifestFramedBytes", manifestFramedBytes)
        }
        override fun toString(): String = "RecurrentCheckpointRange(comparison-only,redacted)"
        companion object {
            fun from(entry: TestActiveCheckpointHistoryV1.Entry): Range = Range(entry.ordinal, entry.source, entry.operationToken,
                entry.epochStart, entry.epochEnd, entry.sha256, entry.canonicalSha256, entry.manifestSha256, entry.eventCount, entry.manifestFramedBytes)
            fun parse(v: JsonObject): Range = with(TestActiveRecurrentJsonV1) {
                Range(integer(v, "ordinal"), TestActiveCheckpointHistoryV1.Source.valueOf(string(v, "source")), string(v, "operationToken"),
                    long(v, "epochStart"), long(v, "epochEnd"), string(v, "historyEntrySha256"), string(v, "sealCanonicalSha256"),
                    string(v, "manifestSha256"), long(v, "eventCount"), long(v, "manifestFramedBytes")).also { require(it.json() == v) }
            }
        }
    }

    class Pass(val startedAt: Instant, val completedAt: Instant, val manifestSha256: String,
        val applicationCoverageSha256: String, val objectCount: Long, val byteCount: Long) {
        init {
            require(TestActiveRecurrentJsonV1.time(startedAt) && TestActiveRecurrentJsonV1.time(completedAt) && completedAt >= startedAt)
            require(listOf(manifestSha256, applicationCoverageSha256).all(TestActiveRecurrentJsonV1::hash) && objectCount >= 0 && byteCount >= 0)
        }
        fun sameInventory(other: Pass): Boolean = manifestSha256 == other.manifestSha256 && applicationCoverageSha256 == other.applicationCoverageSha256 &&
            objectCount == other.objectCount && byteCount == other.byteCount
        fun json(number: Int): JsonObject = buildJsonObject {
            require(number in 1..2); put("pass", number); put("startedAt", startedAt.toString()); put("completedAt", completedAt.toString())
            put("manifestSha256", manifestSha256); put("applicationCoverageSha256", applicationCoverageSha256)
            put("objectCount", objectCount); put("byteCount", byteCount)
        }
        override fun toString(): String = "RecurrentCheckpointPass(comparison-only)"
        companion object {
            fun parse(v: JsonObject, number: Int): Pass = with(TestActiveRecurrentJsonV1) {
                require(integer(v, "pass") == number)
                Pass(instant(v, "startedAt"), instant(v, "completedAt"), string(v, "manifestSha256"), string(v, "applicationCoverageSha256"),
                    long(v, "objectCount"), long(v, "byteCount")).also { require(it.json(number) == v) }
            }
        }
    }
    override fun toString(): String = "TestActiveRecurrentCheckpointDocumentV1(syntax-only,no-authority)"
    companion object {
        const val PROFILE = "TEST_ACTIVE_RECURRENT_CHECKPOINT_V1"
        const val MAX_REFRESH_MILLIS = 900_000L
        const val MAX_GATE_AGE_MILLIS = 1_200_000L
        fun parse(bytes: ByteArray): TestActiveRecurrentCheckpointDocumentV1 = with(TestActiveRecurrentJsonV1) {
            val v = objectBytes(bytes, TestActiveRecurrentStorageV1.MAX_CHECKPOINT_BYTES)
            require(string(v, "kind") == "kira-complaint-reconciliation-checkpoint" && integer(v, "schemaVersion") == 1 &&
                string(v, "canonicalizerId") == "kcj-1" && string(v, "profile") == PROFILE && string(v, "result") == "SUCCESS")
            val ranges = array(v, "ranges").also { require(it.size in 2..TestActiveRecurrentStorageV1.MAX_ACTIVE_SEALS) }
                .map { Range.parse(it as? JsonObject ?: error("Invalid checkpoint range")) }
            val passes = array(v, "passes").also { require(it.size == 2) }
            TestActiveRecurrentCheckpointDocumentV1(TestActiveCheckpointHistoryV1.Identity.parse(obj(v, "identity")), long(v, "fencingToken"),
                string(v, "scanId"), string(v, "predecessorCheckpointSha256"), string(v, "sealHistorySha256"), ranges,
                Pass.parse(passes[0] as JsonObject, 1), Pass.parse(passes[1] as JsonObject, 2)).also { require(it.canonicalBytes().contentEquals(bytes)) }
        }
    }
}
