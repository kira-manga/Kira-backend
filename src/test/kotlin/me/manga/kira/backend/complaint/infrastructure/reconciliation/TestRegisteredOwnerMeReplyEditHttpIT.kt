package me.manga.kira.backend.complaint.infrastructure.reconciliation

import me.manga.kira.backend.common.infrastructure.persistence.PgLifecycleDatabaseFixture
import me.manga.kira.backend.common.infrastructure.persistence.VersionBoundPersistenceConnectedFixture
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

/** SOURCE ONLY / NOT_COMPILED / NOT_RUN. Explicit registered TEST selection, not default or LIVE activation. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Execution(ExecutionMode.SAME_THREAD)
class TestRegisteredOwnerMeReplyEditHttpIT {
    private val database = lazy { PgLifecycleDatabaseFixture(TestRegisteredOwnerMeReplyEditHttpIT::class.java).also { it.start() } }
    @AfterAll fun closeDatabase() { if (database.isInitialized()) database.value.close() }

    @Test fun oneOriginalListenerFollowsEnrollmentLocationThenCreatesRepliesEditsAndReplaysExactReceipts() = withFixture {
        TestRegisteredOwnerMeReplyEditHttpCasesV1.oneListenerAndExactReceipts(it)
    }
    @Test fun createOnlyBirthRefusesCombinedStartupAndOlderMeListenerStillExcludesReplyAndEdit() = withFixture {
        TestRegisteredOwnerMeReplyEditHttpCasesV1.createOnlyAndOlderMe(it)
    }
    @Test fun replyOnlyBirthRefusesCombinedStartupAndOlderReplyListenerStillExcludesMeAndEdit() = withFixture {
        TestRegisteredOwnerMeReplyEditHttpCasesV1.replyOnlyAndOlderReply(it)
    }
    @Test fun absentCreateDeclarationCannotRetainCombinedStartupOrInitializeJpaAndListener() = withFixture {
        TestRegisteredOwnerMeReplyEditHttpCasesV1.absentDeclaration(it)
    }

    private fun withFixture(action: (VersionBoundPersistenceConnectedFixture) -> Unit) =
        VersionBoundPersistenceConnectedFixture(database.value, testActivation = true, activeFirstCut = true).use { it.bind(); action(it) }
}
