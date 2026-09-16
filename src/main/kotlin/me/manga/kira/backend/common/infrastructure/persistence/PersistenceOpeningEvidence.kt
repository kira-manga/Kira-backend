package me.manga.kira.backend.common.infrastructure.persistence

import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.sql.SQLException

internal enum class PersistenceOpeningFailureSite {
    DRIVER_CONNECT,
    OPENING_FALLBACK,
    SCOPE_LEAVE,
}

internal enum class PersistenceFailureType {
    SOCKET_TIMEOUT,
    CONNECT,
    SOCKET,
    EOF,
    IO,
    SQL,
    INTERRUPTED,
    BOUNDARY,
    STATE_CHECK,
    ERROR,
    OTHER,
}

/** A capture span/type, not a Throwable, driver cause, result authority or resource reference. */
internal class PersistenceOpeningFailure(val site: PersistenceOpeningFailureSite, val type: PersistenceFailureType)

internal enum class PersistenceConstructionSite {
    BOUND_PREPARE,
    BOUND_INSTALL,
    CONSTRUCTION_SPAN,
}

internal enum class PersistenceConstructionDisposition {
    REFUSED,
    RETAINED,
    THREW,
}

/** First failure-to-provide evidence per role. RETAINED is not a failed native constructor. */
internal class PersistenceConstructionEvidence(
    val site: PersistenceConstructionSite,
    val disposition: PersistenceConstructionDisposition,
    val refusal: PersistenceTransportRefusal? = null,
    val type: PersistenceFailureType? = null,
)

/**
 * Fixed closed records, initialized by BOTH opening preparation paths outside F/G/T, not by Entry
 * construction under G. Capture uses no allocation, enum switch initializer or Throwable accessor.
 * Coverage excludes predecessor-close throws, false scope-leave returns and opaque business/finally
 * failures. Missing evidence is NOT_RECORDED, never proof of success or absence of a failure.
 */
internal object PersistenceOpeningEvidence {
    private val openings = PersistenceOpeningFailureSite.entries.map { site ->
        PersistenceFailureType.entries.map { PersistenceOpeningFailure(site, it) }
    }
    private val refusals = PersistenceConstructionSite.entries.map { site ->
        PersistenceTransportRefusal.entries.map { PersistenceConstructionEvidence(site, PersistenceConstructionDisposition.REFUSED, refusal = it) }
    }
    private val thrown = PersistenceFailureType.entries.map {
        PersistenceConstructionEvidence(PersistenceConstructionSite.CONSTRUCTION_SPAN, PersistenceConstructionDisposition.THREW, type = it)
    }
    val retained = PersistenceConstructionEvidence(PersistenceConstructionSite.CONSTRUCTION_SPAN, PersistenceConstructionDisposition.RETAINED)

    /** Calling this initializes this object's complete fixed catalog, including every enum family. */
    fun prepareRuntime() = Unit

    fun opening(site: PersistenceOpeningFailureSite, failure: Throwable): PersistenceOpeningFailure = openings[site.ordinal][type(failure).ordinal]

    fun refused(site: PersistenceConstructionSite, refusal: PersistenceTransportRefusal): PersistenceConstructionEvidence =
        refusals[site.ordinal][refusal.ordinal]

    fun thrown(failure: Throwable): PersistenceConstructionEvidence = thrown[type(failure).ordinal]

    private fun type(failure: Throwable): PersistenceFailureType = when (failure) {
        is SocketTimeoutException -> PersistenceFailureType.SOCKET_TIMEOUT
        is ConnectException -> PersistenceFailureType.CONNECT
        is SocketException -> PersistenceFailureType.SOCKET
        is EOFException -> PersistenceFailureType.EOF
        is IOException -> PersistenceFailureType.IO
        is SQLException -> PersistenceFailureType.SQL
        is InterruptedException -> PersistenceFailureType.INTERRUPTED
        is PersistenceBoundaryException -> PersistenceFailureType.BOUNDARY
        is IllegalStateException -> PersistenceFailureType.STATE_CHECK
        is Error -> PersistenceFailureType.ERROR
        else -> PersistenceFailureType.OTHER
    }
}
