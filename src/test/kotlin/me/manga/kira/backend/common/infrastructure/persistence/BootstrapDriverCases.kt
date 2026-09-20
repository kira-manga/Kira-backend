package me.manga.kira.backend.common.infrastructure.persistence

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.io.PrintWriter
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException

internal object BootstrapDriverCases {
    fun verify(mode: BootstrapProbeCase, root: Path) {
        BootstrapDriverLoader(root, mode).use { loader ->
            when (mode) {
                BootstrapProbeCase.REAL_CONSTRUCTION -> realConstruction()

                BootstrapProbeCase.COLD_SYNTHETIC, BootstrapProbeCase.DEFAULT_RESOURCE_ABSENT -> coldConstruction(loader)

                BootstrapProbeCase.WRONG_TYPE, BootstrapProbeCase.MISSING_CLASS, BootstrapProbeCase.PRIVATE_CONSTRUCTOR -> {
                    expectBootstrapFailure(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER) { PersistenceDriverBootstrap.prepareWithLoader(loader) }
                    requireSyntheticDriverCold()
                }

                BootstrapProbeCase.DEFAULT_RESOURCE_CHILD -> definingResource(loader)

                BootstrapProbeCase.DEFAULT_RESOURCE_SYSTEM -> systemResourceFallback()

                BootstrapProbeCase.RESOURCE_IO_FAILURE, BootstrapProbeCase.RESOURCE_INTERRUPTED,
                BootstrapProbeCase.RESOURCE_FATAL, BootstrapProbeCase.DRIVER_LINKAGE_FATAL,
                ->
                    lookupFailure(loader, mode)

                BootstrapProbeCase.OWNER_LOADER_FAILURE, BootstrapProbeCase.OWNER_LOADER_FATAL -> ownerLoaderFailure(mode)

                BootstrapProbeCase.CONSTRUCTOR_FAILURE, BootstrapProbeCase.CONSTRUCTOR_INTERRUPTED, BootstrapProbeCase.CONSTRUCTOR_FATAL ->
                    constructorFailure(loader, mode)

                BootstrapProbeCase.HIDDEN_CONFIG_PRESENT, BootstrapProbeCase.HIDDEN_CONFIG_EMPTY, BootstrapProbeCase.HIDDEN_LOOKALIKE ->
                    hiddenConfig(loader, mode)

                BootstrapProbeCase.WRITER, BootstrapProbeCase.LEGACY_WRITER -> writerGuard(loader, mode)

                BootstrapProbeCase.NO_DISCOVERY -> noDiscovery()

                BootstrapProbeCase.GLOBAL_DRIFT -> globalDrift(loader)

                else -> error("Unimplemented driver probe mode")
            }
            check(loader.elementQueries == 0 && loader.renderCalls == 0)
        }
    }

    private fun coldConstruction(loader: BootstrapDriverLoader) {
        requireSyntheticDriverCold()
        val handle = PersistenceDriverBootstrap.prepareWithLoader(loader)
        requireSyntheticDriverCold()
        check(handle.toString() == "PreparedPersistenceDriver")
        check(loader.driverLoads == 1 && loader.resourceQueries == 1 && loader.presenceQueries == 1)
        val driver = handle.construct()
        check(driver.javaClass.classLoader === loader)
        check(driver.javaClass === Class.forName("org.postgresql.Driver", false, loader))
        check(System.getProperty(BOOTSTRAP_DRIVER_MARKER) == "initialized")
        check(loader.resourceQueries == 1) // A visibility snapshot is not a promise of immutable resources.
    }

    private fun realConstruction() {
        DriverManager.setLoginTimeout(37) // A nondefault synthetic value proves that bootstrap does not overwrite it.
        val loginTimeout = DriverManager.getLoginTimeout()
        persistenceBootstrapBoundary { requireNoPersistenceDriverDefaults(null) }
        val handle = PersistenceDriverBootstrap.prepare()
        check(handle.toString() == "PreparedPersistenceDriver")
        val driver = handle.construct()
        check(driver.javaClass.name == "org.postgresql.Driver")
        check(driver.javaClass.`package`.implementationVersion == "42.7.12")
        check(driver.javaClass.getMethod("isRegistered").invoke(null) == true)
        check(DriverManager.getLoginTimeout() == loginTimeout)
        check(Thread.getAllStackTraces().keys.none { it.name.startsWith("PostgreSQL-JDBC-SharedTimer") || it.name.contains("PG-JDBC-Connect") })
        println("BOOTSTRAP_REAL driver=42.7.12 constructed=true registered=true connected=false")
    }

    private fun definingResource(loader: BootstrapDriverLoader) {
        val name = "org/postgresql/driverconfig.properties"
        check(!ClassLoader.getSystemClassLoader().getResources(name).hasMoreElements())
        check(!Thread.currentThread().contextClassLoader.getResources(name).hasMoreElements())
        expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_DRIVER_DEFAULT_RESOURCE) { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        requireSyntheticDriverCold()
        check(loader.resourceQueries == 1 && loader.presenceQueries == 1 && loader.elementQueries == 0)
        // Actual same-defining-loader positive control; never request an element, open a URL or render it.
        check(loader.getResources(name).hasMoreElements())
        check(loader.resourceQueries == 2 && loader.presenceQueries == 2)
    }

    private fun systemResourceFallback() {
        expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_DRIVER_DEFAULT_RESOURCE) {
            persistenceBootstrapBoundary { requireNoPersistenceDriverDefaults(null) }
        }
        check(ClassLoader.getSystemClassLoader().getResources("org/postgresql/driverconfig.properties").hasMoreElements())
        requireSyntheticDriverCold()
    }

    private fun lookupFailure(loader: BootstrapDriverLoader, mode: BootstrapProbeCase) = withBootstrapInterruptIsolation {
        if (mode == BootstrapProbeCase.RESOURCE_FATAL || mode == BootstrapProbeCase.DRIVER_LINKAGE_FATAL) {
            val observed = runCatching { PersistenceDriverBootstrap.prepareWithLoader(loader) }.exceptionOrNull()
            check(observed === loader.fatal)
        } else {
            val first = expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_BOOTSTRAP_FAILED) { PersistenceDriverBootstrap.prepareWithLoader(loader) }
            val second = expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_BOOTSTRAP_FAILED) { PersistenceDriverBootstrap.prepareWithLoader(loader) }
            check(first !== second)
            check(Thread.currentThread().isInterrupted == (mode == BootstrapProbeCase.RESOURCE_INTERRUPTED))
        }
        requireSyntheticDriverCold()
        check(loader.presenceQueries == 0)
    }

    private fun ownerLoaderFailure(mode: BootstrapProbeCase) {
        if (mode == BootstrapProbeCase.OWNER_LOADER_FATAL) {
            val fatal = LinkageError(BOOTSTRAP_CANARY)
            check(runCatching { PreparedPersistenceDriver.prepare { throw fatal } }.exceptionOrNull() === fatal)
        } else {
            val supplied = PersistenceBoundaryException(PersistenceBoundaryFailureCode.JDBC_BOOTSTRAP_FAILED)
            supplied.initCause(IllegalArgumentException(BOOTSTRAP_CANARY))
            supplied.addSuppressed(IllegalStateException(BOOTSTRAP_CANARY))
            val adapted = expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_BOOTSTRAP_FAILED) { PreparedPersistenceDriver.prepare { throw supplied } }
            check(adapted !== supplied)
        }
        requireSyntheticDriverCold()
    }

    private fun constructorFailure(loader: BootstrapDriverLoader, mode: BootstrapProbeCase) = withBootstrapInterruptIsolation {
        val handle = PersistenceDriverBootstrap.prepareWithLoader(loader)
        requireSyntheticDriverCold()
        if (mode == BootstrapProbeCase.CONSTRUCTOR_FATAL) {
            val observed = runCatching { handle.construct() }.exceptionOrNull()
            val type = Class.forName("org.postgresql.Driver", false, loader)
            check(observed === type.getField("FATAL").get(null))
        } else {
            expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_BOOTSTRAP_FAILED) { handle.construct() }
            check(Thread.currentThread().isInterrupted == (mode == BootstrapProbeCase.CONSTRUCTOR_INTERRUPTED))
        }
        check(System.getProperty(BOOTSTRAP_DRIVER_MARKER) == "initialized")
    }

    private fun hiddenConfig(loader: BootstrapDriverLoader, mode: BootstrapProbeCase) {
        val lookalike = mode == BootstrapProbeCase.HIDDEN_LOOKALIKE
        val key = if (lookalike) "hikari.configurationFile" else "hikaricp.configurationFile"
        val value = if (mode == BootstrapProbeCase.HIDDEN_CONFIG_EMPTY) "" else BOOTSTRAP_CANARY
        check(System.getProperty(key) == null)
        System.setProperty(key, value)
        if (lookalike) {
            PersistenceDriverBootstrap.prepareWithLoader(loader)
        } else {
            expectBootstrapFailure(PersistenceBoundaryFailureCode.HIDDEN_HIKARI_CONFIGURATION) { PersistenceDriverBootstrap.prepareWithLoader(loader) }
            check(loader.driverLoads == 0 && loader.resourceQueries == 0)
        }
        check(System.getProperty(key) == value)
        requireSyntheticDriverCold()
    }

    private fun writerGuard(loader: BootstrapDriverLoader, mode: BootstrapProbeCase) {
        val bytes = ByteArrayOutputStream()
        val writer = PrintWriter(bytes, true)
        if (mode == BootstrapProbeCase.LEGACY_WRITER) {
            // Exercise the historical public API without suppressing a deprecated call in production code.
            DriverManager::class.java.getMethod("setLogStream", PrintStream::class.java).invoke(null, PrintStream(bytes, true))
        } else {
            DriverManager.setLogWriter(writer)
        }
        val installed = checkNotNull(DriverManager.getLogWriter())
        val timeout = DriverManager.getLoginTimeout()
        expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED) { PersistenceDriverBootstrap.prepareWithLoader(loader) }
        requireSyntheticDriverCold()
        check(loader.driverLoads == 0 && loader.resourceQueries == 0)
        check(DriverManager.getLogWriter() === installed && DriverManager.getLoginTimeout() == timeout)
        check(bytes.size() == 0)
        // Deliberate synthetic positive control AFTER every zero-output/absence assertion.
        SQLException("synthetic writer positive control")
        installed.flush()
        check(bytes.size() > 0)
    }

    private fun noDiscovery() {
        val markers = listOf("kira.synthetic.bootstrap.spi", "kira.synthetic.bootstrap.legacy")
        check(markers.all { System.getProperty(it) == null })
        val driver = PersistenceDriverBootstrap.prepare().construct()
        check(markers.all { System.getProperty(it) == null })
        // Only now discover providers. Both public discovery fixtures must actually run.
        val registered = DriverManager.getDrivers().toList()
        check(markers.all { System.getProperty(it) == "initialized" })
        val intrinsic = registered.filter { it.javaClass === driver.javaClass }
        check(intrinsic.size == 1 && intrinsic.single() !== driver)
        println("BOOTSTRAP_DISCOVERY before=0 after=2 intrinsic_registration=preserved")
    }

    private fun globalDrift(loader: BootstrapDriverLoader) {
        val first = PersistenceDriverBootstrap.prepareWithLoader(loader)
        val second = PersistenceDriverBootstrap.prepareWithLoader(loader)
        check(first !== second && first.toString() == second.toString())
        for (value in listOf("", BOOTSTRAP_CANARY)) {
            System.setProperty("hikaricp.configurationFile", value)
            expectBootstrapFailure(PersistenceBoundaryFailureCode.HIDDEN_HIKARI_CONFIGURATION) { first.construct() }
            check(System.getProperty("hikaricp.configurationFile") == value)
            requireSyntheticDriverCold()
        }
        System.clearProperty("hikaricp.configurationFile")
        val bytes = ByteArrayOutputStream()
        DriverManager.setLogWriter(PrintWriter(bytes, true))
        expectBootstrapFailure(PersistenceBoundaryFailureCode.JDBC_LOG_WRITER_INSTALLED) { second.construct() }
        requireSyntheticDriverCold()
        check(bytes.size() == 0)
    }
}
