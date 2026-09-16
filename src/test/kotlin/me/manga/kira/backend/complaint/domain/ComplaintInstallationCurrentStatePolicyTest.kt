package me.manga.kira.backend.complaint.domain

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.util.UUID

class ComplaintInstallationCurrentStatePolicyTest {
    @Test
    fun `disabled missing control and unconfigured seeds cannot create authority`() {
        assertEquals(
            ComplaintInstallationCurrentStateAssessment.DISABLED,
            ComplaintInstallationCurrentStatePolicy.assess(ComplaintInstallationDesiredSettings.Disabled, RUN, null),
        )
        assertEquals(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, assess(control = null))
        val seed = control(hash = null, database = null, restore = null, maintenance = true, creation = true, scan = true)
        assertEquals(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, assess(control = seed))
        assertEquals(ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE, assess(control = control(hash = null)))
        assertEquals(
            ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE,
            assess(control = control(database = null, restore = null)),
        )
        assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, assess())
    }

    @Test
    fun `each independent desired field must match before scope diagnostics and every hash byte participates`() {
        val mismatchedControls = listOf(
            control(scope = RUN),
            control(generation = 2),
            control(database = OTHER.id),
            control(restore = OTHER.id),
        )
        mismatchedControls.forEach { current ->
            assertEquals(ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH, assess(control = current))
            assertEquals(
                ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH,
                assess(requested = RUN, control = current, run = ComplaintInstallationRunObservation.Absent(RUN)),
            )
        }
        repeat(32) { index ->
            val changed = bytes().also { it[index] = (it[index].toInt() xor 1).toByte() }
            assertEquals(ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH, assess(control = control(hash = changed)))
            assertEquals(
                ComplaintInstallationCurrentStateAssessment.DESIRED_BINDING_MISMATCH,
                assess(desired(RUN), RUN, control(RUN), run(hash = changed)),
            )
        }
    }

    @Test
    fun `maintenance creation scan and ordinary journal flags alone neither add refusal nor supply provenance`() {
        listOf(ComplaintDataScope.LIVE, RUN).forEach { scope ->
            val unknown = control(scope = scope, journal = null)
            assertNull(unknown.journalDegraded)
            assertEquals(
                ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED,
                assess(desired(scope), scope, unknown, if (scope.testOnly) run() else null),
            )
            repeat(16) { flags ->
                val current = control(
                    scope = scope,
                    maintenance = flags and 1 != 0,
                    creation = flags and 2 != 0,
                    scan = flags and 4 != 0,
                    journal = flags and 8 != 0,
                )
                val run = if (scope.testOnly) run() else null
                assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, assess(desired(scope), scope, current, run))
            }
        }
        // These observations contain no authenticated catalog/projection/replay or activation evidence.
        // No test here claims session availability, restore clearance, row locks or real TEST admission.
    }

    @Test
    fun `exact absent or terminal requested TEST scope is retired without an installation lookup even under LIVE`() {
        val retired = listOf<ComplaintInstallationRunObservation>(ComplaintInstallationRunObservation.Absent(RUN)) +
            ComplaintInstallationRunState.entries.filter { it != ComplaintInstallationRunState.ACTIVE }.map { run(state = it, hash = ByteArray(32)) }
        listOf(ComplaintDataScope.LIVE, RUN, OTHER).forEach { currentScope ->
            retired.forEach { observed ->
                assertEquals(
                    ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_RETIRED,
                    assess(desired(currentScope), RUN, control(currentScope), observed),
                )
            }
        }
    }

    @Test
    fun `unknown or foreign run observations are unavailable and exact ACTIVE wrong scope is mismatch not retirement`() {
        val foreign = listOf(
            null,
            ComplaintInstallationRunObservation.Absent(OTHER),
            run(scope = OTHER),
            run(scope = OTHER, state = ComplaintInstallationRunState.PURGED),
        )
        foreign.forEach { observation ->
            assertEquals(
                ComplaintInstallationCurrentStateAssessment.CURRENT_STATE_UNAVAILABLE,
                assess(desired(RUN), RUN, control(RUN), observation),
            )
        }
        listOf(ComplaintDataScope.LIVE, OTHER).forEach { currentScope ->
            assertEquals(
                ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH,
                assess(desired(currentScope), RUN, control(currentScope), run()),
            )
        }
        assertEquals(
            ComplaintInstallationCurrentStateAssessment.INSTALLATION_SCOPE_MISMATCH,
            assess(desired(RUN), ComplaintDataScope.LIVE, control(RUN)),
        )
        assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, assess(desired(RUN), RUN, control(RUN), run()))
    }

    @Test
    fun `malformed observations reject before becoming absence and malformed desired objects have no bypass`() {
        listOf(0, 2).forEach { schema -> invalidObservation { control(schema = schema) } }
        listOf(0L, -1L).forEach { generation -> invalidObservation { control(generation = generation) } }
        listOf(0, 31, 33).forEach { size ->
            invalidObservation { control(hash = ByteArray(size)) }
            invalidObservation { run(hash = ByteArray(size)) }
        }
        invalidObservation { control(scope = RUN, testOnly = false) }
        invalidObservation { control(testOnly = true) }
        invalidObservation { control(database = null) }
        invalidObservation { control(restore = null) }
        invalidObservation { control(database = ComplaintDataScope.LIVE.id) }
        invalidObservation { control(restore = UUID.fromString("aaaaaaaa-bbbb-1ccc-8ddd-eeeeeeeeeeee")) }
        invalidObservation { ComplaintInstallationRunObservation.Absent(ComplaintDataScope.LIVE) }
        invalidObservation { run(scope = ComplaintDataScope.LIVE) }
        invalidObservation { run(testOnly = false) }
        listOf(ComplaintInstallationMode.DISABLED, ComplaintInstallationMode.PRE_CUTOVER_TEST).forEach { mode ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                ComplaintInstallationDesiredSettings.Configured(mode, 1, 1, ComplaintDataScope.LIVE, DATABASE, RESTORE, bytes())
            }
            assertEquals("Invalid installation desired settings", failure.message)
            assertNull(failure.cause)
        }
    }

    @Test
    fun `observation digests copy in both directions and all diagnostics remain redacted`() {
        val input = bytes()
        val expected = input.copyOf()
        val current = control(RUN, hash = input)
        val active = run(hash = input)
        input.fill(77)
        current.configurationHashBytes()!!.fill(88)
        active.configurationHashBytes().fill(99)
        assertArrayEquals(expected, current.configurationHashBytes())
        assertArrayEquals(expected, active.configurationHashBytes())
        assertEquals(ComplaintInstallationCurrentStateAssessment.PROVENANCE_REQUIRED, assess(desired(RUN), RUN, current, active))
        assertEquals("ComplaintInstallationControlObservation(redacted)", current.toString())
        assertEquals("ComplaintInstallationRunObservation.Present(redacted)", active.toString())
        assertEquals("ComplaintInstallationRunObservation.Absent(redacted)", ComplaintInstallationRunObservation.Absent(RUN).toString())
    }

    private fun assess(
        desired: ComplaintInstallationDesiredSettings = desired(),
        requested: ComplaintDataScope = ComplaintDataScope.LIVE,
        control: ComplaintInstallationControlObservation? = control(),
        run: ComplaintInstallationRunObservation? = null,
    ): ComplaintInstallationCurrentStateAssessment = ComplaintInstallationCurrentStatePolicy.assess(desired, requested, control, run)

    private fun desired(scope: ComplaintDataScope = ComplaintDataScope.LIVE): ComplaintInstallationDesiredSettings.Configured =
        ComplaintInstallationDesiredSettings.Configured(
            if (scope.testOnly) ComplaintInstallationMode.PRE_CUTOVER_TEST else ComplaintInstallationMode.LIVE,
            1,
            1,
            scope,
            DATABASE,
            RESTORE,
            bytes(),
        )

    private fun control(
        scope: ComplaintDataScope = ComplaintDataScope.LIVE,
        schema: Int = 1,
        generation: Long = 1,
        hash: ByteArray? = bytes(),
        database: UUID? = DATABASE,
        restore: UUID? = RESTORE,
        testOnly: Boolean = scope.testOnly,
        maintenance: Boolean = false,
        creation: Boolean = false,
        scan: Boolean = false,
        journal: Boolean? = false,
    ): ComplaintInstallationControlObservation = ComplaintInstallationControlObservation(
        scope,
        testOnly,
        schema,
        generation,
        hash,
        database,
        restore,
        maintenance,
        creation,
        scan,
        journal,
    )

    private fun run(
        scope: ComplaintDataScope = RUN,
        state: ComplaintInstallationRunState = ComplaintInstallationRunState.ACTIVE,
        hash: ByteArray = bytes(),
        testOnly: Boolean = true,
    ): ComplaintInstallationRunObservation.Present = ComplaintInstallationRunObservation.Present(scope, testOnly, state, hash)

    private fun invalidObservation(operation: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
        assertEquals("Invalid installation current-state observation", failure.message)
        assertNull(failure.cause)
    }

    private fun bytes(): ByteArray = ByteArray(32) { (it + 1).toByte() }

    private companion object {
        val DATABASE: UUID = UUID.fromString("aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee")
        val RESTORE: UUID = UUID.fromString("bbbbbbbb-cccc-4ddd-8eee-ffffffffffff")
        val RUN: ComplaintDataScope = ComplaintIdentifiers.dataScope("cccccccc-dddd-4eee-8fff-aaaaaaaaaaaa")
        val OTHER: ComplaintDataScope = ComplaintIdentifiers.dataScope("dddddddd-eeee-4fff-8aaa-bbbbbbbbbbbb")
    }
}
