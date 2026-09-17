package me.manga.kira.backend.common.infrastructure.persistence

import java.sql.Connection
import java.sql.Driver
import java.util.Properties

/** One prepared recipe, not readiness, a DataSource, a JDBC escape or a terminal-resource adapter. */
internal class PersistencePgDriverOpening private constructor(
    private val driver: Driver,
    private val endpoint: ResolvedPersistenceEndpoint,
    val policy: PersistenceDriverAttemptPolicy,
    val image: PersistencePgDriverImage?,
    val cut: PersistencePgOwnedCutAccess,
    val timer: PersistenceDriverTimer? = null,
) {
    val loginPolicy: PersistenceLoginPolicy = endpoint.loginPolicy

    fun invoke(physical: PersistencePhysicalFactoryBinding, record: PersistencePhysicalRecord): PersistencePhysicalOpening {
        if (!physical.isOwnedWorkerThread()) return PersistencePhysicalOpening.REFUSED
        if (Thread.currentThread().isInterrupted) return PersistencePhysicalOpening.INTERRUPTED
        val entry = claim(physical, record) ?: return PersistencePhysicalOpening.REFUSED
        val result: PersistencePhysicalOpening
        val scopeEnded: Boolean
        var returned = false
        try {
            result = runCatching {
                try {
                    val properties = effectiveDriverProperties(endpoint, policy)
                    entry.driverScope?.enter()
                    // Independent of the transport image, including ORIGINAL_PROVIDER. Retain before arm/connect.
                    entry.driverCut.begin(driver, cut)
                    val outcome = connect(physical, entry, properties)
                    returned = true
                    outcome
                } finally {
                    // Publish actual failure exit before the enclosing Result boxes a Throwable, even after properties/scope entry failure.
                    if (!returned) {
                        entry.retirementRequested.set(true)
                        settleOpening(physical, entry, PersistencePhysicalOpening.FAILED)
                    }
                }
            }.getOrElse { failure -> failOpening(physical, entry, failure) }
        } finally {
            // The cut observer never throws over the primary connect failure. Still restore the transport scope.
            val cutEnded = entry.driverCut.endOpening()
            scopeEnded = finishScope(physical, entry) && cutEnded
        }
        return if (scopeEnded) result else PersistencePhysicalOpening.FAILED
    }

    private fun connect(physical: PersistencePhysicalFactoryBinding, entry: PersistencePhysicalEntry, properties: Properties): PersistencePhysicalOpening {
        entry.openingFacts.driverEntered.set(true)
        var nativeReturned = false
        try {
            runCatching {
                // Literally adjacent normal-return retention; no diagnostic action may intervene.
                val raw: Connection? = driver.connect(endpoint.driverUrl, properties)
                entry.raw.set(raw)
                nativeReturned = true
                entry.driverCut.returned(raw)
            }.onFailure { failure ->
                val site = if (nativeReturned) PersistenceOpeningFailureSite.OPENING_FALLBACK else PersistenceOpeningFailureSite.DRIVER_CONNECT
                entry.openingFacts.recordFailure(site, failure)
            }.getOrThrow()
        } finally {
            entry.openingFacts.driverEnded.set(true)
        }
        val outcome = when {
            Thread.currentThread().isInterrupted -> PersistencePhysicalOpening.INTERRUPTED
            entry.raw.get() == null -> PersistencePhysicalOpening.NO_RAW_RETURN
            else -> PersistencePhysicalOpening.RETAINED
        }
        return settleOpening(physical, entry, outcome)
    }

    private fun claim(physical: PersistencePhysicalFactoryBinding, record: PersistencePhysicalRecord): PersistencePhysicalEntry? {
        val ledger = physical.ledger
        // Already admitted, authentic managed F1, with no F/G/T held. Maintenance contention is
        // not a new creation failure. External admission and the unbound helper remain fail-fast.
        if (physical.isManagedOpeningWorker()) {
            ledger.lock.lock()
        } else if (!ledger.lock.tryLock()) {
            return null
        }
        return try {
            val entry = ledger.current(record) ?: return null
            val control = entry.control ?: return null
            val attempt = entry.attempt ?: return null
            val identity = claimIdentityMatches(entry, record, control, attempt)
            val unavailable = Thread.currentThread().isInterrupted || physical.isClosed() || ledger.sealed ||
                !physical.admissionOpen.get() || entry.retiring ||
                entry.retirementRequested.get() || entry.unknown || control.state() !== PersistenceOwnedCallerDisposition.ATTACHED ||
                attempt.cancellation.isRequested() || persistenceFactoryRemainingMillis(control.budget) == 0L
            val unclaimed = entry.dispatched && entry.opening === PersistencePhysicalOpeningPhase.UNCLAIMED &&
                entry.raw.get() == null && entry.terminal == null && (image == null || entry.driverScope != null)
            if (identity && !unavailable && unclaimed) {
                // Only G-owned fields and stable pre-dispatch associations; never F.current or attempt.phase.
                entry.opening = PersistencePhysicalOpeningPhase.ACTIVE
                entry
            } else {
                null
            }
        } finally {
            ledger.lock.unlock()
        }
    }

    private fun claimIdentityMatches(
        entry: PersistencePhysicalEntry,
        record: PersistencePhysicalRecord,
        control: PersistenceOwnedCallerControl,
        attempt: PersistenceFactoryAttempt<PersistencePhysicalRecord, PersistenceJdbcCandidate>,
    ): Boolean = entry.driverOpening === this && entry.policy === policy && control.matchesRecord(record) &&
        attempt.input === record && attempt.ownedControl === control &&
        attempt.budget === control.budget && attempt.receipt === control.receipt

    private fun failOpening(physical: PersistencePhysicalFactoryBinding, entry: PersistencePhysicalEntry, failure: Throwable): PersistencePhysicalOpening {
        entry.openingFacts.recordFailure(PersistenceOpeningFailureSite.OPENING_FALLBACK, failure)
        if (failure is Error) entry.openingFacts.fatal.set(true)
        entry.retirementRequested.set(true)
        val interrupted = failure is InterruptedException || Thread.currentThread().isInterrupted
        val outcome = settleOpening(physical, entry, if (interrupted) PersistencePhysicalOpening.INTERRUPTED else PersistencePhysicalOpening.FAILED)
        if (failure is InterruptedException) Thread.currentThread().interrupt()
        if (failure is Error) throw failure
        return outcome
    }

    private fun settleOpening(
        physical: PersistencePhysicalFactoryBinding,
        entry: PersistencePhysicalEntry,
        outcome: PersistencePhysicalOpening,
    ): PersistencePhysicalOpening {
        check(physical.isOwnedWorkerThread())
        val ledger = physical.ledger
        ledger.lock.lock()
        try {
            check(ledger.current(entry.record) === entry && entry.driverOpening === this)
            entry.opening = PersistencePhysicalOpeningPhase.SETTLED
            entry.openingFacts.outcome.set(outcome)
            // Preowned monotone bookkeeping only: no T, scope callback, close or successful-PRIMARY fence beneath G.
            entry.driverScope?.extentSource?.primaryOpeningEnded?.set(true)
            if (outcome !== PersistencePhysicalOpening.RETAINED || physical.isClosed() || ledger.sealed) entry.retirementRequested.set(true)
            if (outcome === PersistencePhysicalOpening.FAILED || outcome === PersistencePhysicalOpening.INTERRUPTED) entry.unknown = true
            if (entry.control?.state() !== PersistenceOwnedCallerDisposition.ATTACHED || entry.attempt?.cancellation?.isRequested() != false) {
                entry.retirementRequested.set(true)
            }
            return if (outcome === PersistencePhysicalOpening.RETAINED && entry.retirementRequested.get()) {
                PersistencePhysicalOpening.RETAINED_FOR_RETIREMENT
            } else {
                outcome
            }
        } finally {
            ledger.lock.unlock()
        }
    }

    private fun finishScope(physical: PersistencePhysicalFactoryBinding, entry: PersistencePhysicalEntry): Boolean {
        var ended = false
        try {
            ended = runCatching {
                // No scope for ORIGINAL_PROVIDER: no outstanding capture cleanup, not tracking success.
                entry.driverScope?.leave() ?: true
            }.getOrElse { failure ->
                entry.openingFacts.recordFailure(PersistenceOpeningFailureSite.SCOPE_LEAVE, failure)
                if (failure is InterruptedException) Thread.currentThread().interrupt()
                if (failure is Error) throw failure
                false
            }
            return ended
        } finally {
            if (!ended) entry.retirementRequested.set(true)
            check(physical.isOwnedWorkerThread())
            physical.ledger.lock.lock()
            try {
                check(physical.ledger.current(entry.record) === entry && entry.driverOpening === this)
                entry.scopeEnded = ended
                if (!ended) entry.unknown = true
            } finally {
                physical.ledger.lock.unlock()
                entry.openingFacts.scopeCallEnded.set(true)
            }
        }
    }

    override fun toString(): String = "PersistencePgDriverOpening(redacted)"

    /** Cold effective recipe. The same retained selection supplies descriptors and actual driver.connect properties. */
    internal class Configuration private constructor(private val endpoint: ResolvedPersistenceEndpoint, val policy: PersistenceDriverAttemptPolicy) {
        val loginPolicy: PersistenceLoginPolicy = endpoint.loginPolicy
        val driverUrl: String = endpoint.driverUrl

        fun publicDriverProperties(): Map<String, String> = effectiveDriverProperties(endpoint, policy).let { properties ->
            properties.stringPropertyNames().filterNot { it == "password" || it == "sslrootcert" }
                .associateWith { properties.getProperty(it) }
        }

        internal fun prepareRetained(retained: PersistenceRetainedPgDriver, timer: PersistenceDriverTimer?): PersistencePgDriverOpening {
            requireDisabledLoginThread()
            val driver = retained.forOpening()
            val cut = retained.cutAccess()
            val image = image(driver)
            if (policy.evidence === PersistenceDriverEvidencePolicy.TRACKED_CONJUNCTION) checkNotNull(timer)
            return PersistencePgDriverOpening(driver, endpoint, policy, image, cut, timer)
        }

        internal fun prepare(prepared: PreparedPersistenceDriver): PersistencePgDriverOpening {
            requireDisabledLoginThread()
            val driver = prepared.construct()
            val cut = PersistencePgOwnedCutAccess.prepare(prepared, driver.javaClass)
            return PersistencePgDriverOpening(driver, endpoint, policy, image(driver), cut)
        }

        private fun image(driver: Driver): PersistencePgDriverImage? =
            if (policy.recipe === PersistenceDriverExecutionRecipe.TRACKED_STANDARD) PersistencePgDriverImage.prepare(driver.javaClass) else null

        private fun requireDisabledLoginThread() {
            if (endpoint.driverProperties().getProperty("loginTimeout") != "0") rejectPersistenceBoundary(PersistenceBoundaryFailureCode.INVALID_LOGIN_POLICY)
        }

        override fun toString(): String = "PersistencePgDriverConfiguration(redacted)"

        companion object {
            internal fun resolve(
                endpoint: ResolvedPersistenceEndpoint,
                policy: PersistenceDriverAttemptPolicy,
                pathStyle: PersistencePathStyle,
            ): Configuration = Configuration(selectEndpoint(endpoint, policy, pathStyle), policy)
        }
    }

    companion object {
        /** Only the root-preowned guarded handle can supply the integrated Driver. Existing standalone preparation survives. */
        fun prepareRetained(
            retained: PersistenceRetainedPgDriver,
            endpoint: ResolvedPersistenceEndpoint,
            policy: PersistenceDriverAttemptPolicy,
            pathStyle: PersistencePathStyle,
            timer: PersistenceDriverTimer? = null,
        ): PersistencePgDriverOpening = persistenceBootstrapBoundary {
            PersistenceOpeningEvidence.prepareRuntime()
            Configuration.resolve(endpoint, policy, pathStyle).prepareRetained(retained, timer)
        }

        /** A bound role uses its retained cold selection, never a separately supplied endpoint or settings map. */
        internal fun prepareRetained(
            retained: PersistenceRetainedPgDriver,
            configuration: Configuration,
            timer: PersistenceDriverTimer? = null,
        ): PersistencePgDriverOpening = persistenceBootstrapBoundary {
            PersistenceOpeningEvidence.prepareRuntime()
            configuration.prepareRetained(retained, timer)
        }

        fun prepare(
            prepared: PreparedPersistenceDriver,
            endpoint: ResolvedPersistenceEndpoint,
            policy: PersistenceDriverAttemptPolicy,
            pathStyle: PersistencePathStyle,
        ): PersistencePgDriverOpening = persistenceBootstrapBoundary {
            PersistenceOpeningEvidence.prepareRuntime()
            Configuration.resolve(endpoint, policy, pathStyle).prepare(prepared)
        }

        private fun effectiveDriverProperties(endpoint: ResolvedPersistenceEndpoint, policy: PersistenceDriverAttemptPolicy): Properties =
            endpoint.driverProperties().apply {
                if (policy.recipe === PersistenceDriverExecutionRecipe.TRACKED_STANDARD) {
                    setProperty("socketFactory", TrackedPgSocketFactory::class.java.name)
                }
            }

        private fun selectEndpoint(
            endpoint: ResolvedPersistenceEndpoint,
            policy: PersistenceDriverAttemptPolicy,
            pathStyle: PersistencePathStyle,
        ): ResolvedPersistenceEndpoint {
            if (policy.recipe === PersistenceDriverExecutionRecipe.ORIGINAL_PROVIDER) return endpoint
            val assessment = if (policy === PersistenceDriverAttemptPolicy.TRACKED_CATALOG_CONJUNCTION) {
                PersistenceNativeSettings.deriveCatalogCoordinator(endpoint, pathStyle)
            } else if (policy.route === PersistenceDriverTransportRoute.APPROVED_DIRECT) {
                PersistenceNativeSettings.deriveDeletion(endpoint, pathStyle)
            } else {
                PersistenceNativeSettings.assessOrdinary(endpoint, pathStyle)
            }
            return when (assessment) {
                is PersistenceNativeSettingsResult.Supported -> assessment.endpoint
                is PersistenceNativeSettingsResult.Unsupported -> rejectPersistenceBoundary(PersistenceBoundaryFailureCode.JDBC_CONFIGURATION_FAILED)
            }
        }
    }
}
