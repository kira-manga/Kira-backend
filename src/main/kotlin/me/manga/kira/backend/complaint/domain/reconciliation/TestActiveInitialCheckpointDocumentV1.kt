package me.manga.kira.backend.complaint.domain.reconciliation

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.catalog.OfflineBootstrapGrammar
import java.time.Instant

/** Fixed backend-only comparison syntax. Constructing bytes does not prove native work or current authority. */
internal class TestActiveInitialCheckpointDocumentV1(
    val scope: String,
    val desiredGeneration: Long,
    val fencingToken: Long,
    val configurationSha256: String,
    val journalConfigurationSha256: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val catalogGeneration: Long,
    val catalogSha256: String,
    val trustBundleSha256: String,
    val catalogWriterGeneration: String,
    val writerGeneration: String,
    val sealOperationToken: String,
    val sealObjectKey: String,
    val sealObjectVersion: String,
    val sealCanonicalSha256: String,
    val sealCiphertextSha256: String,
    val manifestSha256: String,
    val manifestFramedBytes: Long,
    val first: Pass,
    val second: Pass,
) {
    val startedAt: Instant get() = first.startedAt
    val completedAt: Instant get() = second.completedAt

    init {
        require(CanonicalJson.CANON_VERSION == "kcj-1")
        require(listOf(scope, databaseIdentity, restoreIdentity, catalogWriterGeneration, writerGeneration, sealOperationToken)
            .all(OfflineBootstrapGrammar::uuidV4))
        require(desiredGeneration > 0 && fencingToken > 0 && catalogGeneration in 2..65536)
        require(listOf(configurationSha256, journalConfigurationSha256, catalogSha256, trustBundleSha256,
            sealCanonicalSha256, sealCiphertextSha256, manifestSha256).all { HASH.matches(it) })
        require(sealObjectKey.length in 1..1024 && sealObjectKey.all { it in '!'..'~' })
        require(sealObjectVersion.toByteArray(Charsets.UTF_8).size in 1..1024 && sealObjectVersion != "null" &&
            sealObjectVersion.none { it.code < 32 || it.code == 127 } && manifestFramedBytes > 0)
        require(!second.startedAt.isBefore(first.completedAt))
    }

    fun canonicalBytes(): ByteArray = CanonicalJson.canonicalize(buildJsonObject {
        put("kind", "kira-complaint-reconciliation-checkpoint"); put("schemaVersion", 1)
        put("canonicalizerId", "kcj-1"); put("profile", PROFILE)
        put("dataScopeKind", "TEST"); put("dataScopeId", scope)
        put("desiredGeneration", desiredGeneration); put("fencingToken", fencingToken)
        put("configurationSha256", configurationSha256); put("journalConfigurationSha256", journalConfigurationSha256)
        put("databaseIdentity", databaseIdentity); put("restoreIdentity", restoreIdentity)
        put("catalogGeneration", catalogGeneration); put("catalogSha256", catalogSha256)
        put("trustBundleSha256", trustBundleSha256); put("catalogWriterGeneration", catalogWriterGeneration)
        put("writerGeneration", writerGeneration); put("cutoffEpoch", 1)
        put("sealOperationToken", sealOperationToken); put("sealObjectKey", sealObjectKey); put("sealObjectVersion", sealObjectVersion)
        put("sealCanonicalSha256", sealCanonicalSha256); put("sealCiphertextSha256", sealCiphertextSha256)
        put("scanId", sealOperationToken); put("manifestSha256", manifestSha256); put("manifestFramedBytes", manifestFramedBytes)
        put("passes", buildJsonArray {
            listOf(first, second).forEachIndexed { index, pass -> add(buildJsonObject {
                put("pass", index + 1); put("startedAt", pass.startedAt.toString()); put("completedAt", pass.completedAt.toString())
                put("manifestSha256", manifestSha256); put("objectCount", 0); put("byteCount", 0)
            }) }
        })
        put("startedAt", startedAt.toString()); put("completedAt", completedAt.toString())
        put("objectCount", 0); put("byteCount", 0); put("result", "SUCCESS")
    }).toByteArray(Charsets.UTF_8).also { require(it.size in 1..MAX_BYTES) }

    class Pass(val startedAt: Instant, val completedAt: Instant) {
        init { require(time(startedAt) && time(completedAt) && !completedAt.isBefore(startedAt)) }
        override fun toString(): String = "InitialCheckpointPass(comparison-only)"
    }

    override fun toString(): String = "TestActiveInitialCheckpointDocumentV1(syntax-only,redacted,no-authority)"
    companion object {
        const val PROFILE = "TEST_INITIAL_EMPTY_EPOCH1"
        const val MAX_BYTES = 65_536
        private val HASH = Regex("[0-9a-f]{64}")
        private val FLOOR = Instant.parse("1970-01-01T00:00:00Z")
        private val CEILING = Instant.parse("+10000-01-01T00:00:00Z")
        internal fun time(value: Instant): Boolean = value.nano % 1000 == 0 && !value.isBefore(FLOOR) && value.isBefore(CEILING)
    }
}
