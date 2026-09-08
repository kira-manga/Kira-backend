package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.reflect.Modifier

/** C5's exact current caller. Metadata and application overrides are never evaluated under F/G/T. */
internal class PersistenceOwnedFactoryCaller private constructor(private val caller: Thread, private val kind: Kind) {
    private var observedInterruption = false
    private var restorationNeeded = false

    fun isCurrent(): Boolean = Thread.currentThread() === caller

    /** The overriding case can block or throw. Its caller must already retain any admitted work. */
    fun sampleOutsideLocks(): PersistenceFactoryFailure? {
        if (!isCurrent()) return PersistenceFactoryFailure.COORDINATION_FAILED
        if (kind == Kind.OVERRIDING_PLATFORM && caller.isInterrupted) observedInterruption = true
        return sampleActualFlag()
    }

    /** Only the bound caller may use this primitive after the final F then G acquisition. */
    fun sampleActualFlag(): PersistenceFactoryFailure? {
        if (!isCurrent()) return PersistenceFactoryFailure.COORDINATION_FAILED
        val actual = if (kind == Kind.OVERRIDING_PLATFORM) {
            // Platform only. Virtual get-and-clear may acquire its interrupt monitor and is forbidden.
            Thread.interrupted().also { if (it) restorationNeeded = true }
        } else {
            // Metadata proves that this dispatch is the pinned direct field read, not an application override.
            caller.isInterrupted
        }
        if (actual) observedInterruption = true
        return if (observedInterruption) PersistenceFactoryFailure.INTERRUPTED else null
    }

    /** Invoke only after authoritative refusal/abandonment and after releasing every ownership lock. */
    fun restoreAfterFailure() {
        if (!isCurrent() || !restorationNeeded) return
        restorationNeeded = false
        // An application override may block, throw or ignore this. No restoration guarantee is inferred.
        caller.interrupt()
    }

    override fun toString(): String = "PersistenceOwnedFactoryCaller"

    private enum class Kind {
        DIRECT_PLATFORM,
        DIRECT_VIRTUAL,
        OVERRIDING_PLATFORM,
    }

    companion object {
        fun capture(): PersistenceOwnedFactoryCaller {
            val caller = Thread.currentThread()
            val method = caller.javaClass.getMethod("isInterrupted")
            val declaration = method.declaringClass
            val kind = if (caller.isVirtual) {
                val virtualClass = Class.forName("java.lang.VirtualThread", false, null)
                check(virtualClass.classLoader == null && Modifier.isFinal(virtualClass.modifiers))
                check(caller.javaClass === virtualClass && declaration === virtualClass)
                Kind.DIRECT_VIRTUAL
            } else if (declaration === Thread::class.java) {
                Kind.DIRECT_PLATFORM
            } else {
                Kind.OVERRIDING_PLATFORM
            }
            check(Modifier.isPublic(method.modifiers) && method.parameterCount == 0 && method.returnType === Boolean::class.javaPrimitiveType)
            return PersistenceOwnedFactoryCaller(caller, kind)
        }
    }
}
