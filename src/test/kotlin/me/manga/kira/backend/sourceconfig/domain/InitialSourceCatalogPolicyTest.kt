package me.manga.kira.backend.sourceconfig.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import me.manga.kira.backend.common.CanonicalJson
import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.common.exception.BadRequestException
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.APPROVED_GENERIC_APIS
import me.manga.kira.backend.sourceconfig.domain.InitialSourceCatalogPolicy.Companion.LEGACY_APIS
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.ACTIVE
import me.manga.kira.backend.sourceconfig.domain.SourceLifecycleStatus.WITHHELD
import me.manga.kira.backend.sourceconfig.domain.model.IconSpec
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfig
import me.manga.kira.backend.sourceconfig.domain.model.SourceConfigDocument
import me.manga.kira.backend.sourceconfig.infrastructure.ClasspathInitialSourceCatalogPolicy
import me.manga.kira.backend.sourceconfig.parsing.SourceConfigParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.ValueSource
import java.io.IOException
import java.time.Instant
import java.util.UUID

/**
 * Initial-policy unit coverage only: no database, mock COMPLETE receipt, or sibling App checkout.
 * The independently pinned main resource is the oracle; minimal legacy models exercise roster rules,
 * not a released payload. Connected import/replay/rollback tests own transaction-level assertions.
 */
class InitialSourceCatalogPolicyTest {
    private val policy = ClasspathInitialSourceCatalogPolicy()

    @Test
    fun `main reference has the independently approved bytes identity and raw ordered models`() {
        val bytes = referenceBytes()
        assertEquals(30_742, bytes.size)
        assertEquals(REFERENCE_SHA256, Sha256.hex(bytes))
        assertEquals(REFERENCE_SHA256, InitialSourceCatalogPolicy.REFERENCE_SHA256)
        assertEquals("app-bundle-v6-initial-catalog-v1", InitialSourceCatalogPolicy.POLICY_ID)
        val document = referenceDocument()
        assertEquals(1, document.schemaVersion)
        assertEquals(6L, document.revision)
        assertEquals(null, document.generatedAt)
        assertEquals(
            listOf(
                "Azora", "Mangamello", "Mangamello Plus", "SwatManga", "Lekmanga", "Team X",
                "DilarV2", "3asq", "Demonicscans", "Mangabuddy", "Zazamanga", "Tapas",
            ),
            document.sources.map { it.api },
        )
        assertEquals(APPROVED_GENERIC_APIS, document.sources.map { it.api })
        assertTrue(document.sources.all { it.engine == "generic" && it.lifecycle == "active" && it.priority == 0 })
        assertEquals(33, LEGACY_APIS.size)
        assertEquals(45, InitialSourceCatalogPolicy.EXPECTED_ALL.size)
    }

    @Test
    fun `construction is lazy and successful evaluation reads reference only once`() {
        var reads = 0
        val lazyPolicy = ClasspathInitialSourceCatalogPolicy {
            reads++
            referenceBytes()
        }
        assertEquals(0, reads)
        val admission = lazyPolicy.admitPayload(serialize(approvedDocument()))
        assertEquals("app-bundle-v6-initial-catalog-v1", admission.policyId)
        assertEquals(REFERENCE_SHA256, admission.referenceSha256)
        val inventory = inventory()
        lazyPolicy.requireStagedInventory(inventory.heads, inventory.assembly)
        lazyPolicy.requirePublicationInventory(inventory.heads, inventory.assembly)
        lazyPolicy.admitPayload(serialize(approvedDocument()))
        assertEquals(1, reads)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(ReferenceDefect::class)
    fun `unavailable reference fails closed only when admission is evaluated`(defect: ReferenceDefect) {
        val approvedBytes = referenceBytes()
        var reads = 0
        val unavailablePolicy = ClasspathInitialSourceCatalogPolicy {
            reads++
            when (defect) {
                ReferenceDefect.MISSING -> null
                ReferenceDefect.TRUNCATED -> approvedBytes.copyOf(approvedBytes.size - 1)
                ReferenceDefect.CHANGED -> approvedBytes.toString(Charsets.UTF_8).replace("\"revision\":6", "\"revision\":7").toByteArray()
                ReferenceDefect.UNSUPPORTED_SCHEMA ->
                    approvedBytes.toString(Charsets.UTF_8).replace("\"schemaVersion\":1", "\"schemaVersion\":2").toByteArray()

                ReferenceDefect.INVALID_UTF8 -> approvedBytes.copyOf().apply { this[0] = 0xc3.toByte() }
                ReferenceDefect.READ_FAILURE -> throw IOException("reference read failed")
            }
        }
        assertEquals(0, reads)
        val ex = assertThrows(IllegalStateException::class.java) {
            unavailablePolicy.admitPayload(serialize(approvedDocument()))
        }
        assertEquals("Initial source catalog reference is unavailable or invalid", ex.message)
        assertEquals(1, reads)
    }

    @Test
    fun `explicit defaults and reversed object keys preserve full typed equality`() {
        val document = approvedDocument()
        val omitted = serialize(document)
        val explicit = Json { encodeDefaults = true }.encodeToString(SourceConfigDocument.serializer(), document)
        val reversed = reverseObjectKeys(Json.parseToJsonElement(explicit)).toString()
        assertNotEquals(omitted, explicit)
        assertNotEquals(explicit, reversed)
        listOf(omitted, explicit, reversed).forEach { raw ->
            assertEquals(document, policy.admitPayload(raw).document)
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(longs = [0L, 1L, 6L, Long.MAX_VALUE])
    fun `input revision and generated time are preserved provenance not reference gates`(revision: Long) {
        val document = approvedDocument().copy(revision = revision, generatedAt = "2026-09-12T00:00:00Z")
        assertEquals(document, policy.admitPayload(serialize(document)).document)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(ints = [-1, 0, 2])
    fun `unsupported payload schema is rejected`(schema: Int) {
        val ex = assertThrows(InitialSourceCatalogPolicyRejected::class.java) {
            policy.admitPayload(serialize(approvedDocument().copy(schemaVersion = schema)))
        }
        assertEquals("initial source schema is unsupported", ex.message)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(MalformedPayload::class)
    fun `new payload uses strict parsing rather than compatibility import`(defect: MalformedPayload) {
        val raw = serialize(approvedDocument())
        val malformed = when (defect) {
            MalformedPayload.UNKNOWN_DOCUMENT_KEY -> "{\"unknownDocumentField\":true," + raw.drop(1)
            MalformedPayload.UNKNOWN_SOURCE_KEY -> raw.replaceFirst("\"api\":", "\"unknownSourceField\":true,\"api\":")
            MalformedPayload.DUPLICATE_DOCUMENT_KEY -> "{\"schemaVersion\":1," + raw.drop(1)
            MalformedPayload.DUPLICATE_SOURCE_KEY -> raw.replaceFirst("\"api\":\"Azora\"", "\"api\":\"Azora\",\"api\":\"Azora\"")
            MalformedPayload.TRAILING_VALUE -> "$raw\n{}"
            MalformedPayload.MALFORMED -> "{ not json"
            MalformedPayload.WRONG_TYPE -> raw.replace("\"schemaVersion\":1", "\"schemaVersion\":true")
        }
        val ex = assertThrows(BadRequestException::class.java) { policy.admitPayload(malformed) }
        assertEquals("MALFORMED_CONFIG_JSON", ex.code)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(RosterDefect::class)
    fun `payload requires exactly all reviewed unique APIs`(defect: RosterDefect) {
        val document = approvedDocument()
        val sources = document.sources
        val changed = when (defect) {
            RosterDefect.MISSING -> sources.dropLast(1)
            RosterDefect.EXTRA -> sources + sources.last().copy(api = "Unexpected")
            RosterDefect.DUPLICATE -> sources.dropLast(1) + sources.first()
            RosterDefect.REPLACED -> sources.dropLast(1) + sources.last().copy(api = "Unexpected")
        }
        assertThrows(InitialSourceCatalogPolicyRejected::class.java) {
            policy.admitPayload(serialize(document.copy(sources = changed)))
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(SourceField::class)
    fun `every source field is compared for Azora and non Azora input and stored bytes`(field: SourceField) {
        val reference = referenceDocument()
        listOf("Azora", "Tapas").forEach { api ->
            assertInputAndStoredDrift(api, field.change(reference.sources.single { it.api == api }))
        }
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(NestedDrift::class)
    fun `nested filter semantics and array declaration order are not projected away`(drift: NestedDrift) {
        val source = referenceDocument().sources.single { it.api == "Lekmanga" }
        assertInputAndStoredDrift(source.api, drift.change(source))
    }

    @Test
    fun `legacy content and legacy order are not an invented second reference gate`() {
        val document = approvedDocument()
        val legacy = document.sources.drop(12).reversed().map { it.copy(displayName = "Reviewed legacy ${it.api}") }
        val reordered = document.copy(sources = legacy.take(1) + document.sources.take(12) + legacy.drop(1))
        assertEquals(reordered, policy.admitPayload(serialize(reordered)).document)
        val retyped = document.copy(sources = document.sources.map { if (it.api == "Sussytoons") it.copy(engine = "generic") else it })
        assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.admitPayload(serialize(retyped)) }
    }

    @Test
    fun `staging permits active or withheld legacy but initial publication requires all withheld`() {
        val active = inventory(legacyStatus = ACTIVE)
        policy.requireStagedInventory(active.heads, active.assembly)
        val mixed = active.copy(
            heads = active.heads.mapIndexed { index, head ->
                if (head.api in LEGACY_APIS && index % 2 == 0) head.copy(status = WITHHELD) else head
            },
        )
        policy.requireStagedInventory(mixed.heads, mixed.assembly)
        listOf(active, mixed).forEach { state ->
            assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.requirePublicationInventory(state.heads, state.assembly) }
        }
        val withheld = inventory()
        policy.requireStagedInventory(withheld.heads, withheld.assembly)
        policy.requirePublicationInventory(withheld.heads, withheld.assembly)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(RosterDefect::class)
    fun `effective head inventory includes retained extras and drafts excluded from assembly`(defect: RosterDefect) {
        val state = inventory()
        val heads = state.heads
        val unexpectedDraft = heads.last().copy(api = "Unexpected", status = SourceLifecycleStatus.DRAFT, currentPublishedRevisionId = null)
        val changed = when (defect) {
            RosterDefect.MISSING -> heads.dropLast(1)
            RosterDefect.EXTRA -> heads + unexpectedDraft
            RosterDefect.DUPLICATE -> heads.dropLast(1) + heads.first()
            RosterDefect.REPLACED -> heads.dropLast(1) + unexpectedDraft
        }
        assertInventoryRejected(state.copy(heads = changed))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = SourceLifecycleStatus::class, names = ["ACTIVE"], mode = EnumSource.Mode.EXCLUDE)
    fun `generic heads must be active even if their published content matches`(status: SourceLifecycleStatus) {
        val state = inventory()
        assertInventoryRejected(state.copy(heads = state.heads.map { if (it.api == "Tapas") it.copy(status = status) else it }))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(value = SourceLifecycleStatus::class, names = ["DRAFT", "DISABLED", "RETIRED", "REMOVED"])
    fun `unsupported legacy head states cannot be silently adopted`(status: SourceLifecycleStatus) {
        val state = inventory()
        assertInventoryRejected(state.copy(heads = state.heads.map { if (it.api == "Sussytoons") it.copy(status = status) else it }))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @ValueSource(strings = ["Azora", "Tapas", "Sussytoons"])
    fun `every reviewed head needs the correct engine and a published pointer`(api: String) {
        val state = inventory()
        val wrongEngine = if (api in APPROVED_GENERIC_APIS) "legacy" else "generic"
        assertInventoryRejected(state.copy(heads = state.heads.map { if (it.api == api) it.copy(engine = wrongEngine) else it }))
        assertInventoryRejected(state.copy(heads = state.heads.map { if (it.api == api) it.copy(currentPublishedRevisionId = null) else it }))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(AssemblyDefect::class)
    fun `effective assembly has exactly twelve members bound to their lifecycle heads`(defect: AssemblyDefect) {
        val state = inventory()
        val first = state.assembly.first()
        val changed = when (defect) {
            AssemblyDefect.MISSING -> state.assembly.dropLast(1)
            AssemblyDefect.EXTRA -> state.assembly + first.copy(api = "Sussytoons")
            AssemblyDefect.DUPLICATE -> state.assembly + first
            AssemblyDefect.API -> listOf(first.copy(api = "Unexpected")) + state.assembly.drop(1)
            AssemblyDefect.HEAD_ID -> listOf(first.copy(sourceConfigId = UUID(99, 1))) + state.assembly.drop(1)
            AssemblyDefect.PUBLISHED_REVISION -> listOf(first.copy(sourceRevisionId = UUID(99, 2))) + state.assembly.drop(1)
            AssemblyDefect.POSITION -> listOf(first.copy(position = -1)) + state.assembly.drop(1)
            AssemblyDefect.ENGINE -> listOf(first.copy(engine = "legacy")) + state.assembly.drop(1)
            AssemblyDefect.LIFECYCLE -> listOf(first.copy(status = SourceLifecycleStatus.DISABLED)) + state.assembly.drop(1)
        }
        assertInventoryRejected(state.copy(assembly = changed))
    }

    @Test
    fun `effective order comes from position then api not repository return order or raw priority`() {
        val state = inventory()
        // Azora and Mangamello tie; the API tiebreak keeps their approved order. Absolute position
        // gaps do not rewrite the raw priority=0 values pinned in all twelve immutable models.
        fun position(old: Int): Int = if (old <= 1) 10 else old * 10
        val reordered = state.copy(
            heads = state.heads.reversed().map { it.copy(position = position(it.position)) },
            assembly = state.assembly.reversed().map { it.copy(position = position(it.position)) },
        )
        policy.requireStagedInventory(reordered.heads, reordered.assembly)
        policy.requirePublicationInventory(reordered.heads, reordered.assembly)
    }

    @Test
    fun `changed relative generic order is rejected for input and coherently positioned heads`() {
        val document = approvedDocument()
        val reordered = document.sources.toMutableList().apply { add(1, removeAt(0)) }
        assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.admitPayload(serialize(document.copy(sources = reordered))) }
        val state = inventory()
        fun position(old: Int): Int = when (old) {
            0 -> 1
            1 -> 0
            else -> old
        }
        assertInventoryRejected(
            state.copy(
                heads = state.heads.map { it.copy(position = position(it.position)) },
                assembly = state.assembly.map { it.copy(position = position(it.position)) },
            ),
        )
    }

    @Test
    fun `malformed stored content is a bounded effective state rejection not a request parsing error`() {
        val state = inventory()
        val malformed = "{\"untrustedStoredKey\":"
        val changed = state.assembly.map {
            if (it.api == "Tapas") it.copy(canonicalContent = malformed, checksum = Sha256.hexUtf8(malformed)) else it
        }
        val ex = assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.requirePublicationInventory(state.heads, changed) }
        assertEquals("effective initial generic content is malformed", ex.message)
        assertTrue(ex.cause is BadRequestException)
        assertInventoryRejected(state.copy(assembly = changed))
    }

    private fun assertInputAndStoredDrift(api: String, changed: SourceConfig) {
        val document = approvedDocument()
        assertNotEquals(document.sources.single { it.api == api }, changed, "mutation must actually change $api")
        val sources = document.sources.map { if (it.api == api) changed else it }
        assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.admitPayload(serialize(document.copy(sources = sources))) }
        val state = inventory()
        val raw = SourceConfigParser.canonicalSource(changed)
        val assembly = state.assembly.map { if (it.api == api) it.copy(canonicalContent = raw, checksum = Sha256.hexUtf8(raw)) else it }
        assertInventoryRejected(state.copy(assembly = assembly))
    }

    private fun assertInventoryRejected(state: Inventory) {
        assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.requireStagedInventory(state.heads, state.assembly) }
        assertThrows(InitialSourceCatalogPolicyRejected::class.java) { policy.requirePublicationInventory(state.heads, state.assembly) }
    }

    private fun inventory(legacyStatus: SourceLifecycleStatus = WITHHELD): Inventory {
        val sources = approvedDocument().sources
        val heads = sources.mapIndexed { position, source ->
            SourceConfigHead(
                id = UUID(0, position.toLong() + 1),
                api = source.api,
                displayName = source.displayName,
                language = source.language,
                engine = source.engine,
                status = if (source.engine == "generic") ACTIVE else legacyStatus,
                position = position,
                baseUrl = source.baseUrl,
                adult = source.siteState == "ADULT_18_PLUS",
                currentPublishedRevisionId = UUID(1, position.toLong() + 1),
                createdAt = NOW,
                updatedAt = NOW,
                publishedAt = NOW,
            )
        }
        val assembly = heads.filter { it.engine == "generic" }.map { head ->
            val canonical = SourceConfigParser.canonicalSource(sources.single { it.api == head.api })
            AssemblySource(
                api = head.api,
                position = head.position,
                engine = head.engine,
                status = head.status,
                canonicalContent = canonical,
                sourceConfigId = head.id,
                sourceRevisionId = requireNotNull(head.currentPublishedRevisionId),
                revisionNumber = 1,
                checksum = Sha256.hexUtf8(canonical),
                canonVersion = CanonicalJson.CANON_VERSION,
            )
        }
        return Inventory(heads, assembly)
    }

    private fun approvedDocument(): SourceConfigDocument {
        val reference = referenceDocument()
        return reference.copy(sources = reference.sources + LEGACY_APIS.map { SourceConfigFixtures.validLegacySource(it) })
    }

    private fun referenceBytes(): ByteArray = requireNotNull(javaClass.getResourceAsStream(REFERENCE_PATH)) {
        "missing independently pinned main resource"
    }.use { it.readBytes() }

    private fun referenceDocument(): SourceConfigDocument = SourceConfigParser.parseStrictDocument(referenceBytes().toString(Charsets.UTF_8))

    private fun serialize(document: SourceConfigDocument): String = SourceConfigParser.canonicalDocument(document)

    private fun reverseObjectKeys(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.entries.reversed().associate { (key, value) -> key to reverseObjectKeys(value) })
        is JsonArray -> JsonArray(element.map { reverseObjectKeys(it) })
        else -> element
    }

    private data class Inventory(val heads: List<SourceConfigHead>, val assembly: List<AssemblySource>)

    enum class ReferenceDefect {
        MISSING,
        TRUNCATED,
        CHANGED,
        UNSUPPORTED_SCHEMA,
        INVALID_UTF8,
        READ_FAILURE,
    }

    enum class MalformedPayload {
        UNKNOWN_DOCUMENT_KEY,
        UNKNOWN_SOURCE_KEY,
        DUPLICATE_DOCUMENT_KEY,
        DUPLICATE_SOURCE_KEY,
        TRAILING_VALUE,
        MALFORMED,
        WRONG_TYPE,
    }

    enum class RosterDefect {
        MISSING,
        EXTRA,
        DUPLICATE,
        REPLACED,
    }

    enum class AssemblyDefect {
        MISSING,
        EXTRA,
        DUPLICATE,
        API,
        HEAD_ID,
        PUBLISHED_REVISION,
        POSITION,
        ENGINE,
        LIFECYCLE,
    }

    enum class SourceField {
        API,
        LANGUAGE,
        DISPLAY_NAME,
        BASE_URL,
        IMAGE_BASE,
        ENABLED,
        PRIORITY,
        ENGINE,
        MIN_APP_VERSION,
        HEADERS,
        USES_CAPTURED_HEADERS,
        PAGINATION,
        ENDPOINTS,
        FIELDS,
        BLACKLIST_GENRES,
        SITE_STATE,
        LIFECYCLE,
        PREVIOUS_HOSTS,
        PREVIOUS_IMAGE_HOSTS,
        TRUSTED_HOSTS,
        ICON,
        FILTERS,
        ;

        fun change(source: SourceConfig): SourceConfig = when (this) {
            API -> source.copy(api = "${source.api} changed")
            LANGUAGE -> source.copy(language = "fr")
            DISPLAY_NAME -> source.copy(displayName = "${source.displayName} changed")
            BASE_URL -> source.copy(baseUrl = "https://changed.example")
            IMAGE_BASE -> source.copy(imageBase = "https://changed.example")
            ENABLED -> source.copy(enabled = !source.enabled)
            PRIORITY -> source.copy(priority = source.priority + 1)
            ENGINE -> source.copy(engine = "legacy")
            MIN_APP_VERSION -> source.copy(minAppVersion = "9.9.9")
            HEADERS -> source.copy(headers = source.headers + ("X-Policy-Test" to "changed"))
            USES_CAPTURED_HEADERS -> source.copy(usesCapturedHeaders = !source.usesCapturedHeaders)
            PAGINATION -> source.copy(pagination = source.pagination.copy(start = source.pagination.start + 1))
            ENDPOINTS -> source.copy(
                endpoints = source.endpoints + ("details" to source.endpoints.getValue("details").copy(url = "{itemUrl}?changed=1")),
            )

            FIELDS -> source.copy(fields = source.fields + ("chapter.date" to source.fields.getValue("chapter.date").copy(dateStrategy = "changed")))
            BLACKLIST_GENRES -> source.copy(blacklistGenres = source.blacklistGenres + "changed")
            SITE_STATE -> source.copy(siteState = "UNDER_MAINTENANCE")
            LIFECYCLE -> source.copy(lifecycle = "disabled")
            PREVIOUS_HOSTS -> source.copy(previousHosts = source.previousHosts + "changed.example")
            PREVIOUS_IMAGE_HOSTS -> source.copy(previousImageHosts = source.previousImageHosts + "changed.example")
            TRUSTED_HOSTS -> source.copy(trustedHosts = source.trustedHosts + "changed.example")
            ICON -> source.copy(icon = IconSpec(resourceKey = "changed"))
            FILTERS -> source.copy(filters = source.filters + SourceConfigFixtures.validFilter("policy_drift"))
        }
    }

    enum class NestedDrift {
        FILTER_REQUEST,
        FILTER_ORDER,
        OPTION_ORDER,
        TRANSFORM_ORDER,
        ;

        fun change(source: SourceConfig): SourceConfig {
            val firstFilter = source.filters.first()
            return when (this) {
                FILTER_REQUEST -> source.copy(
                    filters = listOf(firstFilter.copy(request = firstFilter.request.copy(param = "changed"))) + source.filters.drop(1),
                )

                FILTER_ORDER -> source.copy(filters = source.filters.reversed())
                OPTION_ORDER -> source.copy(filters = listOf(firstFilter.copy(options = firstFilter.options.reversed())) + source.filters.drop(1))
                TRANSFORM_ORDER -> {
                    val field = source.fields.getValue("chapter.number")
                    source.copy(fields = source.fields + ("chapter.number" to field.copy(transform = field.transform.reversed())))
                }
            }
        }
    }

    private companion object {
        const val REFERENCE_PATH = "/source-config/bootstrap/app-bundle-v6-generic.json"
        const val REFERENCE_SHA256 = "42a26ca29182a0c8c1150196ff55979fc41a8d828ed60556e9dcf6062b8b9095"
        val NOW: Instant = Instant.parse("2026-09-12T00:00:00Z")
    }
}
