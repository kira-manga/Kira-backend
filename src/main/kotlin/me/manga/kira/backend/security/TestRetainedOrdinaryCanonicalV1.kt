package me.manga.kira.backend.security

import me.manga.kira.backend.common.Sha256

/**
 * Closed content projection only. The purge SQL original separately proves native cut membership
 * and the actual converted primary/alias family. This helper cannot issue that authority.
 */
internal object TestRetainedOrdinaryCanonicalV1 {
    fun sha256(routing: TestOwnerDeleteJournalRoutingV1, primary: TestOwnerDeleteJournalEventV1,
        selected: TestOwnerDeleteRoutingCandidateV1): String {
        requireJournalCodec(primary.belongsTo(routing) && selected in routing.derive(primary.comparison).candidates())
        val limits = routing.journalConfiguration.declaration().limits.decoder
        val bytes = primary.canonicalBytes()
        val projected = try {
            when (primary.comparison) {
                is TestOwnerDeleteJournalTupleV1 -> TestOwnerDeleteJournalJsonV1(limits, routing.journalConfiguration.ownerDeleteAll).let {
                    it.encodePayload(it.payload(bytes).copy(eventId = selected.eventId))
                }
                is TestAdminDeleteJournalTupleV1 -> TestAdminDeleteJournalJsonV1(limits).let {
                    it.encodePayload(it.payload(bytes).copy(eventId = selected.eventId))
                }
                is TestAdminBatchDeleteJournalTupleV1 -> TestAdminBatchDeleteJournalJsonV1(limits).let {
                    it.encodePayload(it.payload(bytes).copy(eventId = selected.eventId))
                }
            }
        } finally { bytes.fill(0) }
        try {
            val rebound = when (primary.comparison) {
                is TestOwnerDeleteJournalTupleV1 -> TestOwnerDeleteJournalCodecV1.restoreCanonical(routing, projected, selected.routingKeyId)
                is TestAdminDeleteJournalTupleV1 -> TestOwnerDeleteJournalCodecV1.restoreAdminCanonical(routing, projected, selected.routingKeyId)
                is TestAdminBatchDeleteJournalTupleV1 -> TestOwnerDeleteJournalCodecV1.restoreAdminBatchCanonical(routing, projected, selected.routingKeyId)
            }
            val a = primary.comparison
            val b = rebound.comparison
            requireJournalCodec(rebound.route == selected && a.scope == b.scope && a.epoch == b.epoch && a.eventKind == b.eventKind &&
                a.actorKind == b.actorKind && a.actorId == b.actorId && a.credentialVersion == b.credentialVersion &&
                a.operationKey == b.operationKey && a.encodedFingerprint() == b.encodedFingerprint() && primary.complaintIds() == rebound.complaintIds())
            if (a is TestAdminErasureJournalTupleV1) requireJournalCodec(b is TestAdminErasureJournalTupleV1 &&
                a.consumedGrantId == b.consumedGrantId && a.ownerInstallationIds() == b.ownerInstallationIds())
            return Sha256.hex(projected).also { requireJournalCodec(it == rebound.semanticSha256) }
        } finally { projected.fill(0) }
    }
}
