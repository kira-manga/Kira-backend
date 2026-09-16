package me.manga.kira.backend.common.infrastructure.persistence

import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties
import org.springframework.boot.jdbc.EmbeddedDatabaseConnection
import java.nio.charset.Charset
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.Properties
import kotlin.system.exitProcess

internal const val ENDPOINT_PROBE_VERIFIED_EXIT = 47

enum class EndpointProbeMode {
    DRIVER_UTF8,
    DRIVER_LATIN1,
    STOCK_PROVIDER_SENTINEL,
    DEFAULT_ENCODING,
    ASSERTION_FAILURE,
    WAIT_FOR_TERMINATION,
}

/** Synthetic parse/encoding characterization only. No connection, service name or implicit password lookup. */
object EndpointResolutionProbe {
    @JvmStatic
    fun main(args: Array<String>) {
        when (val mode = EndpointProbeMode.valueOf(args.single())) {
            EndpointProbeMode.DRIVER_UTF8, EndpointProbeMode.DRIVER_LATIN1 -> verifyDriver(mode)

            EndpointProbeMode.STOCK_PROVIDER_SENTINEL -> verifyProviderBoundary()

            EndpointProbeMode.DEFAULT_ENCODING -> {
                check(System.getProperty("postgresql.url.encoding") == null)
                check(resolve("jdbc:postgresql:caf%C3%A9", explicitCredentials()).driverProperties().getProperty("PGDBNAME") == "café")
            }

            EndpointProbeMode.ASSERTION_FAILURE -> error("Deliberate child assertion failure")

            EndpointProbeMode.WAIT_FOR_TERMINATION -> Thread.sleep(Long.MAX_VALUE)
        }
        // Exit0 (wrong main or accidental fall-through) cannot stand in for a successful probe.
        exitProcess(ENDPOINT_PROBE_VERIFIED_EXIT)
    }

    private fun verifyDriver(mode: EndpointProbeMode) {
        val encoding = if (mode == EndpointProbeMode.DRIVER_UTF8) "UTF-8" else "ISO-8859-1"
        check(System.getProperty("postgresql.url.encoding") == encoding)
        val driver = Class.forName("org.postgresql.Driver")
        check(driver.`package`.implementationVersion == "42.7.12")
        val parse = driver.getMethod("parseURL", String::class.java, Properties::class.java)
        val vectors = EndpointUrlCases.accepted + listOf(
            EndpointUrlVector(
                "escaped-nonascii",
                "jdbc:postgresql:caf%C3%A9",
                mapOf(
                    "PGDBNAME" to if (mode == EndpointProbeMode.DRIVER_UTF8) "café" else "cafÃ©",
                ),
            ),
            EndpointUrlVector(
                "alternate-escaped-byte",
                "jdbc:postgresql:caf%E9",
                mapOf(
                    "PGDBNAME" to if (mode == EndpointProbeMode.DRIVER_LATIN1) "café" else "caf\uFFFD",
                ),
            ),
        )
        for (vector in vectors) {
            // Model Driver.connect's effective String-default flattening before calling its public parser.
            val input = flatten(Properties(vector.inputProperties()))
            check(input.getProperty("user") != null && input.getProperty("password") != null && input.getProperty("service") == null)
            val endpoint = resolve(vector.url, input)
            val original = parse.invoke(null, vector.url, input) as? Properties ?: error("Supported original parse rejected")
            val normalized = parse.invoke(null, endpoint.driverUrl, endpoint.driverProperties()) as? Properties ?: error("Carrier parse rejected")
            check(withoutLogin(original) == withoutLogin(normalized)) { "Effective driver values changed" }
            check(normalized == endpoint.driverProperties()) { "Carrier altered the explicit snapshot" }
            check(normalized.getProperty("loginTimeout") == "0")
            check(normalized.stringPropertyNames().associateWith { normalized.getProperty(it) } == vector.expectedProperties())
            check(endpoint.loginPolicy.durationMillis == vector.loginMillis)
        }
    }

    private fun verifyProviderBoundary() {
        check(EndpointCharsetCounters.constructions == 0 && EndpointCharsetCounters.lookups == 0)
        val stock = listOf(
            StandardCharsets.US_ASCII,
            StandardCharsets.ISO_8859_1,
            StandardCharsets.UTF_8,
            StandardCharsets.UTF_16BE,
            StandardCharsets.UTF_16LE,
            StandardCharsets.UTF_16,
        )
        for (charset in stock) {
            for (alias in charset.aliases() + charset.name()) {
                for (name in listOf(alias.lowercase(Locale.ROOT), alias.uppercase(Locale.ROOT))) {
                    val endpoint = resolve("jdbc:postgresql:sample+value", explicitCredentials(), name)
                    check(endpoint.driverProperties().getProperty("PGDBNAME") == "sample value")
                    requireFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_URL) {
                        resolve("jdbc:postgresql:%ZZ", explicitCredentials(), name)
                    }
                }
            }
        }
        for (invalid in listOf("", " ", " UTF-8", "UTF-8 ", "windows-1252", ENDPOINT_SENTINEL_CHARSET, "unknown", "UTＦ-8", "ütf-8", "UTF‐8")) {
            requireFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_ENCODING) { resolve("jdbc:postgresql://", explicitCredentials(), invalid) }
        }
        // The named property capture follows the same rejection boundary when no explicit name is supplied.
        check(System.getProperty("postgresql.url.encoding") == ENDPOINT_SENTINEL_CHARSET)
        requireFailure(PersistenceBoundaryFailureCode.INVALID_JDBC_ENCODING) { resolve("jdbc:postgresql://", explicitCredentials()) }
        check(EndpointCharsetCounters.constructions == 0 && EndpointCharsetCounters.lookups == 0)

        // Deliberate positive control AFTER every zero-counter assertion. An absent service fixture fails this probe.
        check(Charset.forName(ENDPOINT_SENTINEL_CHARSET) === StandardCharsets.UTF_8)
        check(EndpointCharsetCounters.constructions > 0 && EndpointCharsetCounters.lookups > 0)
    }

    private fun resolve(url: String, properties: Properties, encoding: String? = null): ResolvedPersistenceEndpoint {
        val fallback = DataSourceProperties().apply {
            this.url = url
            embeddedDatabaseConnection = EmbeddedDatabaseConnection.NONE
        }
        return PersistenceEndpointResolver.resolve(emptyList(), fallback, properties, 30_000, encoding)
    }

    private fun flatten(source: Properties): Properties = Properties().apply {
        source.stringPropertyNames().forEach { setProperty(it, source.getProperty(it)) }
    }

    private fun withoutLogin(properties: Properties): Map<String, String> = properties.stringPropertyNames()
        .filter { it != "loginTimeout" }.associateWith { properties.getProperty(it) }

    private fun explicitCredentials(): Properties = Properties().apply {
        setProperty("user", ENDPOINT_TEST_USER)
        setProperty("password", ENDPOINT_TEST_PASSWORD)
    }

    private fun requireFailure(code: PersistenceBoundaryFailureCode, action: () -> Unit) {
        try {
            action()
            error("Expected fixed endpoint rejection")
        } catch (failure: PersistenceBoundaryException) {
            check(failure.code == code)
            check(failure.message == "Persistence boundary rejected: ${code.name}.")
            check(failure.cause == null && failure.suppressed.isEmpty())
        }
    }
}
