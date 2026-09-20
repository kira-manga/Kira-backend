package me.manga.kira.backend.common.infrastructure.persistence

import java.util.logging.Level
import java.util.logging.Logger

/** Fresh require-child logging instrumentation after whole prepare; not production quiet-profile proof. */
internal class PgRequireErrorObservation private constructor(
    private val logger: Logger,
    private val ownedThread: Thread,
    capture: () -> Unit,
    private val realDriver: Boolean,
) : AutoCloseable {
    private val previousLevel: Level? = logger.level
    private val previousParents = logger.useParentHandlers
    private val handler = PgRequireErrorHandler(ownedThread, capture)
    private var restored = false

    fun requireObserved() = handler.requireObserved()

    private fun install(): PgRequireErrorObservation {
        check(logger.filter == null && logger.handlers.isEmpty()) { "Synthetic observer requires an unfiltered leaf without handlers." }
        var installed = false
        try {
            logger.useParentHandlers = false
            logger.addHandler(handler)
            logger.level = Level.FINEST
            installed = true
            return this
        } finally {
            if (!installed) restoreLogger()
        }
    }

    override fun close() {
        if (restored) return
        check(!ownedThread.isAlive) { "Synthetic observer cannot restore logging before its actual owned Thread exits." }
        restoreLogger()
        restored = true
        if (realDriver) {
            println(
                "PG_REQUIRE_OBSERVER_CLEANUP thread_id=${ownedThread.threadId()} all_terminated=true own_handler_removed=true " +
                    "level_restored=true parent_handlers_restored=true proof=TEST_ONLY_LOGGER_INSTRUMENTATION",
            )
        }
    }

    private fun restoreLogger() {
        val removal = runCatching { logger.removeHandler(handler) }
        val level = runCatching { logger.level = previousLevel }
        val parents = runCatching { logger.useParentHandlers = previousParents }
        removal.getOrThrow()
        level.getOrThrow()
        parents.getOrThrow()
        check(logger.handlers.none { it === handler } && logger.level === previousLevel && logger.useParentHandlers == previousParents)
    }

    companion object {
        fun install(ownedThread: Thread, capture: () -> Unit): PgRequireErrorObservation {
            check(ownedThread.state === Thread.State.NEW)
            val logger = Logger.getLogger(PgRequireErrorHandler.LOGGER_NAME)
            check(logger.javaClass === Logger::class.java && logger.name == PgRequireErrorHandler.LOGGER_NAME)
            return PgRequireErrorObservation(logger, ownedThread, capture, realDriver = true).install()
        }

        /** Isolated anonymous MODEL logger only; this does not bind a real driver event. */
        fun model(logger: Logger, ownedThread: Thread, capture: () -> Unit): PgRequireErrorObservation {
            check(logger.name == null)
            return PgRequireErrorObservation(logger, ownedThread, capture, realDriver = false).install()
        }
    }
}
