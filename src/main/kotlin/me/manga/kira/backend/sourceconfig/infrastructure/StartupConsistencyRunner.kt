package me.manga.kira.backend.sourceconfig.infrastructure

import me.manga.kira.backend.sourceconfig.application.ClientCatalogQueryService
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Invokes the source-config startup consistency validators at boot (PLAN §5). All run fail-fast: if
 * any throws, the [ApplicationRunner] contract fails `SpringApplication.run` and the process refuses
 * to start (readiness stays red) — inconsistency is never silently auto-repaired.
 *
 * The floor check runs first (its property invariant is cheapest and DB-independent), then the pointer
 * coherence check, then optional signed client-pair admission. The validators are separate beans so
 * tests can exercise each in isolation against a manipulated DB state; this is the startup wiring point.
 */
@Component
class StartupConsistencyRunner(
    private val revisionFloorStartupValidator: RevisionFloorStartupValidator,
    private val publicationStateStartupValidator: PublicationStateStartupValidator,
    private val clientCatalogQueryService: ClientCatalogQueryService,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        revisionFloorStartupValidator.validate()
        publicationStateStartupValidator.validate()
        clientCatalogQueryService.validateStartup()
    }
}
