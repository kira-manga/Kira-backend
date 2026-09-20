package me.manga.kira.backend.complaint.infrastructure.journal.aws

import me.manga.kira.backend.complaint.infrastructure.journal.requireJournalPublication

/** Private-origin wire projection, not a routing/profile/proof interface implementable by callers. */
internal class JournalS3WireBindingV1 private constructor(private val live: JournalS3RequestV1?, private val test: TestOwnerDeleteS3CallV1?, private val seal: TestOrdinarySealS3CallV1?) {
    val location = live?.declaration?.journalLocation ?: test?.declaration?.journalLocation ?: checkNotNull(seal).declaration.journalLocation
    val maximumEnvelopeBytes = live?.declaration?.limits?.decoder?.maximumEnvelopeBytes ?: test?.declaration?.limits?.decoder?.maximumEnvelopeBytes ?: checkNotNull(seal).declaration.limits.decoder.maximumEnvelopeBytes
    val objectKey get() = live?.objectKey ?: test?.objectKey ?: checkNotNull(seal).objectKey
    val operation get() = live?.operation ?: test?.operation ?: checkNotNull(seal).operation
    val versionId get() = live?.versionId ?: test?.versionId ?: seal?.versionId
    val candidate: Candidate? = live?.candidate?.let { Candidate.live(it) } ?: test?.candidate?.let { Candidate.test(it) } ?: seal?.candidate?.let { Candidate.seal(it) }
    fun check() { if (live != null) live.check() else if (test != null) test.check() else checkNotNull(seal).check() }
    fun remainingMillis(): Int = live?.remainingMillis() ?: test?.remainingMillis() ?: checkNotNull(seal).remainingMillis()
    fun matches(call: JournalS3RequestV1): Boolean = live === call
    fun matches(call: TestOwnerDeleteS3CallV1): Boolean = test === call
    fun matches(call: TestOrdinarySealS3CallV1): Boolean = seal === call
    class Candidate private constructor(private val live: JournalS3PutV1?, private val test: TestOwnerDeleteS3CandidateV1?, private val seal: TestOrdinarySealS3CandidateV1?) {
        val size get() = live?.size ?: test?.size ?: checkNotNull(seal).size
        val wireSha256 get() = live?.wireSha256 ?: test?.wireSha256 ?: checkNotNull(seal).wireSha256
        val checksum get() = live?.checksum ?: test?.checksum ?: checkNotNull(seal).checksum
        val retainUntil get() = live?.retainUntil ?: test?.retainUntil ?: checkNotNull(seal).retainUntil
        fun bytes(): ByteArray = live?.bytes() ?: test?.bytes() ?: checkNotNull(seal).bytes()
        fun metadata(): Map<String, String> = live?.metadata() ?: test?.metadata() ?: checkNotNull(seal).metadata()
        companion object {
            internal fun live(call: JournalS3PutV1) = Candidate(call, null, null)
            internal fun test(call: TestOwnerDeleteS3CandidateV1) = Candidate(null, call, null)
            internal fun seal(call: TestOrdinarySealS3CandidateV1) = Candidate(null, null, call)
        }
    }
    companion object {
        fun of(call: JournalS3RequestV1): JournalS3WireBindingV1 {
            // The pre-existing closed LIVE ordinary/seal family is unchanged.
            requireJournalPublication(call is JournalS3CallV1 || call is EpochSealS3CallV1)
            call.check()
            return JournalS3WireBindingV1(call, null, null)
        }
        fun of(call: TestOwnerDeleteS3CallV1): JournalS3WireBindingV1 { call.check(); return JournalS3WireBindingV1(null, call, null) }
        fun of(call: TestOrdinarySealS3CallV1): JournalS3WireBindingV1 { call.check(); return JournalS3WireBindingV1(null, null, call) }
    }
}
