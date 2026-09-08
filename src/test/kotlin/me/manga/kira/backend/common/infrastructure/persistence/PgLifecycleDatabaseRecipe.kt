package me.manga.kira.backend.common.infrastructure.persistence

/** Independent D13 recipes; properties are rebuilt, never accumulated across rows. */
internal enum class PgLifecycleDatabaseRecipe(val settings: Map<String, String>, val positiveTimeoutFails: Boolean = false) {
    DEFAULT(emptyMap()),
    READ_ONLY_FALSE_ALWAYS(mapOf("readOnlyMode" to "always")),
    READ_ONLY_TRUE_TRANSACTION(mapOf("readOnly" to "true", "readOnlyMode" to "transaction")),
    READ_ONLY_TRUE_IGNORE(mapOf("readOnly" to "true", "readOnlyMode" to "ignore")),
    READ_ONLY_TRUE_ALWAYS(mapOf("readOnly" to "true", "readOnlyMode" to "always"), true),
    BARE_BINARY_BOX(mapOf("datatype.box" to "org.postgresql.geometric.PGbox")),
    QUALIFIED_NONBINARY_BOX(mapOf("datatype.pg_catalog.box" to "org.postgresql.util.PGobject")),
    QUALIFIED_BINARY_BOX(mapOf("datatype.pg_catalog.box" to "org.postgresql.geometric.PGbox"), true),
    QUALIFIED_BINARY_BOX_SIMPLE(mapOf("datatype.pg_catalog.box" to "org.postgresql.geometric.PGbox", "preferQueryMode" to "simple")),
    QUALIFIED_BINARY_BOX_BINARY_DISABLED(mapOf("datatype.pg_catalog.box" to "org.postgresql.geometric.PGbox", "binaryTransfer" to "false"), true),
    READ_ONLY_TRUE_ALWAYS_SIMPLE(mapOf("readOnly" to "true", "readOnlyMode" to "always", "preferQueryMode" to "simple"), true),
    ;

    fun readOnlySql(queryTimeout: Int): Boolean = queryTimeout == 0 && settings["readOnly"] == "true" && settings["readOnlyMode"] == "always"

    fun catalogSql(queryTimeout: Int): Boolean = queryTimeout == 0 && settings["datatype.pg_catalog.box"] == "org.postgresql.geometric.PGbox" &&
        settings["preferQueryMode"] != "simple"
}

internal enum class PgLifecycleDatabaseLane(val deleting: Boolean, val allowanceMillis: Long) {
    ORDINARY(false, 6_000),
    DELETION(true, 2_000),
}

internal enum class PgLifecycleDatabaseMode {
    MATRIX,
    REUSE,
    WRONG_PASSWORD,
    ASSERTION_FAILURE,
    MISSING_RECEIPT,
    WRONG_RECEIPT,
    DUPLICATE_RECEIPT,
}

internal data class PgLifecycleDatabaseCase(
    val recipe: PgLifecycleDatabaseRecipe,
    val queryTimeout: Int,
    val lane: PgLifecycleDatabaseLane,
    val mode: PgLifecycleDatabaseMode = PgLifecycleDatabaseMode.MATRIX,
) {
    init {
        require(queryTimeout in 0..1)
        require(mode === PgLifecycleDatabaseMode.MATRIX || (recipe === PgLifecycleDatabaseRecipe.DEFAULT && queryTimeout == 0))
    }

    val attempts: Int get() = if (mode === PgLifecycleDatabaseMode.REUSE) 2 else 1
    val returnsRaw: Boolean get() = mode !== PgLifecycleDatabaseMode.WRONG_PASSWORD && !(queryTimeout == 1 && recipe.positiveTimeoutFails)
    val label: String get() = "mode=${mode.name} lane=${lane.name} recipe=${recipe.name} q=$queryTimeout"
}

/** Disposable database/role/password constants only. No environment datasource or credential file is read. */
internal object PgLifecycleDatabaseSettings {
    const val DATABASE = "w03_lifecycle"
    const val OBSERVER = "w03_observer"
    const val CANDIDATE = "w03_candidate"
    const val OBSERVER_PASSWORD = "synthetic-observer-only"
    const val CANDIDATE_PASSWORD = "synthetic-candidate-only"

    fun endpoint(case: PgLifecycleDatabaseCase, port: Int, application: String): ResolvedPersistenceEndpoint {
        check(port in 1..65535 && application.matches(Regex("w03c_[0-9a-f-]{36}")))
        val properties = mapOf(
            "PGHOST" to "127.0.0.1", "PGPORT" to port.toString(), "PGDBNAME" to DATABASE,
            "user" to CANDIDATE, "password" to if (case.mode === PgLifecycleDatabaseMode.WRONG_PASSWORD) "synthetic-wrong-only" else CANDIDATE_PASSWORD,
            "ApplicationName" to application, "assumeMinServerVersion" to "17", "loginTimeout" to "0",
            "gssEncMode" to "disable", "requireAuth" to "scram-sha-256", "scramMaxIterations" to "100000", "channelBinding" to "disable",
            "sslmode" to "disable", "sslcert" to "", "sslkey" to "", "connectTimeout" to "2", "socketTimeout" to "3", "cancelSignalTimeout" to "2",
            "readOnly" to "false", "preferQueryMode" to "extended", "queryTimeout" to case.queryTimeout.toString(),
        ) + case.recipe.settings
        // Deliberately different from deletion's derived 2000ms. Never reset an already active request budget.
        return ResolvedPersistenceEndpoint(properties, PersistenceLoginPolicy.resolve(null, 6_000))
    }
}
