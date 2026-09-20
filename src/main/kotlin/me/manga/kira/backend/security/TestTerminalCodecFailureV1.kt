package me.manga.kira.backend.security

import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryException
import me.manga.kira.backend.common.infrastructure.persistence.PersistenceBoundaryFailureCode
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.complaint.domain.terminal.TestTerminalExceptionV1
import java.util.concurrent.CancellationException

internal enum class TestTerminalCodecFailureV1 {
    INVALID_INPUT,
    LIMIT_EXCEEDED,
    KEY_FAILURE,
    KEY_CLEANUP_FAILURE,
    DEADLINE_EXHAUSTED,
    CRYPTO_FAILURE,
    AUTHENTICATION_FAILED,
}

internal class TestTerminalCodecExceptionV1(val code: TestTerminalCodecFailureV1) :
    RuntimeException("TEST terminal codec rejected operation: ${code.name}", null, false, false)

internal fun requireTestTerminalCodec(condition: Boolean, code: TestTerminalCodecFailureV1 = TestTerminalCodecFailureV1.INVALID_INPUT) {
    if (!condition) throw TestTerminalCodecExceptionV1(code)
}

/** No caller/provider diagnostic graph or conversion from local cryptography into durable authority. */
@Suppress("TooGenericExceptionCaught")
internal fun <T> testTerminalCodecBoundary(action: () -> T): T = try {
    action()
} catch (failure: Exception) {
    throw when (failure) {
        is TestTerminalCodecExceptionV1 -> failure
        is TestTerminalExceptionV1 -> TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.valueOf(failure.code.name))
        is OwnerDeleteAllJournalException -> TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.valueOf(failure.code.name))
        is PersistencePhaseException -> failure
        is PersistenceBoundaryException -> TestTerminalCodecExceptionV1(
            if (failure.code == PersistenceBoundaryFailureCode.TIME_BUDGET_EXHAUSTED) {
                TestTerminalCodecFailureV1.DEADLINE_EXHAUSTED
            } else {
                TestTerminalCodecFailureV1.INVALID_INPUT
            },
        )
        is CancellationException -> CancellationException("TEST terminal codec operation cancelled.")
        is InterruptedException -> {
            Thread.currentThread().interrupt()
            InterruptedException("TEST terminal codec operation interrupted.")
        }
        else -> TestTerminalCodecExceptionV1(TestTerminalCodecFailureV1.INVALID_INPUT)
    }
}
