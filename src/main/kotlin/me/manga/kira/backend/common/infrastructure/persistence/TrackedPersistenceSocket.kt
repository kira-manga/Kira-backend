package me.manga.kira.backend.common.infrastructure.persistence

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.net.SocketException
import java.net.SocketOption

/**
 * Unused real Socket adapter, not a delegating facade. Selected Java 21 state/no-work methods remain inherited.
 * BUSINESS refusal, OBSERVATION overload refusal and coalesced close are intentional private-contract differences.
 */
internal class TrackedPersistenceSocket : Socket {
    private val binding: PersistenceTransportBinding

    private constructor(binding: PersistenceTransportBinding) : super() {
        this.binding = binding
    }

    private constructor(direct: DirectBinding) : super(Proxy.NO_PROXY) {
        binding = direct.binding
    }

    @Throws(IOException::class)
    override fun connect(endpoint: SocketAddress?) = binding.business { super.connect(endpoint, 0) }

    @Throws(IOException::class)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = binding.business { super.connect(endpoint, timeout) }

    @Throws(IOException::class)
    override fun bind(bindpoint: SocketAddress?) = binding.business { super.bind(bindpoint) }

    override fun getInetAddress(): InetAddress? = binding.observation { super.getInetAddress() }

    override fun getLocalAddress(): InetAddress = binding.observation { super.getLocalAddress() }

    override fun getPort(): Int = binding.observation { super.getPort() }

    override fun getLocalPort(): Int = binding.observation { super.getLocalPort() }

    override fun getRemoteSocketAddress(): SocketAddress? = binding.observation {
        if (!super.isConnected()) null else InetSocketAddress(super.getInetAddress(), super.getPort())
    }

    override fun getLocalSocketAddress(): SocketAddress? = binding.observation {
        if (!super.isBound()) null else InetSocketAddress(super.getLocalAddress(), super.getLocalPort())
    }

    @Throws(IOException::class)
    override fun getInputStream(): InputStream = binding.business { binding.input(super.getInputStream(), this) }

    @Throws(IOException::class)
    override fun getOutputStream(): OutputStream = binding.business { binding.output(super.getOutputStream(), this) }

    @Throws(SocketException::class)
    override fun setTcpNoDelay(on: Boolean) = binding.business { super.setTcpNoDelay(on) }

    @Throws(SocketException::class)
    override fun getTcpNoDelay(): Boolean = binding.business { super.getTcpNoDelay() }

    @Throws(SocketException::class)
    override fun setSoLinger(on: Boolean, linger: Int) = binding.business { super.setSoLinger(on, linger) }

    @Throws(SocketException::class)
    override fun getSoLinger(): Int = binding.business { super.getSoLinger() }

    @Throws(IOException::class)
    override fun sendUrgentData(data: Int) = binding.business { super.sendUrgentData(data) }

    @Throws(SocketException::class)
    override fun setOOBInline(on: Boolean) = binding.business { super.setOOBInline(on) }

    @Throws(SocketException::class)
    override fun getOOBInline(): Boolean = binding.business { super.getOOBInline() }

    @Throws(SocketException::class)
    override fun setSoTimeout(timeout: Int) = binding.business { super.setSoTimeout(timeout) }

    @Throws(SocketException::class)
    override fun getSoTimeout(): Int = binding.business { super.getSoTimeout() }

    @Throws(SocketException::class)
    override fun setSendBufferSize(size: Int) = binding.business { super.setSendBufferSize(size) }

    @Throws(SocketException::class)
    override fun getSendBufferSize(): Int = binding.business { super.getSendBufferSize() }

    @Throws(SocketException::class)
    override fun setReceiveBufferSize(size: Int) = binding.business { super.setReceiveBufferSize(size) }

    @Throws(SocketException::class)
    override fun getReceiveBufferSize(): Int = binding.business { super.getReceiveBufferSize() }

    @Throws(SocketException::class)
    override fun setKeepAlive(on: Boolean) = binding.business { super.setKeepAlive(on) }

    @Throws(SocketException::class)
    override fun getKeepAlive(): Boolean = binding.business { super.getKeepAlive() }

    @Throws(SocketException::class)
    override fun setTrafficClass(tc: Int) = binding.business { super.setTrafficClass(tc) }

    @Throws(SocketException::class)
    override fun getTrafficClass(): Int = binding.business { super.getTrafficClass() }

    @Throws(SocketException::class)
    override fun setReuseAddress(on: Boolean) = binding.business { super.setReuseAddress(on) }

    @Throws(SocketException::class)
    override fun getReuseAddress(): Boolean = binding.business { super.getReuseAddress() }

    @Throws(IOException::class)
    override fun close() {
        val receipt = binding.captureAuxiliaryClose(this)
        binding.close(this) { super.close() }
        binding.publishAuxiliaryClose(receipt)
    }

    fun hasBinding(expected: PersistenceTransportBinding): Boolean = binding === expected

    @Throws(IOException::class)
    override fun shutdownInput() = binding.business { super.shutdownInput() }

    @Throws(IOException::class)
    override fun shutdownOutput() = binding.business { super.shutdownOutput() }

    @Throws(IOException::class)
    override fun <T : Any?> setOption(name: SocketOption<T>?, value: T): Socket = binding.business {
        super.setOption(name, value)
        this
    }

    @Throws(IOException::class)
    override fun <T : Any?> getOption(name: SocketOption<T>?): T = binding.business { super.getOption(name) }

    override fun supportedOptions(): Set<SocketOption<*>> = binding.observation { super.supportedOptions() }

    override fun toString(): String = "TrackedPersistenceSocket(redacted)"

    private class DirectBinding(val binding: PersistenceTransportBinding)

    /** Binding/allocation identity is prepared before G→T admission; construct is invoked after both locks are released. */
    internal class Prepared(
        private val owner: PersistenceTransportOwner<TrackedPersistenceSocket>,
        role: PersistenceTransportRole,
        direct: Boolean,
        origin: PersistencePgTransportOrigin? = null,
    ) {
        private val ticket = if (origin == null) owner.prepareConstruction(role) else owner.prepareBoundConstruction(origin.extent)
        private val binding = PersistenceTransportBinding(owner, ticket.record, origin)
        private val directBinding = if (direct) DirectBinding(binding) else null
        val record: PersistenceTransportRecord get() = ticket.record

        fun reserve(): PersistenceTransportRefusal? = owner.reserveConstruction(ticket)

        fun prepareBoundReservation(): PersistenceTransportRefusal? = owner.prepareBoundReservation(ticket)

        fun reserveBound(): PersistenceTransportRefusal? = owner.reserveBoundConstruction(ticket)

        fun abandonBound() = owner.abandonBoundConstruction(ticket)

        fun closePredecessor() {
            if (record.role === PersistenceTransportRole.PRIMARY) ticket.predecessor?.let { owner.requestClose(it) }
        }

        fun construct(): PersistenceTransportCreation<TrackedPersistenceSocket> = owner.constructReserved(ticket) {
            if (directBinding == null) TrackedPersistenceSocket(binding) else TrackedPersistenceSocket(directBinding)
        }

        override fun toString(): String = "TrackedPersistenceSocket.Prepared(redacted)"
    }

    companion object {
        fun prepare(owner: PersistenceTransportOwner<TrackedPersistenceSocket>, role: PersistenceTransportRole): Prepared =
            Prepared(owner, role, direct = false)

        fun prepareApprovedDirect(owner: PersistenceTransportOwner<TrackedPersistenceSocket>, role: PersistenceTransportRole): Prepared =
            Prepared(owner, role, direct = true)

        fun prepareBound(owner: PersistenceTransportOwner<TrackedPersistenceSocket>, origin: PersistencePgTransportOrigin, direct: Boolean): Prepared =
            Prepared(owner, origin.extent.role, direct, origin)

        fun tryCreate(
            owner: PersistenceTransportOwner<TrackedPersistenceSocket>,
            role: PersistenceTransportRole,
        ): PersistenceTransportCreation<TrackedPersistenceSocket> {
            val prepared = prepare(owner, role)
            val refusal = prepared.reserve()
            return if (refusal == null) prepared.construct() else PersistenceTransportCreation.Refused(refusal)
        }

        /** Only a later approved direct-database-routing policy may select this distinct constructor. */
        fun tryCreateApprovedDirect(
            owner: PersistenceTransportOwner<TrackedPersistenceSocket>,
            role: PersistenceTransportRole,
        ): PersistenceTransportCreation<TrackedPersistenceSocket> {
            val prepared = prepareApprovedDirect(owner, role)
            val refusal = prepared.reserve()
            return if (refusal == null) prepared.construct() else PersistenceTransportCreation.Refused(refusal)
        }
    }
}
