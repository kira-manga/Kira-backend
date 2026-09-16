package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.Constructor
import java.lang.reflect.InvocationTargetException
import java.sql.Driver

/** Unused cold prerequisite. The future private factory, not a bean/unwrap, must own the prepared handle. */
internal object PersistenceDriverBootstrap {
    fun prepare(): PreparedPersistenceDriver = PreparedPersistenceDriver.prepare { PersistenceDriverBootstrap::class.java.classLoader }

    /** Test seam varies only the loader; it cannot vary the literal driver name or the resource-view loader. */
    fun prepareWithLoader(loader: ClassLoader?): PreparedPersistenceDriver = PreparedPersistenceDriver.prepare { loader }
}

internal class PreparedPersistenceDriver private constructor(
    private val constructor: Constructor<out Driver>,
    private val logging: PersistenceLoggingPreflight,
) {
    fun construct(): Driver = persistenceBootstrapBoundary {
        requirePersistenceBootstrapGlobals()
        logging.recheck()
        try {
            constructor.newInstance()
        } catch (failure: InvocationTargetException) {
            // Only this trusted invocation wrapper is unwrapped, never an arbitrary callback failure graph.
            rejectPersistenceBootstrapFailure(failure.targetException)
        }
    }

    /** Additive cold path; optional timer metadata never replaces ordinary guarded construction. */
    fun constructWithTimerAccess(): PersistenceDriverWithTimerAccess = PersistencePgTimerAccess.construct(this)

    internal fun ownsTimerDriverClass(type: Class<*>): Boolean = constructor.declaringClass === type

    internal fun recheckForTimer() = persistenceBootstrapBoundary {
        requirePersistenceBootstrapGlobals()
        logging.recheck()
        requireNoPersistenceDriverDefaults(constructor.declaringClass.classLoader)
    }

    override fun toString(): String = "PreparedPersistenceDriver"

    companion object {
        fun prepare(ownerLoader: () -> ClassLoader?): PreparedPersistenceDriver = persistenceBootstrapBoundary {
            requirePersistenceBootstrapGlobals()
            val logging = PersistenceLoggingPreflight.capture()
            val driverClass = loadDriver(ownerLoader())
            val constructor = publicConstructor(driverClass)
            requireNoPersistenceDriverDefaults(driverClass.classLoader)
            PreparedPersistenceDriver(constructor, logging)
        }

        private fun loadDriver(loader: ClassLoader?): Class<out Driver> {
            val type = try {
                Class.forName("org.postgresql.Driver", false, loader)
            } catch (_: ClassNotFoundException) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER)
            }
            if (!Driver::class.java.isAssignableFrom(type)) rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER)
            return type.asSubclass(Driver::class.java)
        }

        private fun publicConstructor(type: Class<out Driver>): Constructor<out Driver> = try {
            type.getConstructor()
        } catch (_: NoSuchMethodException) {
            rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER)
        }
    }
}
