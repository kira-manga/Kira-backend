package me.manga.kira.backend.common.infrastructure.persistence

import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.util.Properties
import java.util.function.BiFunction
import java.util.logging.Filter
import java.util.logging.Level
import java.util.logging.LogManager
import java.util.logging.Logger

internal object BootstrapJulCases {
    fun verify(mode: BootstrapProbeCase, root: Path) {
        BootstrapDriverLoader(root, mode).use { loader ->
            when (mode) {
                BootstrapProbeCase.JUL_LEVEL_MATRIX -> levelMatrix(loader)

                BootstrapProbeCase.JUL_ANCESTOR_LATENT -> ancestor(loader)

                BootstrapProbeCase.JUL_EXISTING_CHILD -> existingChild(loader)

                BootstrapProbeCase.JUL_FILTER_NOT_AUTHORITY -> filterNotAuthority(loader)

                BootstrapProbeCase.JUL_CUSTOM_LOGGER, BootstrapProbeCase.JUL_CUSTOM_ANCESTOR, BootstrapProbeCase.JUL_FOREIGN_DESCENDANT,
                BootstrapProbeCase.JUL_CONFIGURED_ANCESTOR_FOREIGN, BootstrapProbeCase.JUL_UNRELATED_CUSTOM,
                -> foreignLogger(loader, mode)

                BootstrapProbeCase.JUL_CUSTOM_MANAGER -> foreignManager(loader)

                BootstrapProbeCase.JUL_TARGET_HANDLER, BootstrapProbeCase.JUL_ANCESTOR_HANDLER, BootstrapProbeCase.JUL_EMPTY_HANDLER ->
                    latentHandler(loader, mode)

                BootstrapProbeCase.JUL_LOCALIZED_C1 -> localizedC1(loader)

                BootstrapProbeCase.JUL_DRIFT -> drift(loader)

                else -> error("Unimplemented JUL probe")
            }
        }
    }

    fun pendingLevel(root: Path, raw: String, allowed: Boolean) {
        BootstrapDriverLoader(root, BootstrapProbeCase.PENDING_LEVEL).use { loader ->
            val manager = LogManager.getLogManager()
            assertMissingLeaf(manager)
            updateMissingLevel(manager, raw)
            check(manager.getProperty("$BOOTSTRAP_LEAF.level") == raw)
            assertMissingLeaf(manager)
            if (allowed) {
                PersistenceDriverBootstrap.prepareWithLoader(loader)
            } else {
                expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
            }
            requireSyntheticDriverCold()
            check(manager.getProperty("$BOOTSTRAP_LEAF.level") == raw)
            println("BOOTSTRAP_PENDING_LEVEL expected_allowed=$allowed verified=true patched=false")
        }
    }

    private fun levelMatrix(loader: BootstrapDriverLoader) {
        val original = PersistenceDriverBootstrap.prepareWithLoader(loader)
        check(original.toString() == "PreparedPersistenceDriver")
        val verbose = listOf(Level.FINE, Level.FINER, Level.FINEST, Level.ALL)
        var rejected = 0
        for (name in PersistenceLoggerNames.postgres) {
            val logger = Logger.getLogger(name)
            val prior = logger.level
            for (level in verbose) {
                logger.level = level
                expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
                check(logger.level === level)
                rejected++
            }
            logger.level = prior
        }
        val root = Logger.getLogger("org.postgresql")
        for (level in listOf(Level.INFO, Level.WARNING, Level.SEVERE, Level.OFF, Level.CONFIG)) {
            root.level = level
            PersistenceDriverBootstrap.prepareWithLoader(loader)
            check(root.level === level)
        }
        requireSyntheticDriverCold()
        check(rejected == 120)
        println("BOOTSTRAP_JUL_MATRIX emitters=30 verbose_rejections=120 nonverbose_inheritance=5")
    }

    private fun ancestor(loader: BootstrapDriverLoader) {
        val manager = LogManager.getLogManager()
        check(manager.getLogger("org") == null)
        configure(manager, mapOf(".level" to "INFO", "org.level" to "FINE"))
        check(manager.getLogger("org") == null)
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        requireSyntheticDriverCold()
        check(loader.driverLoads == 0)
        check(manager.getLogger("org").level === Level.FINE)
    }

    private fun existingChild(loader: BootstrapDriverLoader) {
        val parent = Logger.getLogger("org.postgresql").apply { level = Level.INFO }
        val child = Logger.getLogger("org.postgresql.bootstrap.dynamic").apply { level = Level.FINE }
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(parent.level === Level.INFO && child.level === Level.FINE)
        child.level = Level.INFO
        val lookalike = Logger.getLogger("org.postgresqlExtra.bootstrap").apply { level = Level.FINE }
        PersistenceDriverBootstrap.prepareWithLoader(loader)
        check(lookalike.level === Level.FINE)
        requireSyntheticDriverCold()
    }

    private fun filterNotAuthority(loader: BootstrapDriverLoader) {
        val logger = Logger.getLogger(BOOTSTRAP_LEAF).apply { level = Level.FINE }
        var filterCalls = 0
        logger.filter = Filter {
            filterCalls++
            false
        }
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(filterCalls == 0)
        logger.fine("Synthetic downstream-filter positive control")
        check(filterCalls == 1)
        requireSyntheticDriverCold()
    }

    private fun foreignLogger(loader: BootstrapDriverLoader, mode: BootstrapProbeCase) {
        val manager = LogManager.getLogManager()
        val name = when (mode) {
            BootstrapProbeCase.JUL_CUSTOM_LOGGER -> BOOTSTRAP_LEAF
            BootstrapProbeCase.JUL_CUSTOM_ANCESTOR -> "org"
            BootstrapProbeCase.JUL_FOREIGN_DESCENDANT -> "$BOOTSTRAP_LEAF.foreign"
            BootstrapProbeCase.JUL_CONFIGURED_ANCESTOR_FOREIGN -> "org.unrelated.bootstrap"
            else -> "org.postgresqlExtra.unrelated"
        }
        val foreign = BootstrapForeignLogger(name)
        check(manager.addLogger(foreign))
        if (mode == BootstrapProbeCase.JUL_CONFIGURED_ANCESTOR_FOREIGN) {
            configure(manager, mapOf(".level" to "INFO", "org.level" to "INFO"))
            check(manager.getLogger("org") == null)
        }
        foreign.armed = true
        if (mode == BootstrapProbeCase.JUL_UNRELATED_CUSTOM) {
            PersistenceDriverBootstrap.prepareWithLoader(loader).construct()
        } else {
            expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
            requireSyntheticDriverCold()
        }
        check(foreign.inspections == 0 && foreign.parentCalls == 0)
        check(manager.getLogger(name) === foreign)
        when (mode) {
            BootstrapProbeCase.JUL_FOREIGN_DESCENDANT, BootstrapProbeCase.JUL_CONFIGURED_ANCESTOR_FOREIGN -> {
                // The unchanged configuration would call the foreign child's setParent during materialization.
                check(runCatching { Logger.getLogger(BOOTSTRAP_LEAF) }.exceptionOrNull() is AssertionError)
                check(foreign.parentCalls == 1)
            }

            else -> {
                check(runCatching { foreign.isLoggable(Level.INFO) }.exceptionOrNull() is AssertionError)
                check(foreign.inspections == 1)
            }
        }
        foreign.armed = false // Keep only the bootstrap/positive-control interval hostile, not JUL's JVM shutdown hook.
        println("BOOTSTRAP_FOREIGN_LOGGER negative_callbacks=0 positive_callback=1")
    }

    private fun foreignManager(loader: BootstrapDriverLoader) {
        val manager = LogManager.getLogManager() as BootstrapForeignLogManager
        manager.armed = true
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(manager.inspections == 0 && loader.driverLoads == 0)
        requireSyntheticDriverCold()
        check(runCatching { manager.getProperty("synthetic-positive-control") }.exceptionOrNull() is AssertionError)
        check(manager.inspections == 1)
        manager.armed = false
    }

    private fun latentHandler(loader: BootstrapDriverLoader, mode: BootstrapProbeCase) {
        val manager = LogManager.getLogManager()
        val target = if (mode == BootstrapProbeCase.JUL_ANCESTOR_HANDLER) "org" else BOOTSTRAP_LEAF
        check(manager.getLogger(target) == null)
        val value = if (mode == BootstrapProbeCase.JUL_EMPTY_HANDLER) "" else BootstrapSyntheticHandler::class.java.name
        configure(manager, mapOf(".level" to "INFO", "$target.handlers" to value))
        check(BootstrapHandlerCounter.constructed == 0)
        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        check(BootstrapHandlerCounter.constructed == 0 && manager.getLogger(target) == null)
        requireSyntheticDriverCold()
        Logger.getLogger(BOOTSTRAP_LEAF)
        val expected = if (mode == BootstrapProbeCase.JUL_EMPTY_HANDLER) 0 else 1
        check(BootstrapHandlerCounter.constructed == expected)
        println("BOOTSTRAP_HANDLER before=0 after_same_path_positive=$expected")
    }

    private fun localizedC1(loader: BootstrapDriverLoader) {
        val manager = LogManager.getLogManager()
        LoggerFactory.getILoggerFactory() // Complete ordinary logging setup BEFORE the custom Level exists.
        assertMissingLeaf(manager)
        check(System.getProperty(BOOTSTRAP_BUNDLE_MARKER) == null)
        val customLevel = BootstrapSyntheticLevel()
        check(System.getProperty(BOOTSTRAP_BUNDLE_MARKER) == null)
        updateMissingLevel(manager, "KIRA_SYNTHETIC_LOCALIZED")
        check(manager.getProperty("$BOOTSTRAP_LEAF.level") == "KIRA_SYNTHETIC_LOCALIZED")
        assertMissingLeaf(manager)
        check(System.getProperty(BOOTSTRAP_BUNDLE_MARKER) == null)
        println("BOOTSTRAP_C1 stage=setup leaf_absent=true bundle_initialized=false sentinel_reset=false")

        expectLoggingFailure { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        requireSyntheticDriverCold()
        assertMissingLeaf(manager)
        check(loader.driverLoads == 0 && System.getProperty(BOOTSTRAP_BUNDLE_MARKER) == null)
        println("BOOTSTRAP_C1 stage=negative rejected=true driver_initialized=false bundle_initialized=false")

        // Exact LogManager materialization path under unchanged configuration; NOT Level.parse/direct bundle lookup.
        val actual = Logger.getLogger(BOOTSTRAP_LEAF)
        check(System.getProperty(BOOTSTRAP_BUNDLE_MARKER) == "initialized")
        check(actual.level.intValue() == customLevel.intValue() && actual.level.javaClass === Level::class.java)
        check(manager.getProperty("$BOOTSTRAP_LEAF.level") == "KIRA_SYNTHETIC_LOCALIZED")
        println("BOOTSTRAP_C1 stage=positive api=Logger.getLogger bundle_initialized=true mirror_value=850 runtime_authority=UNKNOWN")
    }

    private fun drift(loader: BootstrapDriverLoader) {
        val handle = PersistenceDriverBootstrap.prepareWithLoader(loader)
        val later = Logger.getLogger("org.postgresql.bootstrap.later").apply { level = Level.FINE }
        expectLoggingFailure { handle.construct() }
        requireSyntheticDriverCold()
        later.level = Level.INFO
        val manager = LogManager.getLogManager()
        configure(manager, mapOf(".level" to "INFO", "org.handlers" to BootstrapSyntheticHandler::class.java.name))
        expectLoggingFailure { handle.construct() }
        check(BootstrapHandlerCounter.constructed == 0)
        requireSyntheticDriverCold()
        configure(manager, mapOf(".level" to "INFO"))
        val foreign = BootstrapForeignLogger("org.postgresql.bootstrap.foreignLater")
        check(LogManager.getLogManager().addLogger(foreign))
        foreign.armed = true
        expectLoggingFailure { handle.construct() }
        check(foreign.inspections == 0 && foreign.parentCalls == 0)
        requireSyntheticDriverCold()
        foreign.armed = false
    }

    private fun assertMissingLeaf(manager: LogManager) {
        check(manager.getLogger(BOOTSTRAP_LEAF) == null)
        check(manager.loggerNames.toList().none { it.startsWith("$BOOTSTRAP_LEAF.") })
    }

    private fun updateMissingLevel(manager: LogManager, value: String) {
        val bytes = propertyBytes(mapOf("$BOOTSTRAP_LEAF.level" to value))
        ByteArrayInputStream(bytes).use { stream ->
            manager.updateConfiguration(stream) { _ -> BiFunction { old, new -> new ?: old } }
        }
    }

    private fun configure(manager: LogManager, properties: Map<String, String>) {
        ByteArrayInputStream(propertyBytes(properties)).use(manager::readConfiguration)
    }

    private fun propertyBytes(values: Map<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        Properties().apply { values.forEach { (key, value) -> setProperty(key, value) } }.store(output, "synthetic-only")
        output.toByteArray()
    }

    private fun expectLoggingFailure(action: () -> Unit) {
        expectBootstrapFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_PERSISTENCE_LOGGING, action)
    }
}
