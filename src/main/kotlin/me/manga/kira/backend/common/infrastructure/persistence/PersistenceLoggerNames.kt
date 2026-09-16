package me.manga.kira.backend.common.infrastructure.persistence

/** Literal emitter inventory for pgjdbc 42.7.12 / Hikari 6.3.3; invalidate on dependency/image changes. */
internal object PersistenceLoggerNames {
    val postgres: List<String> = listOf(
        "org.postgresql",
        "org.postgresql.Driver",
        "org.postgresql.core.Encoding",
        "org.postgresql.core.QueryExecutorBase",
        "org.postgresql.core.v3.AuthenticationPluginManager",
        "org.postgresql.core.v3.ConnectionFactoryImpl",
        "org.postgresql.core.v3.QueryExecutorImpl",
        "org.postgresql.core.v3.ScramAuthenticator",
        "org.postgresql.core.v3.SimpleQuery",
        "org.postgresql.core.v3.replication.V3PGReplicationStream",
        "org.postgresql.core.v3.replication.V3ReplicationProtocol",
        "org.postgresql.ds.common.BaseDataSource",
        "org.postgresql.gss.GssAction",
        "org.postgresql.gss.MakeGSS",
        "org.postgresql.jdbc.BooleanTypeUtil",
        "org.postgresql.jdbc.PgConnection",
        "org.postgresql.jdbc.TypeInfoCache",
        "org.postgresql.jdbcurlresolver.PgPassParser",
        "org.postgresql.jdbcurlresolver.PgServiceConfParser",
        "org.postgresql.ssl.MakeSSL",
        "org.postgresql.ssl.PGjdbcHostnameVerifier",
        "org.postgresql.sspi.SSPIClient",
        "org.postgresql.util.LazyCleanerImpl",
        "org.postgresql.util.PGPropertyMaxResultBufferParser",
        "org.postgresql.util.PGPropertyUtil",
        "org.postgresql.util.ServerErrorMessage",
        "org.postgresql.util.SharedTimer",
        "org.postgresql.util.StreamWrapper",
        "org.postgresql.xa.PGXAConnection",
        "org.postgresql.xa.RecoveredXid",
    )

    val hikari: List<String> = listOf(
        "com.zaxxer.hikari.HikariConfig",
        "com.zaxxer.hikari.HikariDataSource",
        "com.zaxxer.hikari.hibernate.HikariConnectionProvider",
        "com.zaxxer.hikari.pool.HikariPool",
        "com.zaxxer.hikari.pool.PoolBase",
        "com.zaxxer.hikari.pool.PoolEntry",
        "com.zaxxer.hikari.pool.ProxyConnection",
        "com.zaxxer.hikari.pool.ProxyLeakTask",
        "com.zaxxer.hikari.util.ConcurrentBag",
        "com.zaxxer.hikari.util.DriverDataSource",
        "com.zaxxer.hikari.util.PropertyElf",
    )

    val slf4j: List<String> = postgres + hikari + "com.zaxxer.hikari"

    fun isPostgres(name: String): Boolean = inNamespace(name, "org.postgresql")

    fun isProtected(name: String): Boolean = isPostgres(name) || inNamespace(name, "com.zaxxer.hikari")

    fun inNamespace(name: String, root: String): Boolean = name == root || name.startsWith("$root.")

    fun ancestors(names: Collection<String>): Set<String> = buildSet {
        for (name in names) {
            var dot = name.indexOf('.')
            while (dot > 0) {
                add(name.substring(0, dot))
                dot = name.indexOf('.', dot + 1)
            }
        }
    }
}
