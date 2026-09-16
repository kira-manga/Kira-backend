package me.manga.kira.backend.common.infrastructure.persistence

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import java.nio.file.Path
import java.util.UUID
import java.util.stream.Stream

/** Real PostgreSQL is mandatory here: a missing service, failed observer, killed child or absent receipt is a test failure, never a skip. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
internal class PersistenceJdbcDatabaseLifecycleTest {
    @TempDir
    lateinit var root: Path

    private lateinit var database: PgLifecycleDatabaseFixture

    @BeforeAll
    fun startOwnedDatabase() {
        database = PgLifecycleDatabaseFixture()
        database.start()
    }

    @AfterAll
    fun stopOwnedDatabase() {
        if (::database.isInitialized) database.close()
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("constructorRows")
    fun `D13 exact constructor matrix preserves genuine strong disposal and witnessed server session removal`(case: PgLifecycleDatabaseCase) {
        run(case)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PgLifecycleDatabaseLane::class)
    fun `same root reuses its physical slot and stale alias cannot retire a positively witnessed successor`(lane: PgLifecycleDatabaseLane) {
        run(PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, lane, PgLifecycleDatabaseMode.REUSE))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @EnumSource(PgLifecycleDatabaseLane::class)
    fun `wrong synthetic password proves real SCRAM refusal without inventing an authenticated session`(lane: PgLifecycleDatabaseLane) {
        run(PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, lane, PgLifecycleDatabaseMode.WRONG_PASSWORD))
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("establishmentRows")
    fun `real authenticated establishment faults deadlines late returns and server role preserve both policies`(case: PgLifecycleDatabaseCase) {
        run(case)
    }

    @ParameterizedTest(name = "{displayName} [{index}] {argumentsWithNames}")
    @MethodSource("originalProviderRows")
    fun `original provider keeps weaker raw and no raw constructor dispositions with deletion unavailable`(case: PgLifecycleDatabaseCase) {
        run(case)
    }

    private fun run(case: PgLifecycleDatabaseCase) {
        val directory = pgLifecycleDatabasePrivateDirectory(root.resolve(UUID.randomUUID().toString()))
        PgLifecycleDatabaseScenario(database).verify(directory, case)
    }

    companion object {
        @JvmStatic
        fun constructorRows(): Stream<Arguments> = PgLifecycleDatabaseLane.entries.flatMap { lane ->
            (0..1).flatMap { queryTimeout ->
                PgLifecycleDatabaseRecipe.entries.map { recipe -> Arguments.of(PgLifecycleDatabaseCase(recipe, queryTimeout, lane)) }
            }
        }.stream()

        @JvmStatic
        fun establishmentRows(): Stream<Arguments> = PgLifecycleDatabaseLane.entries.flatMap { lane ->
            PgLifecycleDatabaseCase.ESTABLISHMENT.map { mode ->
                Arguments.of(PgLifecycleDatabaseCase(PgLifecycleDatabaseRecipe.DEFAULT, 0, lane, mode))
            }
        }.stream()

        @JvmStatic
        fun originalProviderRows(): Stream<Arguments> = (
            PgLifecycleDatabaseRecipe.entries.flatMap { recipe ->
                (0..1).map { timeout ->
                    Arguments.of(PgLifecycleDatabaseCase(recipe, timeout, PgLifecycleDatabaseLane.ORDINARY, PgLifecycleDatabaseMode.ORIGINAL_MATRIX))
                }
            } + Arguments.of(
                PgLifecycleDatabaseCase(
                    PgLifecycleDatabaseRecipe.DEFAULT,
                    0,
                    PgLifecycleDatabaseLane.ORDINARY,
                    PgLifecycleDatabaseMode.ORIGINAL_LATE_RETURN,
                ),
            )
            ).stream()
    }
}
