package me.manga.kira.backend.config

import jakarta.validation.constraints.Email
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * `kira.admin.*` — admin-seed bootstrap credentials (PLAN §3 config/, §6), bound from
 * `KIRA_ADMIN_EMAIL` / `KIRA_ADMIN_PASSWORD` via relaxed binding. Phase 2 defines the typed
 * binding only.
 *
 * Fields are nullable here so a Phase-2 context loads without them. The **fail-fast when seeding
 * is enabled but the env is absent** is the Phase-3 `AdminSeeder`'s responsibility (PLAN §6) — not
 * a bean-validation concern — and the password is BCrypt-hashed at seed time and **never logged**.
 * No credential is ever defaulted here.
 */
@Validated
@ConfigurationProperties(prefix = "kira.admin")
data class KiraAdminSeedProperties(
    @field:Email
    val email: String? = null,
    val password: String? = null,
)
