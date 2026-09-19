package me.manga.kira.backend.complaint.infrastructure.catalog

import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.infrastructure.catalog.aws.AwsCatalogSigningAdapterV1
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials
import software.amazon.awssdk.http.SdkHttpClient

/** One retained real SDK Sign. No standalone provider result, replacement construction or restarted allowance. */
internal class CatalogTestRunActivationAssemblyV1(
    private val original: CatalogTestRunActivationV1,
    private val signingHttpFactory: (() -> SdkHttpClient)?,
) : AutoCloseable {
    private var signer: AwsCatalogSigningAdapterV1.Construction? = null
    private var signerCleanupProven = false
    private var closeFailure: Throwable? = null
    private var closed = false

    internal fun sign(input: CatalogTestRunActivationFrozenV1, credentials: AwsSessionCredentials): ByteArray {
        requireConnectionFree()
        original.requireSignConstruction(this, input) // Spend the actual new durable arm BEFORE any constructor/native open.
        requireTestActivation(!closed && signer == null)
        val construction = AwsCatalogSigningAdapterV1.Construction(original.budget.capped(10_000L))
        signer = construction // Retain even a failed factory that never returns its native owner.
        val bytes = withSignerRotationCleanup(
            {
                original.requireSignProviderRunning(this)
                val key = original.process.catalogActivation.signingKey // Current Stable SINGLE, never fixed G1/G2/G3.
                val adapter = if (signingHttpFactory == null) {
                    AwsCatalogSigningAdapterV1.openOwned(construction, key, credentials)
                } else {
                    AwsCatalogSigningAdapterV1.withHttpFixture(construction, key, credentials, signingHttpFactory)
                }
                original.requireSignProviderRunning(this)
                adapter.sign(input.unsignedBytes())
            },
            ::closeSigner,
        )
        requireCleanup()
        original.requireSignProviderRunning(this)
        return bytes
    }

    internal fun requireCleanup() {
        requireConnectionFree()
        requireTestActivation(signer == null || (signerCleanupProven && closeFailure == null), CatalogTestRunActivationFailureV1.CLEANUP_UNPROVEN)
    }

    private fun closeSigner() {
        val failure = signer?.let { runCatching(it::close).exceptionOrNull() }
        if (failure != null) {
            original.observeFailure(failure)
            closeFailure = preferSignerRotationCleanup(closeFailure, failure)
        }
        closeFailure?.let { throw it }
        signerCleanupProven = true
    }

    override fun close() {
        closed = true
        closeSigner() // Actual cleanup precedes caller/expiry/cancellation acceptance checks in the original owner.
        requireCleanup()
    }

    override fun toString(): String = "CatalogTestRunActivationAssemblyV1(original-one-Sign,no-PUT,redacted)"
}
