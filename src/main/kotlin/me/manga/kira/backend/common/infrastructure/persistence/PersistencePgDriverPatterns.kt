package me.manga.kira.backend.common.infrastructure.persistence

/** pgjdbc 42.7.12's five allocation sites and public reflection route, prepared outside F/G/T. */
internal class PersistencePgDriverPatterns(image: PersistencePgDriverImage) {
    private val stream = image.type("org.postgresql.core.PGStream")
    private val connectionFactory = image.type("org.postgresql.core.v3.ConnectionFactoryImpl")
    private val queryExecutor = image.type("org.postgresql.core.QueryExecutorBase")
    private val streamDescriptor = "Lorg/postgresql/core/PGStream;"
    private val hostDescriptor = "Lorg/postgresql/util/HostSpec;"
    private val propertiesDescriptor = "Ljava/util/Properties;"
    private val socketFactoryDescriptor = "Ljavax/net/SocketFactory;"
    private val sslDescriptor = "Lorg/postgresql/jdbc/SslMode;"
    private val gssDescriptor = "Lorg/postgresql/jdbc/GSSEncMode;"
    private val openDescriptor = "([$hostDescriptor$propertiesDescriptor)Lorg/postgresql/core/QueryExecutor;"
    private val streamNew = frame(stream, "<init>", "($socketFactoryDescriptor${hostDescriptor}II)V")
    private val streamCopy = frame(stream, "<init>", "(${streamDescriptor}I)V")
    private val streamCreate = frame(stream, "createSocket", "(I)Ljava/net/Socket;")
    private val tryConnect = frame(
        connectionFactory,
        "tryConnect",
        "($propertiesDescriptor$socketFactoryDescriptor$hostDescriptor$sslDescriptor${gssDescriptor}IJ)$streamDescriptor",
    )
    private val driverTail = listOf(
        frame(connectionFactory, "openConnectionImpl", openDescriptor),
        frame(image.type("org.postgresql.core.ConnectionFactory"), "openConnection", openDescriptor),
        frame(image.type("org.postgresql.jdbc.PgConnection"), "<init>", "([$hostDescriptor${propertiesDescriptor}Ljava/lang/String;)V"),
        frame(image.driver, "makeConnection", "(Ljava/lang/String;$propertiesDescriptor)Ljava/sql/Connection;"),
        frame(image.driver, "connect", "(Ljava/lang/String;$propertiesDescriptor)Ljava/sql/Connection;"),
        frame(
            PersistencePgDriverOpening::class.java,
            "connect",
            "(${descriptor(PersistencePhysicalFactoryBinding::class.java)}${descriptor(PersistencePhysicalEntry::class.java)}" +
                "$propertiesDescriptor)${descriptor(PersistencePhysicalOpening::class.java)}",
        ),
    )
    private val allocationPrefix = listOf(
        frame(
            PersistencePgDriverFrames::class.java,
            "classifyAllocation",
            "(${descriptor(PersistencePgDriverImage::class.java)})${descriptor(PersistenceTransportRole::class.java)}",
        ),
        frame(PersistencePgFactoryScope::class.java, "createSocket", "(${descriptor(TrackedPgSocketFactory::class.java)})Ljava/net/Socket;"),
        frame(TrackedPgSocketFactory::class.java, "createSocket", "()Ljava/net/Socket;"),
        streamCreate,
    )

    val constructor: List<PersistencePgFrame> = listOf(
        frame(PersistencePgDriverFrames::class.java, "constructorAllowed", "(${descriptor(PersistencePgDriverImage::class.java)})Z"),
        frame(
            PersistencePgFactoryScope.Companion::class.java,
            "capture",
            "(${descriptor(TrackedPgSocketFactory::class.java)})${descriptor(PersistencePgFactoryScope::class.java)}",
        ),
        frame(TrackedPgSocketFactory::class.java, "<init>", "()V"),
        frame(
            image.type("org.postgresql.util.ObjectFactory"),
            "instantiate",
            "(Ljava/lang/Class;Ljava/lang/String;${propertiesDescriptor}ZLjava/lang/String;)Ljava/lang/Object;",
        ),
        frame(image.type("org.postgresql.core.SocketFactoryFactory"), "getSocketFactory", "($propertiesDescriptor)$socketFactoryDescriptor"),
    ) + driverTail

    val auxiliary: List<PersistencePgFrame> = allocationPrefix + listOf(streamNew, frame(queryExecutor, "sendQueryCancel", "()V"))
    val primary: List<List<PersistencePgFrame>> = primaryPatterns()
    val auxiliaryClose: List<PersistencePgFrame> = listOf(
        frame(PersistencePgDriverFrames::class.java, "auxiliaryCloseAllowed", "(${descriptor(PersistencePgDriverImage::class.java)})Z"),
        frame(PersistencePgTransportOrigin::class.java, "isDirectAuxiliaryClose", "()Z"),
        frame(
            PersistenceTransportBinding::class.java,
            "captureAuxiliaryClose",
            "(${descriptor(TrackedPersistenceSocket::class.java)})${descriptor(PersistenceTransportAuxiliaryCloseReceipt::class.java)}",
        ),
        frame(TrackedPersistenceSocket::class.java, "close", "()V"),
        frame(stream, "close", "()V"),
        frame(queryExecutor, "sendQueryCancel", "()V"),
    )

    private fun primaryPatterns(): List<List<PersistencePgFrame>> {
        val gss = frame(
            connectionFactory,
            "enableGSSEncrypted",
            "($streamDescriptor${gssDescriptor}Ljava/lang/String;${propertiesDescriptor}I)$streamDescriptor",
        )
        val ssl = frame(connectionFactory, "enableSSL", "($streamDescriptor$sslDescriptor${propertiesDescriptor}I)$streamDescriptor")
        val sites = listOf(listOf(streamNew), listOf(streamNew, gss), listOf(streamCopy, gss), listOf(streamCopy, ssl))
        val retryDescriptor =
            "($propertiesDescriptor$socketFactoryDescriptor$hostDescriptor${gssDescriptor}IJLjava/lang/Exception;)$streamDescriptor"
        val retries = listOf(
            emptyList(),
            listOf(frame(connectionFactory, "tryConnectWithoutSsl", retryDescriptor)),
            listOf(frame(connectionFactory, "tryConnectWithSsl", retryDescriptor)),
        )
        return sites.flatMap { site -> retries.map { retry -> allocationPrefix + site + tryConnect + retry + driverTail } }
    }

    override fun toString(): String = "PersistencePgDriverPatterns(redacted)"

    private fun descriptor(type: Class<*>): String = type.descriptorString()

    private fun frame(type: Class<*>, method: String, descriptor: String): PersistencePgFrame = PersistencePgFrame(type, method, descriptor)
}
