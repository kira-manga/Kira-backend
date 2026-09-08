package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Properties

/** An opaque cold snapshot, not a DataSource or permission to connect. Never register it as a bean. */
internal class ResolvedPersistenceEndpoint(properties: Map<String, String>, val loginPolicy: PersistenceLoginPolicy) {
    private val values = properties.toMap()

    // The pinned driver's exact // branch assigns no endpoint/query fields and performs no URL decoding.
    val driverUrl: String get() = "jdbc:postgresql://"

    fun driverProperties(): Properties = Properties().apply {
        this@ResolvedPersistenceEndpoint.values.forEach { (name, value) -> setProperty(name, value) }
    }

    fun credentialsMatch(username: String?, password: String?): Boolean = values["user"] == username && values["password"] == password

    override fun toString(): String = "ResolvedPersistenceEndpoint(redacted)"
}
