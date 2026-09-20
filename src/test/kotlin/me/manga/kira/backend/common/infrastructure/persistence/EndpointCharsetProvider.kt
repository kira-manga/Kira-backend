package me.manga.kira.backend.common.infrastructure.persistence

import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.nio.charset.spi.CharsetProvider

internal const val ENDPOINT_SENTINEL_CHARSET = "X-Kira-Endpoint-Probe"

internal object EndpointCharsetCounters {
    var constructions = 0
    var lookups = 0
}

/** Discoverable only through a service file added to an owned child JVM, never the shared test JVM. */
class EndpointCharsetProvider : CharsetProvider() {
    init {
        EndpointCharsetCounters.constructions++
    }

    override fun charsetForName(charsetName: String): Charset? {
        EndpointCharsetCounters.lookups++
        return if (charsetName == ENDPOINT_SENTINEL_CHARSET) StandardCharsets.UTF_8 else null
    }

    override fun charsets(): MutableIterator<Charset> = error("This fixture must not enumerate charsets")
}
