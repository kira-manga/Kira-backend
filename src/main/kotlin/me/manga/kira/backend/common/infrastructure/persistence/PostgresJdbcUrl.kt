package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Locale

/** Pure parsing for the pinned pgjdbc grammar. No driver, URI normalization, DNS or path access. */
internal object PostgresJdbcUrl {
    private const val PREFIX = "jdbc:postgresql:"

    fun parse(url: String, encoding: PersistenceUrlEncoding): Map<String, String> {
        if (!url.startsWith(PREFIX)) invalidUrl()
        val question = url.indexOf('?')
        val base = if (question < 0) url.substring(PREFIX.length) else url.substring(PREFIX.length, question)
        val query = if (question < 0) "" else url.substring(question + 1)
        val endpoint = when {
            base == "//" || base == "///" -> emptyMap()
            base.startsWith("//") -> hostForm(base.substring(2), encoding)
            base.startsWith('/') -> invalidUrl()
            else -> mapOf("PGDBNAME" to encoding.decode(base))
        }
        return PersistenceDriverProperties.merge(endpoint, queryProperties(query, encoding))
    }

    fun validateEndpoint(properties: Map<String, String>) {
        val hosts = properties.getValue("PGHOST").split(',')
        val ports = properties.getValue("PGPORT").split(',')
        if (hosts.any { it.isEmpty() } || ports.any { it.isEmpty() } || hosts.size != ports.size) invalidUrl()
        for (port in ports) {
            val number = try {
                Integer.parseInt(port)
            } catch (_: NumberFormatException) {
                invalidUrl()
            }
            if (number !in 1..65535) invalidUrl()
        }
    }

    private fun hostForm(server: String, encoding: PersistenceUrlEncoding): Map<String, String> {
        val slash = server.indexOf('/')
        if (slash < 0 || server.indexOf('/', slash + 1) >= 0) invalidUrl()
        val result = linkedMapOf<String, String>()
        if (slash < server.lastIndex) result["PGDBNAME"] = encoding.decode(server.substring(slash + 1))
        val addresses = server.substring(0, slash).split(',')
        if (addresses.any { it.isEmpty() }) invalidUrl()
        val hosts = ArrayList<String>(addresses.size)
        val ports = ArrayList<String>(addresses.size)
        for (address in addresses) {
            val portIndex = address.lastIndexOf(':')
            if (portIndex >= 0 && address.lastIndexOf(']') < portIndex) {
                hosts.add(address.substring(0, portIndex).ifEmpty { "localhost" })
                ports.add(address.substring(portIndex + 1))
            } else {
                hosts.add(address)
                ports.add("5432")
            }
        }
        result["PGHOST"] = hosts.joinToString(",")
        result["PGPORT"] = ports.joinToString(",")
        return result
    }

    private fun queryProperties(query: String, encoding: PersistenceUrlEncoding): Map<String, String> {
        val values = linkedMapOf<String, String>()
        for (token in query.split('&')) {
            if (token.isEmpty()) continue
            val equals = token.indexOf('=')
            val name = if (equals < 0) token else queryName(token.substring(0, equals))
            PersistenceDriverProperties.rejectService(name)
            if (PersistenceDriverProperties.isGuarded(name) && values.containsKey(name)) {
                rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_CONFLICT)
            }
            values[name] = if (equals < 0) "" else encoding.decode(token.substring(equals + 1))
        }
        return values
    }

    private fun queryName(name: String): String = when (name.uppercase(Locale.ROOT)) {
        "HOST" -> "PGHOST"
        "PORT" -> "PGPORT"
        "DBNAME" -> "PGDBNAME"
        else -> name
    }

    private fun invalidUrl(): Nothing = rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_JDBC_URL)
}
