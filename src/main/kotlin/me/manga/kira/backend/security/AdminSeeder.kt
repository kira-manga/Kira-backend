package me.manga.kira.backend.security

import me.manga.kira.backend.config.KiraAdminSeedProperties
import me.manga.kira.backend.user.application.UserService
import me.manga.kira.backend.user.domain.Role
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Seeds the initial ADMIN at startup (PLAN §6). When seeding is enabled:
 *  - **Missing `KIRA_ADMIN_EMAIL` / `KIRA_ADMIN_PASSWORD` → fail startup** with a clear message
 *    (in every profile where seeding is enabled; local dev supplies them via the gitignored `.env`).
 *  - If any ADMIN already exists → no-op (idempotent; an existing admin's password is **never** reset).
 *  - Otherwise create one ADMIN from the env credentials.
 *
 * The password is BCrypt-hashed by [UserService] and is **never written to any log at any level**
 * (PLAN §6). The previous "generate a random password and log it at WARN" idea is rejected.
 */
@Component
class AdminSeeder(private val properties: KiraAdminSeedProperties, private val userService: UserService) : ApplicationRunner {

    override fun run(args: ApplicationArguments?) {
        if (!properties.seedEnabled) {
            log.info("Admin seeding disabled (kira.admin.seed-enabled=false).")
            return
        }
        if (userService.adminExists()) {
            log.info("Admin seeding: an ADMIN already exists — nothing to do.")
            return
        }
        val email = properties.email?.trim()
        val password = properties.password
        if (email.isNullOrBlank() || password.isNullOrBlank()) {
            error(
                "Admin seeding is enabled but KIRA_ADMIN_EMAIL / KIRA_ADMIN_PASSWORD are not set. " +
                    "Set both (local dev: the gitignored .env), or disable seeding with " +
                    "kira.admin.seed-enabled=false. The password is never logged.",
            )
        }
        val created = userService.createUser(email, password, Role.ADMIN)
        log.info("Admin seeding: created initial ADMIN id={} email={}", created.id, created.email)
    }

    private companion object {
        val log = LoggerFactory.getLogger(AdminSeeder::class.java)
    }
}
