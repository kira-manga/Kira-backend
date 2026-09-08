package me.manga.kira.backend.common.infrastructure.persistence

/** A value matcher can be tested with MODEL frames, but no supplied vector grants a live capability. */
internal class PersistencePgFrame(val type: Class<*>, val method: String, val descriptor: String) {
    fun matches(other: PersistencePgFrame): Boolean = type === other.type && method == other.method && descriptor == other.descriptor

    override fun toString(): String = "PersistencePgFrame(redacted)"
}

internal object PersistencePgDriverFrames {
    const val MAX_FRAMES = 16

    fun constructorAllowed(image: PersistencePgDriverImage): Boolean {
        // The bound precedes even descriptor extraction. No filtering, skipping or ancestor search.
        val frames = image.walker.walk { stream -> stream.limit(MAX_FRAMES.toLong()).map(::value).toList() }
        return matches(frames, image.patterns.constructor)
    }

    fun classifyAllocation(image: PersistencePgDriverImage): PersistenceTransportRole? {
        val frames = image.walker.walk { stream -> stream.limit(MAX_FRAMES.toLong()).map(::value).toList() }
        return classify(frames, image.patterns)
    }

    fun auxiliaryCloseAllowed(image: PersistencePgDriverImage): Boolean {
        val frames = image.walker.walk { stream -> stream.limit(MAX_FRAMES.toLong()).map(::value).toList() }
        return matches(frames, image.patterns.auxiliaryClose)
    }

    fun classify(frames: List<PersistencePgFrame>, patterns: PersistencePgDriverPatterns): PersistenceTransportRole? = when {
        matches(frames, patterns.auxiliary) -> PersistenceTransportRole.AUX_CANCEL
        patterns.primary.any { matches(frames, it) } -> PersistenceTransportRole.PRIMARY
        else -> null
    }

    fun matches(frames: List<PersistencePgFrame>, expected: List<PersistencePgFrame>): Boolean =
        expected.isNotEmpty() && expected.size <= MAX_FRAMES && frames.size in expected.size..MAX_FRAMES &&
            expected.indices.all { index -> expected[index].matches(frames[index]) }

    private fun value(frame: StackWalker.StackFrame): PersistencePgFrame = PersistencePgFrame(frame.declaringClass, frame.methodName, frame.descriptor)
}
