package me.manga.kira.backend.complaint.catalog

import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.poolTestField
import me.manga.kira.backend.complaint.infrastructure.catalog.CatalogCoordinatorLeaseCampaignV1
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.assertThrows
import java.util.concurrent.atomic.AtomicReference

/** Same original root clock/J/campaign window. No replacement attempt or synthetic renewal receipt is constructed. */
internal class HeldEpochSealBudgetCases(private val h: HeldEpochSealTestFixture) {
    fun exhausted(cut: HeldSealBudgetCut) {
        val leader = h.capture()
        var providerStarted = 0L
        var renewalDispatched = 0L
        h.wire.beforeStsFactory = { serial ->
            if (serial == 1) {
                providerStarted = h.clock.nanoTime()
                renewalDispatched = checkNotNull(
                    poolTestField<AtomicReference<CatalogCoordinatorLeaseCampaignV1.Window?>>(leader.campaign, "window").get(),
                ).startedAtNanos
                when (cut) {
                    HeldSealBudgetCut.ORIGINAL_J -> assertEquals(6000, h.codecAttempt().remainingMillis(30_000))

                    HeldSealBudgetCut.RENEWAL_REMAINDER -> {
                        assertEquals(28_500, h.codecAttempt().remainingMillis(30_000))
                        assertEquals(8500, h.codecAttempt().remainingProviderMillis(30_000))
                    }

                    else -> Unit
                }
            } else if (cut == HeldSealBudgetCut.RENEWAL_REMAINDER) {
                h.clock.advanceMillis(1400)
                assertEquals(2300, h.codecAttempt().remainingProviderMillis(30_000))
            }
        }
        when (cut) {
            HeldSealBudgetCut.ORIGINAL_J -> {
                // Sixteen actual committed cutoff/renew phases, each below the original 2s phase cap, spend 24s of the ONE J.
                h.clock.phaseChargeMillis = 1500
                h.wire.changeStsReply = { _, reply -> reply.beforeCall = { h.clock.advanceMillis(2400) } }
            }

            HeldSealBudgetCut.RENEWAL_REMAINDER -> {
                h.clock.firstPostPrepareRenewalChargeMillis = 1500
                h.wire.changeStsReply = { _, reply -> reply.beforeCall = { h.clock.advanceMillis(2400) } }
            }

            HeldSealBudgetCut.KMS_CURRENT_REMAINDER -> {
                h.wire.beforeKmsFactory = { h.clock.advanceMillis(9500) }
                h.wire.changeKmsReply = { reply ->
                    assertEquals(500, h.codecAttempt().remainingProviderMillis(1000))
                    assertTrue(h.codecAttempt().remainingMillis(30_000) > 10_000)
                    reply.beforeCall = { h.clock.advanceMillis(600) }
                }
            }

            HeldSealBudgetCut.FULL_J_SESSION_EXPIRY -> {
                // A 15s credential easily covers the <=10s cadence, but cannot cover the still-original 30s J.
                h.wire.expirationSeconds = 15
            }
        }
        val failure = assertThrows<PersistencePhaseException> { h.open(leader.campaign) }
        h.wire.assertSanitized(failure)
        assertEquals(if (cut == HeldSealBudgetCut.FULL_J_SESSION_EXPIRY) 2 else 3, h.wire.sts.requests.size)
        assertEquals(if (cut == HeldSealBudgetCut.KMS_CURRENT_REMAINDER) 1 else 0, h.wire.kms.requests.size)
        when (cut) {
            HeldSealBudgetCut.ORIGINAL_J -> {
                assertEquals(16, h.clock.committedPhases)
                assertTrue(h.clock.nanoTime() - providerStarted < 9_000_000_000L, "The STS slice itself is still healthy.")
                assertTrue(h.clock.nanoTime() - renewalDispatched < 10_000_000_000L, "The last genuine renewal is still within cadence.")
                assertEquals(0, h.wire.sts.replies.last().reads)
            }

            HeldSealBudgetCut.RENEWAL_REMAINDER -> {
                assertTrue(h.clock.nanoTime() - providerStarted < 9_000_000_000L)
                assertTrue(h.clock.nanoTime() - renewalDispatched > 10_000_000_000L)
                assertEquals(0, h.wire.sts.replies.last().reads, "A post-STS check alone is too late; the actual request must use the dispatch remainder.")
            }

            HeldSealBudgetCut.KMS_CURRENT_REMAINDER -> {
                assertEquals(2, h.clock.postPrepareRenewals)
                assertEquals(0, h.wire.kms.replies.single().reads, "A captured KMS integer/full-J allowance must not outlive current custody.")
            }

            HeldSealBudgetCut.FULL_J_SESSION_EXPIRY -> assertEquals(1, h.wire.stsFactories, "Insufficient full-J expiry refuses even target construction.")
        }
        h.assertCanonical(leader.receipt.token)
        h.assertReleased()
    }

    fun heldExpiryStillCloses() {
        val leader = h.capture()
        val held = h.open(leader.campaign)
        val canonical = h.assertCanonical(leader.receipt.token)
        h.clock.advanceMillis(h.history.routing.journalConfiguration.declaration().limits.deadlines.epochSealMillis.toLong())
        h.wire.assertSanitized(assertThrows<RuntimeException> { held.requireHeld() })
        assertEquals(1L, h.lanes.activeOwners().totalOwners)
        assertEquals(0, h.wire.sts.closedClients + h.wire.kms.closedClients, "Expiry is not native completion or an automatic lane refund.")
        runCatching(held::close).exceptionOrNull()?.let(h.wire::assertSanitized)
        h.assertReleased()
        assertEquals(canonical, h.sealRow())
    }
}

internal enum class HeldSealBudgetCut { ORIGINAL_J, RENEWAL_REMAINDER, KMS_CURRENT_REMAINDER, FULL_J_SESSION_EXPIRY }
