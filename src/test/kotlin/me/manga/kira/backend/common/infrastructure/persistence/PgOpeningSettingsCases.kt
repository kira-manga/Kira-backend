package me.manga.kira.backend.common.infrastructure.persistence

import java.util.concurrent.atomic.AtomicBoolean

/** Cold settings/association assertions in a sanitized child. No connection or protocol peer is started. */
internal object PgOpeningSettingsCases {
    fun verify() {
        val prepared = PersistenceDriverBootstrap.prepare()
        val base = pgProbeEndpoint(1)
        val before = snapshot(base)
        val ordinary = PersistencePgDriverOpening.prepare(prepared, base, PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT, PersistencePathStyle.POSIX)
        check(endpoint(ordinary) === base && snapshot(endpoint(ordinary)) == before)
        check(ordinary.loginPolicy === base.loginPolicy && ordinary.image != null)
        val deletion = PersistencePgDriverOpening.prepare(
            prepared,
            base,
            PersistenceDriverAttemptPolicy.TRACKED_DELETION_CONJUNCTION,
            PersistencePathStyle.POSIX,
        )
        val derived = PersistenceNativeSettings.deriveDeletion(base, PersistencePathStyle.POSIX) as PersistenceNativeSettingsResult.Supported
        check(snapshot(endpoint(deletion)) == snapshot(derived.endpoint) && deletion.loginPolicy.durationMillis == 2_000L)
        check(snapshot(base) == before) { "Preparing deletion mutated the original settings." }
        val unsupported = pgProbeEndpoint(1, mapOf("socketFactoryArg" to "synthetic-provider-property"))
        check(PersistenceNativeSettings.assessOrdinary(unsupported, PersistencePathStyle.POSIX) is PersistenceNativeSettingsResult.Unsupported)
        val original = PersistencePgDriverOpening.prepare(prepared, unsupported, PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, PersistencePathStyle.POSIX)
        check(endpoint(original) === unsupported && original.image == null && original.loginPolicy === unsupported.loginPolicy)
        requireBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED) {
            PersistencePgDriverOpening.prepare(prepared, unsupported, PersistenceDriverAttemptPolicy.TRACKED_ORDINARY_CONTRACT, PersistencePathStyle.POSIX)
        }
        val nonzero = pgProbeEndpoint(1, mapOf("loginTimeout" to "1"))
        requireBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY) {
            PersistencePgDriverOpening.prepare(prepared, nonzero, PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, PersistencePathStyle.POSIX)
        }
        requireAssociations(ordinary, deletion)
        println("PG_SETTINGS_VERIFIED original_preserved=true ordinary_preserved=true deletion_derived=true policy_budget_bound=true no_driver_connect=true")
    }

    private fun requireAssociations(ordinary: PersistencePgDriverOpening, deletion: PersistencePgDriverOpening) {
        val binding = PersistencePhysicalFactoryBinding(1, AtomicBoolean())
        for (opening in listOf(ordinary, deletion)) {
            val request = PersistenceOwnedFactoryRequest(binding, opening)
            fun field(name: String): Any? = PersistenceOwnedFactoryRequest::class.java.getDeclaredField(name).also { it.isAccessible = true }.get(request)
            check(field("opening") === opening && field("policy") === opening.policy)
            check(field("allowanceMillis") == opening.loginPolicy.durationMillis)
        }
        val control = PersistenceOwnedCallerControl.prepare(6_000)
        try {
            binding.reserve(control, PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, ordinary)
            error("Contradictory reservation policy was accepted.")
        } catch (_: IllegalArgumentException) {
            check(binding.ledger.entries.all { it == null })
        }
        try {
            PersistencePhysicalEntry(PersistencePhysicalRecord(0), control, PersistenceDriverAttemptPolicy.ORIGINAL_PROVIDER, binding, ordinary)
            error("Contradictory Entry policy was accepted.")
        } catch (_: IllegalArgumentException) {
            check(binding.ledger.entries.all { it == null })
        }
    }

    private fun endpoint(opening: PersistencePgDriverOpening): ResolvedPersistenceEndpoint =
        PersistencePgDriverOpening::class.java.getDeclaredField("endpoint").also { it.isAccessible = true }.get(opening) as ResolvedPersistenceEndpoint

    private fun snapshot(endpoint: ResolvedPersistenceEndpoint): Map<String, String> {
        val properties = endpoint.driverProperties()
        return properties.stringPropertyNames().associateWith(properties::getProperty)
    }

    private fun requireBoundary(code: PersistenceBoundaryFailureCode, action: () -> Unit) {
        try {
            action()
            error("Expected synthetic settings refusal.")
        } catch (failure: PersistenceBoundaryException) {
            check(failure.code === code && failure.cause == null && failure.suppressed.isEmpty())
        }
    }
}
