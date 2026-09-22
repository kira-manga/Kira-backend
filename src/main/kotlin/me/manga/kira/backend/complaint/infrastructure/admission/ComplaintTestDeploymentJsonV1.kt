package me.manga.kira.backend.complaint.infrastructure.admission

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import me.manga.kira.backend.complaint.domain.TestOwnerDeleteJournalDocumentV1
import me.manga.kira.backend.complaint.domain.catalog.InitialPolicyReferenceV1
import me.manga.kira.backend.complaint.domain.terminal.TestOrdinaryDenialAuthorityInputV1
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDenialAuthorityInputV1
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.CancellationException

/** Separate closed TEST grammar. No LIVE profile, supplied D, observed state, session or raw secret. */
internal object ComplaintTestDeploymentJsonV1 {
    const val MAX_BYTES = 1024 * 1024
    private const val MAX_TOKENS = 16_384
    private val factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
        .streamReadConstraints(
            StreamReadConstraints.builder().maxNestingDepth(24).maxStringLength(196_608).maxNameLength(64).maxNumberLength(19).build(),
        ).build()

    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json {
        ignoreUnknownKeys = false
        isLenient = false
        explicitNulls = true
        coerceInputValues = false
        allowSpecialFloatingPointValues = false
    }

    @Suppress("TooGenericExceptionCaught") // No parser text, path, credentials or submitted values cross this boundary.
    fun parse(bytes: ByteArray): ComplaintTestDeploymentInputsV1 {
        requireTestDeployment(bytes.size in 1..MAX_BYTES, ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
        return try {
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
            factory.createParser(text).use { parser ->
                requireTestDeployment(parser.nextToken() == JsonToken.START_OBJECT, ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
                var tokens = 1
                var depth = 1
                var adminReadObject = false
                while (depth > 0) {
                    val token = parser.nextToken()
                    requireTestDeployment(token != null && ++tokens <= MAX_TOKENS, ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
                    if (depth == 1 && token == JsonToken.START_OBJECT && parser.currentName == "adminRead") adminReadObject = true
                    if (adminReadObject && depth == 2 && token != JsonToken.FIELD_NAME && token != JsonToken.END_OBJECT) {
                        // Closed new-member types only: kotlinx integer coercion must not accept quoted numbers.
                        // Legacy fields keep their existing grammar and every token keeps the same bounded pass.
                        val exactType = when (parser.currentName) {
                            "schemaVersion", "perMinute" -> token == JsonToken.VALUE_NUMBER_INT
                            "profile" -> token == JsonToken.VALUE_STRING
                            else -> false
                        }
                        requireTestDeployment(exactType, ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
                    }
                    when (token) {
                        JsonToken.START_OBJECT, JsonToken.START_ARRAY -> depth++
                        JsonToken.END_OBJECT, JsonToken.END_ARRAY -> depth--
                        JsonToken.VALUE_NUMBER_FLOAT -> throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
                        else -> Unit
                    }
                    if (depth == 1) adminReadObject = false
                }
                requireTestDeployment(parser.nextToken() == null, ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
            }
            ComplaintTestDeploymentInputsV1.fromDecoded(json.decodeFromString(ComplaintTestDeploymentDocumentV1.serializer(), text))
        } catch (_: CancellationException) {
            throw CancellationException("TEST deployment intake cancelled.")
        } catch (_: Exception) {
            throw ComplaintTestDeploymentExceptionV1(ComplaintTestDeploymentFailureV1.INPUT_REFUSED)
        }
    }
}

/** Legacy fields remain required. The new drain profile alone requires the optional purpose policy. */
@Serializable
internal data class ComplaintTestDeploymentDocumentV1(
    val schemaVersion: Int,
    val profile: String,
    val implementationSchema: Int,
    val desiredGeneration: Long,
    val dataScopeId: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val database: TestDatabaseInputV1,
    val jwt: DesiredJwtInputV1,
    val capacity: DesiredCapacityInputV1,
    val admission: DesiredAdmissionInputV1,
    val journal: TestOwnerDeleteJournalDocumentV1,
    val catalog: DesiredCatalogInputV1,
    val activation: TestActivationInputV1,
    val sealer: DesiredSealerInputV1,
    val retention: TestOrdinarySealRetentionInputV1,
    val ordinaryDenial: TestOrdinaryDenialAuthorityInputV1? = null,
    val activeFirstCut: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutInputV1? = null,
    val ordinaryPublication: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinaryPublicationInputV1? = null,
    val activeFirstCutSuccessor: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveFirstCutSuccessorInputV1? = null,
    val initialCheckpoint: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveInitialCheckpointInputV1? = null,
    val activeRecurrent: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveRecurrentInputV1? = null,
    val activeOrdinarySealRecovery: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOrdinarySealRecoveryInputV1? = null,
    val terminalDenial: TestTerminalDenialAuthorityInputV1? = null,
    val initialCheckpointCreate: me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointCreateInputV1? = null,
    val activeOwnerDeleteQueue: me.manga.kira.backend.complaint.domain.reconciliation.TestActiveOwnerDeleteQueueInputV1? = null,
    val initialCheckpointDeletion: me.manga.kira.backend.complaint.domain.reconciliation.TestInitialCheckpointDeletionInputV1? = null,
    val adminRead: TestRegisteredAdminReadInputV1? = null,
)

/** Independent born-with read cohort only. This declaration is not registered/current TEST authority. */
@Serializable
internal data class TestRegisteredAdminReadInputV1(
    val schemaVersion: Int,
    val profile: String,
    val perMinute: Int,
) {
    companion object {
        const val PROFILE = "TEST_REGISTERED_ADMIN_SEARCH_DETAIL_STATS_V1"
    }
}

/** One ordinary runtime principal only, never the LIVE install operator or catalog author. */
@Serializable
internal data class TestDatabaseInputV1(
    val host: String,
    val port: Int,
    val name: String,
    val runtimeUsername: String,
    val runtimePassword: DesiredSecretReferenceV1,
    val ordinaryCapacity: Int,
    val publicTrustPemBase64: String,
    val protectedTrustParent: String,
)

@Serializable
internal data class TestActivationInputV1(
    val signingKey: DesiredCatalogSigningKeyInputV1,
    val initialWriterRegistryBase64: String,
    val totalAttemptMillis: Long,
)

/** Complete declared restore horizon and finite bounds, not installed-policy or retention evidence. */
@Serializable
internal data class TestOrdinarySealRetentionInputV1(
    val environment: String,
    val dataScopeId: String,
    val writerGeneration: String,
    val databaseIdentity: String,
    val restoreIdentity: String,
    val lastPreRunRestoreHorizon: String,
    val horizonPolicy: InitialPolicyReferenceV1,
    val journalLockPolicy: InitialPolicyReferenceV1,
    val hmacKeys: List<DesiredLiveHmacRetentionInputV1>,
    val kmsKeys: List<DesiredLiveKmsRetentionInputV1>,
    val acceptedRequestLateArrival: DesiredLiveTimeBoundInputV1,
    val utcUncertainty: DesiredLiveTimeBoundInputV1,
)
