package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication

/** Private-origin wire projection, not a routing/profile/proof interface implementable by callers. */
internal class JournalS3WireBindingV1 private constructor(private val live: JournalS3RequestV1?, private val test: TestOwnerDeleteS3CallV1?) {
    val location = live?.declaration?.journalLocation ?: checkNotNull(test).declaration.journalLocation
    val maximumEnvelopeBytes = live?.declaration?.limits?.decoder?.maximumEnvelopeBytes ?: checkNotNull(test).declaration.limits.decoder.maximumEnvelopeBytes
    val objectKey get() = live?.objectKey ?: checkNotNull(test).objectKey
    val operation get() = live?.operation ?: checkNotNull(test).operation
    val versionId get() = live?.versionId ?: test?.versionId
    val candidate: Candidate? = live?.candidate?.let { Candidate.live(it) } ?: test?.candidate?.let { Candidate.test(it) }
    fun check() { if (live != null) live.check() else checkNotNull(test).check() }
    fun remainingMillis(): Int = live?.remainingMillis() ?: checkNotNull(test).remainingMillis()
    fun matches(call: JournalS3RequestV1): Boolean = live === call
    fun matches(call: TestOwnerDeleteS3CallV1): Boolean = test === call
    class Candidate private constructor(private val live: JournalS3PutV1?, private val test: TestOwnerDeleteS3CandidateV1?) {
        val size get() = live?.size ?: checkNotNull(test).size
        val wireSha256 get() = live?.wireSha256 ?: checkNotNull(test).wireSha256
        val checksum get() = live?.checksum ?: checkNotNull(test).checksum
        val retainUntil get() = live?.retainUntil ?: checkNotNull(test).retainUntil
        fun bytes(): ByteArray = live?.bytes() ?: checkNotNull(test).bytes()
        fun metadata(): Map<String, String> = live?.metadata() ?: checkNotNull(test).metadata()
        companion object {
            internal fun live(call: JournalS3PutV1) = Candidate(call, null)
            internal fun test(call: TestOwnerDeleteS3CandidateV1) = Candidate(null, call)
        }
    }
    companion object {
        fun of(call: JournalS3RequestV1): JournalS3WireBindingV1 {
            // The pre-existing closed LIVE ordinary/seal family is unchanged.
            requireJournalPublication(call is JournalS3CallV1 || call is EpochSealS3CallV1)
            call.check()
            return JournalS3WireBindingV1(call, null)
        }
        fun of(call: TestOwnerDeleteS3CallV1): JournalS3WireBindingV1 { call.check(); return JournalS3WireBindingV1(null, call) }
    }
}
