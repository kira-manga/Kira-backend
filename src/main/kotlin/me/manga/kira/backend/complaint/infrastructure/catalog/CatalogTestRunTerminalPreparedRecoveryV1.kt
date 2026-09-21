package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.admission.VersionBoundTestNamespaceProcessV1
import software.amazon.awssdk.http.SdkHttpClient
import java.time.Clock
import java.util.UUID

/**
 * A different ORIGINAL, on a freshly retained normal-runtime process with the same pre-D pins.
 * The fixed UUID is merely a selector. CAPTURE must find the exact paid row before adopting its
 * unsigned bytes; re-supplied signed/raw closures, current SQL, real inventories and immutable
 * external release custody are mandatory again. No D/registration/cut/DTO success is fabricated.
 */
internal class CatalogTestRunTerminalPreparedRecoveryV1 private constructor(
    internal val process: VersionBoundTestNamespaceProcessV1,
    internal val token: UUID,
    internal val installationLimit: Long,
) : AutoCloseable {
    private val caller = Thread.currentThread()
    private var original: CatalogTestRunTerminalV1? = null
    private var attempted = false
    private var closed = false

    init {
        requireConnectionFree()
        requireTestTerminalCatalog(token.version() == 4 && token.variant() == 2 && installationLimit > 0)
        process.claimTerminalPreparedRecovery(this) // Same fresh-process CAS as registration; never reusable.
        CatalogTestRunTerminalV1.forRecovery(this)
        requireTestTerminalCatalog(original != null)
    }

    internal fun retainOriginal(value: CatalogTestRunTerminalV1) {
        requireConnectionFree()
        requireTestTerminalCatalog(caller === Thread.currentThread() && !closed && !attempted && original == null && value.process === process)
        original = value
    }
    internal fun requireOriginal(value: CatalogTestRunTerminalV1) {
        // Local identity only, including inside the original's SQL holder. No budget/authority revival.
        requireTestTerminalCatalog(caller === Thread.currentThread() && !closed && original === value && value.process === process)
    }

    fun resume(request: CatalogTestRunTerminalRequestV1): CatalogTestRunTerminalResultV1 {
        requireConnectionFree(); requireTestTerminalCatalog(caller === Thread.currentThread() && !closed && !attempted)
        attempted = true
        return checkNotNull(original).resumePrepared(this, request)
    }

    /** Raw transport fixtures on this already-claimed original; no proof/row/owner replacement. */
    internal fun withHttpFixtures(signing: () -> SdkHttpClient, put: () -> SdkHttpClient, readback: () -> SdkHttpClient,
        ordinaryS3: () -> SdkHttpClient, ordinaryKms: () -> SdkHttpClient, clock: Clock): CatalogTestRunTerminalPreparedRecoveryV1 {
        requireConnectionFree(); requireTestTerminalCatalog(caller === Thread.currentThread() && !closed && !attempted)
        checkNotNull(original).withHttpFixtures(signing, put, readback, ordinaryS3, ordinaryKms, clock)
        return this
    }

    override fun close() {
        requireConnectionFree(); requireTestTerminalCatalog(caller === Thread.currentThread())
        try { original?.close() } finally { closed = true }
    }
    override fun toString(): String = "CatalogTestRunTerminalPreparedRecoveryV1(fresh-original,exact-token,redacted,no-row-authority)"
    companion object {
        fun begin(process: VersionBoundTestNamespaceProcessV1, token: UUID, installationLimit: Long): CatalogTestRunTerminalPreparedRecoveryV1 =
            CatalogTestRunTerminalPreparedRecoveryV1(process, token, installationLimit)
    }
}
