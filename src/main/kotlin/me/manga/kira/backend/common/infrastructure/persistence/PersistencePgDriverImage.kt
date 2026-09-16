package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.StackWalker.Option.RETAIN_CLASS_REFERENCE
import java.lang.reflect.Modifier
import java.sql.Driver
import javax.net.SocketFactory

/** Exact public metadata, not a controlled-runtime, provider or artifact attestation. No class is instantiated here. */
internal class PersistencePgDriverImage private constructor(val driver: Class<out Driver>, private val classes: Map<String, Class<*>>) {
    val walker: StackWalker = StackWalker.getInstance(setOf(RETAIN_CLASS_REFERENCE), PersistencePgDriverFrames.MAX_FRAMES)
    val patterns = PersistencePgDriverPatterns(this)

    fun type(name: String): Class<*> = classes.getValue(name)

    override fun toString(): String = "PersistencePgDriverImage(redacted)"

    companion object {
        private val TYPES = listOf(
            "org.postgresql.core.PGStream",
            "org.postgresql.core.v3.ConnectionFactoryImpl",
            "org.postgresql.core.QueryExecutorBase",
            "org.postgresql.core.SocketFactoryFactory",
            "org.postgresql.util.ObjectFactory",
            "org.postgresql.core.ConnectionFactory",
            "org.postgresql.jdbc.PgConnection",
        )

        fun prepare(driver: Class<out Driver>): PersistencePgDriverImage = persistenceBootstrapBoundary {
            val loader = driver.classLoader
            if (Class.forName("org.postgresql.Driver", false, loader) !== driver) unsupported()
            val classes = TYPES.associateWith { name ->
                Class.forName(name, false, loader).also { if (it.classLoader !== loader) unsupported() }
            }
            val factoryLoader = classes.getValue("org.postgresql.util.ObjectFactory").classLoader
            val bridge = Class.forName(TrackedPgSocketFactory::class.java.name, false, factoryLoader)
            if (bridge !== TrackedPgSocketFactory::class.java || bridge.superclass !== SocketFactory::class.java) unsupported()
            if (!Modifier.isPublic(bridge.modifiers) || !Modifier.isFinal(bridge.modifiers)) unsupported()
            val constructors = bridge.constructors
            if (constructors.size != 1 || constructors.single().parameterCount != 0) unsupported()
            // Prime the inert scope's closed states before any Entry is constructed under G.
            PersistencePgFactoryScope.prepareRuntime()
            PersistencePgDriverImage(driver, classes)
        }

        private fun unsupported(): Nothing = rejectPersistenceBoundary(PersistenceBoundaryFailureCode.UNSUPPORTED_JDBC_DRIVER)
    }
}
