package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Properties

/** Synthetic vectors shared with an isolated real-driver oracle; expected values are literal contract assertions. */
class EndpointUrlVector(
    val label: String,
    val url: String,
    val expected: Map<String, String>,
    private val properties: Map<String, String> = emptyMap(),
    val loginMillis: Long = 30_000,
) {
    fun inputProperties(): Properties = Properties().apply {
        setProperty("user", ENDPOINT_TEST_USER)
        setProperty("password", ENDPOINT_TEST_PASSWORD)
        properties.forEach { (name, value) -> setProperty(name, value) }
    }

    fun expectedProperties(): Map<String, String> = mapOf(
        "user" to ENDPOINT_TEST_USER,
        "password" to ENDPOINT_TEST_PASSWORD,
        "PGHOST" to "localhost",
        "PGPORT" to "5432",
        "PGDBNAME" to ENDPOINT_TEST_USER,
        "loginTimeout" to "0",
    ) + expected

    override fun toString(): String = label
}

internal object EndpointUrlCases {
    val accepted = listOf(
        EndpointUrlVector("short-db", "jdbc:postgresql:catalog", mapOf("PGDBNAME" to "catalog")),
        EndpointUrlVector("short-empty-db", "jdbc:postgresql:", mapOf("PGDBNAME" to "")),
        EndpointUrlVector("double-slash-default-db", "jdbc:postgresql://", emptyMap()),
        EndpointUrlVector("triple-slash-default-db", "jdbc:postgresql:///", emptyMap()),
        EndpointUrlVector(
            "explicit-host-port-db",
            "jdbc:postgresql://DB.Example:6543/catalog",
            mapOf(
                "PGHOST" to "DB.Example",
                "PGPORT" to "6543",
                "PGDBNAME" to "catalog",
            ),
        ),
        EndpointUrlVector("host-default-port-and-db", "jdbc:postgresql://Db.Example/", mapOf("PGHOST" to "Db.Example")),
        EndpointUrlVector("port-only-member", "jdbc:postgresql://:6543/", mapOf("PGPORT" to "6543")),
        EndpointUrlVector(
            "ordered-ipv6-and-host",
            "jdbc:postgresql://[2001:db8::1]:5433,Db.Example/catalog",
            mapOf(
                "PGHOST" to "[2001:db8::1],Db.Example",
                "PGPORT" to "5433,5432",
                "PGDBNAME" to "catalog",
            ),
        ),
        EndpointUrlVector("ipv6-without-explicit-port", "jdbc:postgresql://[2001:db8::1]/", mapOf("PGHOST" to "[2001:db8::1]")),
        EndpointUrlVector(
            "property-only-unbracketed-ipv6",
            "jdbc:postgresql://",
            mapOf(
                "PGHOST" to "2001:db8::5",
                "PGPORT" to "6543",
                "PGDBNAME" to "literal%2F+?#",
            ),
            mapOf("PGHOST" to "2001:db8::5", "PGPORT" to "6543", "PGDBNAME" to "literal%2F+?#"),
        ),
        EndpointUrlVector(
            "property-lists-keep-port-spelling",
            "jdbc:postgresql://",
            mapOf(
                "PGHOST" to "First,SECOND",
                "PGPORT" to "+05432,65535",
            ),
            mapOf("PGHOST" to "First,SECOND", "PGPORT" to "+05432,65535"),
        ),
        EndpointUrlVector(
            "once-only-db-form-decoding",
            "jdbc:postgresql://host/a%2Fb%25c%3F+z",
            mapOf(
                "PGHOST" to "host",
                "PGDBNAME" to "a/b%c? z",
            ),
        ),
        EndpointUrlVector("percent-not-decoded-twice", "jdbc:postgresql:db%252F%2525", mapOf("PGDBNAME" to "db%2F%25")),
        EndpointUrlVector("short-db-slash-and-hash", "jdbc:postgresql:part/name#tag", mapOf("PGDBNAME" to "part/name#tag")),
        EndpointUrlVector("literal-unicode-is-not-byte-decoded", "jdbc:postgresql:café", mapOf("PGDBNAME" to "café")),
        EndpointUrlVector(
            "only-documented-aliases",
            "jdbc:postgresql://?HoSt=First%2CSecond&PORT=5432%2C6432&dBnAmE=Named",
            mapOf(
                "PGHOST" to "First,Second",
                "PGPORT" to "5432,6432",
                "PGDBNAME" to "Named",
            ),
        ),
        EndpointUrlVector(
            "equal-base-and-query-fields",
            "jdbc:postgresql://Example/db?PGHOST=Example&PGPORT=5432&PGDBNAME=db",
            mapOf(
                "PGHOST" to "Example",
                "PGDBNAME" to "db",
            ),
        ),
        EndpointUrlVector("bare-aliases-stay-inert", "jdbc:postgresql://?host&port&dbname", mapOf("host" to "", "port" to "", "dbname" to "")),
        EndpointUrlVector(
            "names-not-decoded",
            "jdbc:postgresql://?p%61ssword=inert&pgHOST=other",
            mapOf(
                "p%61ssword" to "inert",
                "pgHOST" to "other",
            ),
        ),
        EndpointUrlVector(
            "query-extension-last-value-wins",
            "jdbc:postgresql://?ApplicationName=first&ApplicationName=last",
            mapOf(
                "ApplicationName" to "last",
            ),
            mapOf("ApplicationName" to "property-tier"),
        ),
        EndpointUrlVector("bare-extension-last-value-wins", "jdbc:postgresql://?ApplicationName=first&ApplicationName", mapOf("ApplicationName" to "")),
        EndpointUrlVector("hash-and-extra-equals-are-values", "jdbc:postgresql://?ApplicationName=a=b#tag", mapOf("ApplicationName" to "a=b#tag")),
        EndpointUrlVector("value-form-decoding", "jdbc:postgresql://?currentSchema=p%2Bq+r", mapOf("currentSchema" to "p+q r")),
        EndpointUrlVector("extra-question-is-value", "jdbc:postgresql://?ApplicationName=a?b", mapOf("ApplicationName" to "a?b")),
        EndpointUrlVector("empty-tokens-ignored", "jdbc:postgresql://?&&ApplicationName=x&&", mapOf("ApplicationName" to "x")),
        EndpointUrlVector("raw-property-alias-stays-inert", "jdbc:postgresql://", mapOf("host" to "not-an-endpoint"), mapOf("host" to "not-an-endpoint")),
        EndpointUrlVector("host-spelling-preserved", "jdbc:postgresql://%65Xample/", mapOf("PGHOST" to "%65Xample")),
        EndpointUrlVector("host-whitespace-preserved", "jdbc:postgresql:// Db.Example /", mapOf("PGHOST" to " Db.Example ")),
        EndpointUrlVector(
            "Java-integer-port-grammar",
            "jdbc:postgresql://host:+05432/db",
            mapOf(
                "PGHOST" to "host",
                "PGPORT" to "+05432",
                "PGDBNAME" to "db",
            ),
        ),
        EndpointUrlVector(
            "Java-unicode-integer-port",
            "jdbc:postgresql://host:٥٤٣٢/db",
            mapOf(
                "PGHOST" to "host",
                "PGPORT" to "٥٤٣٢",
                "PGDBNAME" to "db",
            ),
        ),
        EndpointUrlVector(
            "empty-explicit-credentials",
            "jdbc:postgresql://?user=&password=",
            mapOf(
                "user" to "",
                "password" to "",
                "PGDBNAME" to "",
            ),
            mapOf("user" to "", "password" to ""),
        ),
        EndpointUrlVector(
            "whitespace-explicit-credentials",
            "jdbc:postgresql://?user=+&password=+%09",
            mapOf(
                "user" to " ",
                "password" to " \t",
                "PGDBNAME" to " ",
            ),
            mapOf("user" to " ", "password" to " \t"),
        ),
        EndpointUrlVector("fractional-login-is-external-only", "jdbc:postgresql://?loginTimeout=1.25", emptyMap(), loginMillis = 1250),
        EndpointUrlVector("zero-login-selects-finite-fallback", "jdbc:postgresql://?loginTimeout=0", emptyMap()),
        EndpointUrlVector(
            "preserve-ordinary-extension-settings",
            "jdbc:postgresql://?connectTimeout=60&socketFactory=synthetic.Factory",
            mapOf(
                "connectTimeout" to "60",
                "socketFactory" to "synthetic.Factory",
            ),
        ),
    )
}
