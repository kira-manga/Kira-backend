package me.manga.kira.backend.complaint.domain.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1

/** Same-object initial range claims only. No accepted registration, freshness, credentials or activation is produced. */
internal object InitialLiveRangeFactory {
    fun fromJournalConfiguration(journal: ComplaintJournalConfigurationV1): InitialLiveRangeV1 {
        val declared = journal.declaration()
        return InitialLiveRangeV1(
            scope = InitialLiveScopeV1("LIVE", OfflineBootstrapGrammar.LIVE_SCOPE_ID),
            state = "OPEN",
            journalLocation = declared.journalLocation,
            ordinaryPrefix = OfflineBootstrapGrammar.ordinaryPrefix(declared.writer.generationId),
            sealTerminalPrefix = OfflineBootstrapGrammar.sealTerminalPrefix(declared.writer.generationId),
            ordinaryAuthority = declared.authorities.ordinary,
            sealTerminalAuthority = declared.authorities.sealTerminal,
            routingKeyId = declared.routing.activeKeyId,
            encryptionKeyId = declared.encryption.keyId,
            configurationSha256 = journal.sha256,
            firstEpoch = 1L,
            sealHistory = InitialSealHistoryV1(0L, Sha256.hexUtf8("[]")),
        )
    }
}
