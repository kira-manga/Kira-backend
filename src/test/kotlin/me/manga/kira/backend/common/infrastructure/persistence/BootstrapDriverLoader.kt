package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.Enumeration

/** Defines only the literal synthetic Driver child-first; never changes the shared test JVM loader. */
internal class BootstrapDriverLoader(root: Path, private val mode: BootstrapProbeCase) :
    URLClassLoader(arrayOf(root.toUri().toURL()), BootstrapProbe::class.java.classLoader) {
    var driverLoads = 0
    var resourceQueries = 0
    var presenceQueries = 0
    var elementQueries = 0
    var renderCalls = 0
    val fatal = LinkageError(BOOTSTRAP_CANARY)

    @Synchronized
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (name != "org.postgresql.Driver") return super.loadClass(name, resolve)
        driverLoads++
        if (mode == BootstrapProbeCase.DRIVER_LINKAGE_FATAL) throw fatal
        val type = findLoadedClass(name) ?: findClass(name)
        if (resolve) resolveClass(type)
        return type
    }

    override fun getResources(name: String): Enumeration<URL> {
        check(name == "org/postgresql/driverconfig.properties")
        resourceQueries++
        when (mode) {
            BootstrapProbeCase.RESOURCE_IO_FAILURE -> throw HostileBootstrapIOException()
            BootstrapProbeCase.RESOURCE_INTERRUPTED -> throw InterruptedException(BOOTSTRAP_CANARY)
            BootstrapProbeCase.RESOURCE_FATAL -> throw fatal
            else -> Unit
        }
        return object : Enumeration<URL> {
            override fun hasMoreElements(): Boolean {
                presenceQueries++
                return mode == BootstrapProbeCase.DEFAULT_RESOURCE_CHILD
            }

            override fun nextElement(): URL {
                elementQueries++
                error("Resource URL access forbidden")
            }
        }
    }

    override fun toString(): String {
        renderCalls++
        error("Loader rendering forbidden")
    }
}

private class HostileBootstrapIOException : IOException() {
    override val message: String get() = error("Failure message access forbidden")
    override val cause: Throwable? get() = error("Failure cause access forbidden")
    override fun toString(): String = error("Failure rendering forbidden")
}
