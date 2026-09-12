package me.manga.kira.backend.sourceconfig.admin

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.AssemblySource
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogAdmission
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPhase
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.domain.PublishedDocumentRepository
import me.manga.kira.backend.sourceconfig.domain.SourceConfigHead
import me.manga.kira.backend.user.domain.Role
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/** Origin receipt identity is independent of later catalog evolution and current initial policy. */
@Import(BootstrapReplayTestConfiguration::class)
class BootstrapReplayIT : AbstractAdminSourceIT() {
    @Autowired
    private lateinit var policy: BootstrapReplayPolicy

    @Autowired
    private lateinit var documents: PublishedDocumentRepository

    @BeforeEach
    fun resetPolicy() {
        policy.rejectCalls.set(false)
        policy.calls.set(0)
    }

    @AfterEach
    fun releasePolicy() {
        policy.rejectCalls.set(false)
    }

    @Test
    fun `same bytes return the origin receipt after content addition lifecycle and policy changes`() {
        val raw = approvedBootstrapPayload()
        val first = bootstrapRequest(raw).andExpect { status { isOk() } }.andReturn().response
        val originResponse = objectMapper.readTree(first.contentAsByteArray)
        val origin = requireNotNull(documents.initialSourceCatalogState().receipt)
        assertTrue(policy.calls.get() > 0, "first completion must use the real pinned initial policy")

        val later = SourceConfigFixtures.validGenericSource("LaterThirteenth")
        createSource(later).andExpect { status { isCreated() } }
        publish(later.api, 1).andExpect { status { isOk() } }
        val azora = publicServedDocument().sources.single { it.api == "Azora" }
        createRevision(azora.api, azora.copy(displayName = "A later operator-approved title")).andExpect { status { isCreated() } }
        publish(azora.api, 2).andExpect { status { isOk() } }
        disable("Mangamello").andExpect { status { isOk() } }
        assertEquals(13, publicServedDocument().sources.size)
        assertEquals("A later operator-approved title", publicServedDocument().sources.single { it.api == azora.api }.displayName)
        assertEquals("disabled", sourceStatus("Mangamello"))
        assertTrue(requireNotNull(latestPointer()) > origin.documentRevision)

        val retryActor = users.create("bootstrap-retry-${UUID.randomUUID()}@test.local", passwordEncoder.encode(ADMIN_PASSWORD), Role.ADMIN)
        val retryToken = jwtService.issue(retryActor).value
        assertNotEquals(admin.id, retryActor.id)
        val evolved = publicState()
        val evolvedMutations = jdbcTemplate.bootstrapMutationRows()
        val policyCalls = policy.calls.get()
        // Simulate a later policy/reference that cannot admit anything. It is not an input to replay.
        // admitPayload owns strict parsing too, so its guard also detects re-entering initial parsing.
        policy.rejectCalls.set(true)

        val replay = mockMvc.postBootstrap(raw, retryToken).andExpect { status { isOk() } }.andReturn().response
        assertEquals(originResponse, objectMapper.readTree(replay.contentAsByteArray))
        assertEquals(
            origin,
            documents.initialSourceCatalogState().receipt,
            "actor, time, policy and both origin checksums remain immutable",
        )
        assertEquals(InitialSourceCatalogPhase.COMPLETE, documents.initialSourceCatalogState().phase)
        assertPublicStateUnchanged(evolved)
        assertEquals(evolvedMutations, jdbcTemplate.bootstrapMutationRows(), "replay writes neither mutation audit nor source history")

        listOf(
            raw + byteArrayOf('\n'.code.toByte()), // same JSON semantics, different exact bytes
            raw + byteArrayOf(0xff.toByte()), // invalid UTF-8: COMPLETE dispatch precedes decoding
        ).forEach { differentRaw ->
            mockMvc.postBootstrap(differentRaw, retryToken).andExpect {
                status { isConflict() }
                jsonPath("$.errors[0].code") { value(BOOTSTRAP_REJECTED) }
            }
            assertPublicStateUnchanged(evolved)
            assertEquals(evolvedMutations, jdbcTemplate.bootstrapMutationRows())
        }
        assertEquals(policyCalls, policy.calls.get(), "neither exact replay nor different-byte conflict may consult today's initial policy")
        assertEquals(origin.documentRevision, originResponse.path("documentRevision").asLong())
        assertNotEquals(origin.documentRevision, latestPointer(), "replay must not replace the current pointer with the origin")
    }

    @Test
    fun `unavailable current initial policy fails a new PENDING attempt without staging`() {
        val before = publicState()
        val mutationsBefore = jdbcTemplate.bootstrapMutationRows()
        policy.rejectCalls.set(true)

        bootstrapRequest().andExpect { status { isInternalServerError() } }

        assertTrue(policy.calls.get() > 0)
        assertEquals(InitialSourceCatalogPhase.PENDING, documents.initialSourceCatalogState().phase)
        assertPublicStateUnchanged(before)
        assertEquals(mutationsBefore, jdbcTemplate.bootstrapMutationRows())
        assertPublicArtifactAbsent("Azora", 1)
    }
}

@TestConfiguration
class BootstrapReplayTestConfiguration {
    @Bean
    @Primary
    fun bootstrapReplayPolicy(@Qualifier("classpathInitialSourceCatalogPolicy") delegate: InitialSourceCatalogPolicy) = BootstrapReplayPolicy(delegate)
}

/** A tripwire around the actual policy, not a policy that supplies permissive fixture answers. */
class BootstrapReplayPolicy(private val delegate: InitialSourceCatalogPolicy) : InitialSourceCatalogPolicy by delegate {
    val rejectCalls = AtomicBoolean(false)
    val calls = AtomicInteger()

    override fun admitPayload(rawJson: String): InitialSourceCatalogAdmission {
        beforeCall()
        return delegate.admitPayload(rawJson)
    }

    override fun requireStagedInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>) {
        beforeCall()
        delegate.requireStagedInventory(heads, assemblySources)
    }

    override fun requirePublicationInventory(heads: List<SourceConfigHead>, assemblySources: List<AssemblySource>) {
        beforeCall()
        delegate.requirePublicationInventory(heads, assemblySources)
    }

    private fun beforeCall() {
        calls.incrementAndGet()
        check(!rejectCalls.get()) { "injected unavailable initial policy" }
    }
}
