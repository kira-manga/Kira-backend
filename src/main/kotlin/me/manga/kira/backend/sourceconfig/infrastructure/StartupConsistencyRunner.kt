package me.manga.kira.backend.sourceconfig.infrastructure

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Invokes the source-config startup consistency validators at boot (PLAN §5). Both run fail-fast: if
 * either throws, the [ApplicationRunner] contract fails `SpringApplication.run` and the process refuses
 * to start (readiness stays red) — inconsistency is never silently auto-repaired.
 *
 * The floor check runs first (its property invariant is cheapest and DB-independent), then the pointer
 * coherence check. The validators are separate beans so tests can exercise each in isolation against a
 * manipulated DB state; this runner is the single startup wiring point.
 */
@Component
class StartupConsistencyRunner(
    private val revisionFloorStartupValidator: RevisionFloorStartupValidator,
    private val publicationStateStartupValidator: PublicationStateStartupValidator,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments?) {
        revisionFloorStartupValidator.validate()
        publicationStateStartupValidator.validate()
    }
}
