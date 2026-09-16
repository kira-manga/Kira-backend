package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.complaint.domain.ComplaintJournalConfigurationV1
import me.manga.kira.backend.complaint.domain.InitialLiveJournalTestFixture
import me.manga.kira.backend.complaint.domain.catalog.InitialLiveRangeFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class InitialLiveRangeFactoryTest {
    @Test
    fun `range derives all configuration fields and digest from the same initial LIVE object`() {
        val declaration = InitialLiveJournalTestFixture.declaration()
        val journal = ComplaintJournalConfigurationV1.of(declaration)
        val range = InitialLiveRangeFactory.fromJournalConfiguration(journal)
        assertEquals("LIVE", range.scope.kind)
        assertEquals("00000000-0000-0000-0000-000000000000", range.scope.id)
        assertEquals("OPEN", range.state)
        val namespace = "complaints/journal/v1/${declaration.writer.generationId}/live/${range.scope.id}/"
        assertEquals(namespace + "ordinary/", range.ordinaryPrefix)
        assertEquals(namespace + "seal-terminal/", range.sealTerminalPrefix)
        assertEquals(declaration.journalLocation, range.journalLocation)
        assertEquals(declaration.authorities.ordinary, range.ordinaryAuthority)
        assertEquals(declaration.authorities.sealTerminal, range.sealTerminalAuthority)
        assertEquals(declaration.routing.activeKeyId, range.routingKeyId)
        assertEquals(declaration.encryption.keyId, range.encryptionKeyId)
        assertEquals(journal.sha256, range.configurationSha256)
        assertEquals(1L, range.firstEpoch)
        assertEquals(0L, range.sealHistory.count)
        assertEquals(Sha256.hexUtf8("[]"), range.sealHistory.sha256)
    }

    @Test
    fun `a declaration absent from the old range wire still changes its same J commitment`() {
        val declaration = InitialLiveJournalTestFixture.declaration()
        val original = InitialLiveRangeFactory.fromJournalConfiguration(ComplaintJournalConfigurationV1.of(declaration))
        val changed = declaration.copy(limits = declaration.limits.copy(deadlines = declaration.limits.deadlines.copy(s3CallMillis = 1499)))
        val replacement = InitialLiveRangeFactory.fromJournalConfiguration(ComplaintJournalConfigurationV1.of(changed))
        assertNotEquals(original.configurationSha256, replacement.configurationSha256)
        assertEquals(original, replacement.copy(configurationSha256 = original.configurationSha256))
    }

    @Test
    fun `range copies remain claims and cannot mutate the producer or nominate different namespaces`() {
        val declaration = InitialLiveJournalTestFixture.declaration()
        val journal = ComplaintJournalConfigurationV1.of(declaration)
        val original = InitialLiveRangeFactory.fromJournalConfiguration(journal)
        val forged = original.copy(state = "SEALED", configurationSha256 = "0".repeat(64), firstEpoch = 2L)
        assertNotEquals(original, forged)
        assertEquals(original, InitialLiveRangeFactory.fromJournalConfiguration(journal))
        val changed = ComplaintJournalConfigurationV1.of(
            declaration.copy(writer = declaration.writer.copy(generationId = InitialLiveJournalTestFixture.uuid('9'))),
        )
        val other = InitialLiveRangeFactory.fromJournalConfiguration(changed)
        assertNotEquals(original.ordinaryPrefix, other.ordinaryPrefix)
        assertNotEquals(original.configurationSha256, other.configurationSha256)
    }
}
