package me.manga.kira.backend.support

import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.core.type.AnnotatedTypeMetadata

/** Explicit NONSHIPPING controller run only. Invalid present input is checked by the existing descriptor loader. */
internal class PgLifecycleLocalRunCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean = localRunSelected()
}

/** No automatic Docker-to-local fallback: absence selects the unchanged real-container test path. */
internal class PgLifecycleContainerRunCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean = !localRunSelected()
}

private fun localRunSelected(): Boolean = System.getenv("KIRA_PG_LIFECYCLE_LOCAL_RUN") != null
