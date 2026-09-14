package me.manga.kira.backend.tutorial.application

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "kira.tutorial", name = ["media-reconciliation-enabled"], havingValue = "true")
class TutorialMediaReconciliationScheduler(private val reconciliation: TutorialMediaReconciliationService) {
    @Scheduled(
        fixedDelayString = "\${kira.tutorial.media-reconciliation-interval-millis:300000}",
        initialDelayString = "\${kira.tutorial.media-reconciliation-interval-millis:300000}",
    )
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    fun reconcile() {
        try {
            val report = reconciliation.reconcile()
            if (!report.clean) {
                log.warn(
                    "Tutorial media reconciliation needs attention (rows={}, publishedVerified={}, complete={}, findings={}, quarantined={})",
                    report.rowsChecked,
                    report.publishedComplete,
                    report.complete,
                    report.findings.size,
                    report.quarantinedFiles,
                )
            }
        } catch (_: Exception) {
            // The proxied transaction has finished or failed; never retry this invocation with
            // a fresh connection/candidate set. The next separately scheduled scan starts over.
            log.warn("Tutorial media reconciliation failed; no clean-store result is available")
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(TutorialMediaReconciliationScheduler::class.java)
    }
}
