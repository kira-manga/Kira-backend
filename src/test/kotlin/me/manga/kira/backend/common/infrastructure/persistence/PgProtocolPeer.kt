package me.manga.kira.backend.common.infrastructure.persistence

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/** All resources belong to one bounded child fixture. No ambient endpoint, password, file or service is used. */
internal class PgProtocolPeer(private val partial: Boolean) : AutoCloseable {
    private val listener = AtomicReference<ServerSocket?>()
    private val primary = AtomicReference<Socket?>()
    private val auxiliary = AtomicReference<Socket?>()
    private val problem = AtomicReference<Throwable?>()
    private val started = AtomicBoolean()
    private val bodyEntered = AtomicBoolean()
    private val protocolComplete = AtomicBoolean()
    private val cancelObserved = AtomicBoolean()
    private val terminatedByClient = AtomicBoolean()
    private val budget = PersistenceTimeBudget.start(15_000)
    private val worker = Thread.ofPlatform().name("kira-pg-protocol-peer").inheritInheritableThreadLocals(false).unstarted {
        bodyEntered.set(true)
        runCatching(::serve).onFailure(problem::set)
    }
    val address: InetAddress = InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1))
    val port: Int get() = requireNotNull(listener.get()).localPort

    fun bind() {
        check(listener.get() == null)
        listener.set(ServerSocket())
        requireNotNull(listener.get()).bind(InetSocketAddress(address, 0), 2)
    }

    fun start() {
        check(started.compareAndSet(false, true))
        check(listener.get() != null)
        worker.start()
    }

    fun verify() {
        awaitPgFixtureFact { !worker.isAlive }
        problem.get()?.let { throw it }
        check(bodyEntered.get() && protocolComplete.get()) { "Synthetic peer protocol did not complete." }
        check(partial || (cancelObserved.get() && terminatedByClient.get())) { "Synthetic cancellation or Terminate was not witnessed." }
        println(
            "PG_PEER_PROTOCOL primary=1 aux=${if (partial) 0 else 1} startup_password=true setup=${!partial} " +
                "cancel=${cancelObserved.get()} terminate_eof=${terminatedByClient.get()} partial=$partial proof=REAL_DRIVER+PROTOCOL_PEER",
        )
    }

    private fun serve() {
        val server = requireNotNull(listener.get())
        server.soTimeout = budget.remainingMillis(5_000).toInt()
        primary.set(server.accept())
        val socket = requireNotNull(primary.get())
        val channel = PgProtocolChannel(socket, budget)
        requireStartup(channel.startup())
        channel.send('R', PgProtocolChannel.integer(3))
        check(channel.receive('p').contentEquals(PgProtocolChannel.zeroTerminated(PASSWORD))) { "Synthetic password exchange differed." }
        channel.send('R', PgProtocolChannel.integer(0))
        val statuses = mapOf(
            "server_version" to "17.0",
            "server_encoding" to "UTF8",
            "client_encoding" to "UTF8",
            "DateStyle" to "ISO, MDY",
            "integer_datetimes" to "on",
            "standard_conforming_strings" to "on",
            "TimeZone" to "UTC",
        )
        statuses.forEach { (name, value) -> channel.status(name, value) }
        channel.send('K', PgProtocolChannel.integer(4_242) + byteArrayOf(1, 2, 3, 4))
        if (partial) {
            channel.partialReady()
            socket.close()
        } else {
            channel.ready()
            val query = PgProtocolChannel.zeroTerminated("SET application_name = '$APPLICATION'")
            check(channel.receive('Q').contentEquals(query)) { "Synthetic startup SET differed." }
            channel.status("application_name", APPLICATION)
            channel.send('C', PgProtocolChannel.zeroTerminated("SET"))
            channel.ready()
            receiveCancel(server)
            check(channel.receive('X').isEmpty()) { "Synthetic Terminate differed." }
            channel.requireEof()
            terminatedByClient.set(true)
        }
        protocolComplete.set(true)
    }

    private fun receiveCancel(server: ServerSocket) {
        server.soTimeout = budget.remainingMillis(5_000).toInt()
        auxiliary.set(server.accept())
        val socket = requireNotNull(auxiliary.get())
        val payload = PgProtocolChannel(socket, budget).startup()
        val expected = PgProtocolChannel.integer(80_877_102) + PgProtocolChannel.integer(4_242) + byteArrayOf(1, 2, 3, 4)
        check(payload.contentEquals(expected)) { "Synthetic CancelRequest differed." }
        cancelObserved.set(true)
        // The CancelRequest protocol has no response. This lets pgjdbc's receiveEOF actually return.
        socket.close()
    }

    private fun requireStartup(bytes: ByteArray) {
        val expected = PgProtocolChannel.integer(196_608) + listOf(
            "user", "fixture", "database", "fixture", "client_encoding", "UTF8", "DateStyle", "ISO", "TimeZone", "UTC",
        ).flatMap { PgProtocolChannel.zeroTerminated(it).toList() }.toByteArray() + byteArrayOf(0)
        check(bytes.contentEquals(expected)) { "Synthetic startup fields or protocol differed." }
    }

    override fun close() {
        // Run every cleanup even after an earlier failure; sockets are retained before setup and Thread start.
        val initial = listOfNotNull(primary.get(), auxiliary.get())
        val results = listOf(runCatching { listener.get()?.close() }) + initial.map { runCatching { it.close() } }
        val exit = runCatching { awaitPgFixtureFact { !worker.isAlive } }
        // An accept already in progress can store its returned socket after the first cleanup census.
        val sockets = listOfNotNull(primary.get(), auxiliary.get())
        val lateResults = sockets.map { runCatching { it.close() } }
        results.forEach { it.getOrThrow() }
        lateResults.forEach { it.getOrThrow() }
        exit.getOrThrow()
        check(sockets.all(Socket::isClosed) && listener.get()?.isClosed != false)
        println(
            "PG_PEER_CLEANUP thread_id=${worker.threadId()} started=${started.get()} sockets=${sockets.size} " +
                "listeners=${if (listener.get() == null) 0 else 1} all_terminated=true api_closed=true",
        )
    }

    companion object {
        const val APPLICATION = "wip13-peer"
        const val PASSWORD = "invented-peer-password"
    }
}

internal fun awaitPgFixtureFact(predicate: () -> Boolean) {
    val budget = PersistenceTimeBudget.start(7_000)
    while (!predicate()) LockSupport.parkNanos(budget.remainingMillis(1) * 1_000_000)
}
