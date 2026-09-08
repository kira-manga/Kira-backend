package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Enumeration
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.LogRecord
import java.util.logging.Logger

internal class BootstrapForeignLogger(name: String) : Logger(name, null) {
    var armed = false
    var inspections = 0
    var parentCalls = 0

    override fun getName(): String = inspect { super.getName() }
    override fun getLevel(): Level? = inspect { super.getLevel() }
    override fun isLoggable(level: Level): Boolean = inspect { super.isLoggable(level) }
    override fun hashCode(): Int = inspect { super.hashCode() }
    override fun equals(other: Any?): Boolean = inspect { super.equals(other) }
    override fun toString(): String = inspect { "SyntheticForeignLogger" }

    override fun setParent(parent: Logger) {
        if (armed) {
            parentCalls++
            throw AssertionError("Synthetic reparent callback")
        }
        super.setParent(parent)
    }

    private fun <T> inspect(action: () -> T): T {
        if (armed) {
            inspections++
            throw AssertionError("Synthetic foreign logger inspection")
        }
        return action()
    }
}

/** Selected only by one owned child's explicit startup option. */
class BootstrapForeignLogManager : LogManager() {
    var armed = false
    var inspections = 0

    override fun getLoggerNames(): Enumeration<String> = inspect { super.getLoggerNames() }
    override fun getLogger(name: String): Logger? = inspect { super.getLogger(name) }
    override fun getProperty(name: String): String? = inspect { super.getProperty(name) }

    private fun <T> inspect(action: () -> T): T {
        if (armed) {
            inspections++
            throw AssertionError("Synthetic foreign manager inspection")
        }
        return action()
    }
}

internal object BootstrapHandlerCounter {
    var constructed = 0
}

class BootstrapSyntheticHandler : Handler() {
    init {
        BootstrapHandlerCounter.constructed++
    }

    override fun publish(record: LogRecord) = Unit
    override fun flush() = Unit
    override fun close() = Unit
}

internal class BootstrapSyntheticLevel : Level("KIRA_SYNTHETIC_LEVEL", 850, "kira.bootstrap.b07.fixture.SyntheticLevels")
