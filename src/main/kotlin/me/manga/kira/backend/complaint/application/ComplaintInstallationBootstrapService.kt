package me.manga.kira.backend.complaint.application

import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrap
import me.manga.kira.backend.complaint.domain.ComplaintInstallationBootstrapReadPort
import me.manga.kira.backend.complaint.domain.ComplaintInstallationRequestContext

/** No ambient transaction or mode fallback. The actual port retains the original process/ingress owners. */
internal class ComplaintInstallationBootstrapService(private val reader: ComplaintInstallationBootstrapReadPort) {
    fun read(context: ComplaintInstallationRequestContext): ComplaintInstallationBootstrap = reader.read(context)

    override fun toString(): String = "ComplaintInstallationBootstrapService(explicit-TEST)"
}
