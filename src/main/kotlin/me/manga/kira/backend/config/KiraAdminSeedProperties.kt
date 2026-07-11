package me.manga.kira.backend.config

import jakarta.validation.constraints.Email
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * `kira.admin.*` — admin-seed bootstrap credentials (PLAN §3 config/, §6), bound from
 * `KIRA_ADMIN_EMAIL` / `KIRA_ADMIN_PASSWORD` via relaxed binding.
 *
 * [seedEnabled] gates whether [me.manga.kira.backend.security.AdminSeeder] runs. It defaults to
 * `true` (production and dev seed); integration tests set it `false` so the context boots without
 * seed credentials (the seeder fail-fasts when enabled but the env is absent — PLAN §6). The
 * dedicated seeder test flips it back on and supplies test credentials.
 *
 * [email]/[password] are nullable so a context with seeding disabled loads without them. The
 * **fail-fast when seeding is enabled but the env is absent** is the `AdminSeeder`'s responsibility
 * (PLAN §6) — not a bean-validation concern — and the password is BCrypt-hashed at seed time and
 * **never logged**. No credential is ever defaulted here.
 */
@Validated
@ConfigurationProperties(prefix = "kira.admin")
data class KiraAdminSeedProperties(
    val seedEnabled: Boolean = true,
    @field:Email
    val email: String? = null,
    val password: String? = null,
)
