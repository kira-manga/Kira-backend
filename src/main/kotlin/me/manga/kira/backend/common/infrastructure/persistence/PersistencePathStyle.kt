package me.manga.kira.backend.common.infrastructure.persistence

import java.util.Locale

/** Explicit lexical policy only: no OS lookup, filesystem provider, path resolution or locality proof. */
internal enum class PersistencePathStyle {
    POSIX,
    LOCAL_WINDOWS,
    ;

    fun accepts(reference: String): Boolean = '\u0000' !in reference && when (this) {
        POSIX -> reference.startsWith("/")
        LOCAL_WINDOWS -> localWindowsAbsolute(reference)
    }

    private fun localWindowsAbsolute(reference: String): Boolean {
        if (reference.length < 4 || (reference[0] !in 'A'..'Z' && reference[0] !in 'a'..'z')) return false
        if (reference[1] != ':' || (reference[2] != '/' && reference[2] != '\\')) return false
        return reference.substring(3).split('/', '\\').all(::ordinaryWindowsComponent)
    }

    private fun ordinaryWindowsComponent(component: String): Boolean {
        if (component.isEmpty() || component.endsWith(' ') || component.endsWith('.')) return false
        if (component.any { it < ' ' || it in "<>:\"|?*" }) return false
        return component.substringBefore('.').trimEnd(' ').uppercase(Locale.ROOT) !in RESERVED_WINDOWS_NAMES
    }

    private companion object {
        val RESERVED_WINDOWS_NAMES = buildSet {
            addAll(listOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$"))
            for (prefix in listOf("COM", "LPT")) {
                for (digit in ('1'..'9') + listOf('¹', '²', '³')) add("$prefix$digit")
            }
        }
    }
}
