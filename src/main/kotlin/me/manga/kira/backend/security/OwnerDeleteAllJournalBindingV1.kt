package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import java.util.UUID

/** Closed OWNER_DELETE_ALL bridge between the two real routing owners. No material/configuration
 * conversion, provider port, generic family registry or authority is introduced by this binding. */
internal class OwnerDeleteAllJournalBindingV1 private constructor(
    private val live: VersionBoundComplaintJournalRouting?,
    val test: TestOwnerDeleteJournalRoutingV1?,
    private val liveCodec: OwnerDeleteAllJournalCodecV1?,
    private val testCodec: TestOwnerDeleteJournalCodecV1?,
) {
    constructor(routing: VersionBoundComplaintJournalRouting, codec: OwnerDeleteAllJournalCodecV1? = null) : this(routing, null, codec, null)
    constructor(routing: TestOwnerDeleteJournalRoutingV1, codec: TestOwnerDeleteJournalCodecV1? = null) : this(null, routing, null, codec) {
        require(routing.journalConfiguration.ownerDeleteAll)
    }

    val scope = test?.journalConfiguration?.scope ?: ComplaintDataScope.LIVE
    val writer = live?.journalConfiguration?.declaration()?.writer ?: checkNotNull(test).journalConfiguration.declaration().writer
    val limits = live?.journalConfiguration?.declaration()?.limits ?: checkNotNull(test).journalConfiguration.declaration().limits
    val sha256 = live?.journalConfiguration?.sha256 ?: checkNotNull(test).journalConfiguration.sha256

    fun requireLive(routing: VersionBoundComplaintJournalRouting) { check(live === routing && test == null) }
    fun requireTest(routing: TestOwnerDeleteJournalRoutingV1) { check(test === routing && live == null) }
    fun owns(event: OwnerDeleteAllJournalEventV1): Boolean = live?.let(event::belongsTo) ?: event.belongsTo(checkNotNull(test))

    fun derive(tuple: ComplaintJournalDeletionTupleV1): List<ComplaintJournalRoutingCandidateV1> {
        requireTuple(tuple)
        return live?.derive(tuple)?.candidates() ?: checkNotNull(test).derive(testTuple(tuple)).candidates().map {
            ComplaintJournalRoutingCandidateV1(it.routingKeyId, it.objectKey, it.eventId)
        }
    }

    fun active(tuple: ComplaintJournalDeletionTupleV1): ComplaintJournalRoutingCandidateV1 {
        requireTuple(tuple)
        return live?.derive(tuple)?.active ?: checkNotNull(test).derive(testTuple(tuple)).active.let {
            ComplaintJournalRoutingCandidateV1(it.routingKeyId, it.objectKey, it.eventId)
        }
    }

    fun canonicalize(tuple: ComplaintJournalDeletionTupleV1, ids: List<UUID>, key: String): OwnerDeleteAllJournalEventV1 {
        requireTuple(tuple)
        return if (live != null) checkNotNull(liveCodec).canonicalize(tuple, ids, key).also { check(it.belongsTo(live)) }
        else fromTest(checkNotNull(testCodec).canonicalize(testTuple(tuple), ids, key))
    }

    fun restore(bytes: ByteArray, key: String): OwnerDeleteAllJournalEventV1 =
        if (live != null) OwnerDeleteAllJournalCodecV1.restoreCanonical(live, bytes, key)
        else fromTest(TestOwnerDeleteJournalCodecV1.restoreCanonical(checkNotNull(test), bytes, key))

    fun fromTest(event: TestOwnerDeleteJournalEventV1): OwnerDeleteAllJournalEventV1 {
        val routing = checkNotNull(test)
        check(event.belongsTo(routing) && event.tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
        return OwnerDeleteAllJournalEventV1.fromTest(routing, event)
    }

    fun testEvent(event: OwnerDeleteAllJournalEventV1): TestOwnerDeleteJournalEventV1 {
        check(owns(event))
        return TestOwnerDeleteJournalCodecV1.restoreCanonical(checkNotNull(test), event.canonicalBytes(), event.route.routingKeyId).also {
            check(it.tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL)
        }
    }

    private fun requireTuple(tuple: ComplaintJournalDeletionTupleV1) {
        check(tuple.scope == scope && tuple.eventKind == ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL &&
            tuple.actorKind == ComplaintJournalActorKindV1.INSTALLATION)
    }

    private fun testTuple(tuple: ComplaintJournalDeletionTupleV1) = TestOwnerDeleteJournalTupleV1(
        tuple.epoch, tuple.actorId, checkNotNull(tuple.credentialVersion), tuple.operationKey,
        java.util.Base64.getUrlDecoder().decode(tuple.encodedFingerprint()), scope, ComplaintJournalDeletionKindV1.OWNER_DELETE_ALL,
    )

    override fun toString(): String = "OwnerDeleteAllJournalBindingV1(exact-routing-owner,no-authority)"
}
