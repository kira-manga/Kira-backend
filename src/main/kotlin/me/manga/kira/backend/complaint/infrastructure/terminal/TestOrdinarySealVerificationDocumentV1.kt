package me.manga.kira.backend.complaint.infrastructure.terminal

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalDurableRowV1
import java.time.Instant

/** Shared stored comparison syntax only. Supplying these values cannot issue any native proof. */
internal fun testOrdinarySealVerificationBytesV1(row: TestTerminalDurableRowV1, version: String,
    lastModified: Instant, retainUntil: Instant, verifiedAt: Instant): ByteArray = CanonicalJson.canonicalize(buildJsonObject {
    put("schemaVersion", 1); put("objectKind", "EPOCH_SEAL"); put("role", "ORDINARY")
    put("dataScopeId", row.binding.run.dataScopeId); put("writerGeneration", row.binding.writerGeneration)
    put("epochStartInclusive", row.binding.epochStartInclusive); put("epochEndInclusive", row.binding.epochEndInclusive)
    put("operationToken", row.binding.operationToken); put("configurationSha256", row.binding.run.configurationSha256)
    put("journalConfigurationSha256", row.binding.journalConfigurationSha256)
    put("objectKey", row.binding.objectKey); put("objectId", row.binding.objectId); put("objectVersion", version)
    put("canonicalSha256", row.canonicalSha256); put("ciphertextSha256", checkNotNull(row.wireSha256))
    put("lastModified", lastModified.toString()); put("requestedRetainUntil", checkNotNull(row.retainUntil).toString())
    put("retainUntil", retainUntil.toString()); put("objectLockMode", "COMPLIANCE"); put("verifiedAt", verifiedAt.toString())
}).toByteArray(Charsets.UTF_8)
