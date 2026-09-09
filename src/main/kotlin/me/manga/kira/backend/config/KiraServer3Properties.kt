package me.manga.kira.backend.config

import org.springframework.boot.context.properties.ConfigurationProperties

/** Provisioned peers for server3 only. No ingress prerequisite is imposed on generic deployments. */
@ConfigurationProperties("kira.server3")
data class KiraServer3Properties(val hostPeer: String = "", val adminAddress: String = "")
