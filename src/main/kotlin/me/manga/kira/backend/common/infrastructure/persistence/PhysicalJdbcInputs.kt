package me.manga.kira.backend.common.infrastructure.persistence

import java.util.IdentityHashMap

/**
 * One direct dispatch's known-input carrier and pins, not a model of native C, Q or A.
 * Only the driver's actual representation/append/rewrite/drain hooks decide effective
 * retention. No setter-name, overload, return/throw or wrapper-entry snapshot does so here.
 * Argument positions identify the containing public argument, never ownership by themselves.
 */
internal class PhysicalJdbcInputs private constructor(
    val values: Array<Any?>,
    val lives: Array<PersistencePgOwnedCutAccess.Life>,
    val positions: IntArray,
    @Suppress("unused") private val pins: Array<PhysicalJdbcNode>,
) {
    override fun toString(): String = "PhysicalJdbcInputs(redacted)"

    companion object {
        fun prepare(graph: PhysicalJdbcDescendants, identity: PersistenceJdbcGuardIdentity, arguments: Array<Any?>, adapted: Array<Any?>): PhysicalJdbcInputs {
            check(arguments.size == adapted.size)
            val values = ArrayList<Any?>()
            val lives = ArrayList<PersistencePgOwnedCutAccess.Life>()
            val positions = ArrayList<Int>()
            val pins = IdentityHashMap<PhysicalJdbcNode, Boolean>()

            fun include(value: Any, guard: PhysicalJdbcNode, position: Int) {
                guard.requireInput(graph, identity)
                pins[guard] = true
                val life = guard.driverLife
                if (life == null) {
                    graph.context.ordinaryCompatibilityOnly() // Explicit generic/model graph, no native receipt.
                } else {
                    values.add(value)
                    lives.add(life)
                    positions.add(position)
                }
            }

            for (position in arguments.indices) {
                val seen = IdentityHashMap<Any, Boolean>()
                fun collect(value: Any?) {
                    if (value == null || seen.put(value, true) != null) return
                    val guard = PhysicalJdbcDescendants.knownGuard(value)
                    if (guard != null) {
                        include(value, guard, position)
                    } else {
                        when (value) {
                            is Array<*> -> value.forEach(::collect)

                            is Map<*, *> -> value.forEach { (key, item) ->
                                collect(key)
                                collect(item)
                            }

                            is Collection<*> -> value.forEach(::collect)
                        }
                    }
                }
                collect(arguments[position])
                // The sole raw adaptation remains private to the already-validated argument vector.
                // Both exact identities are carried; the driver must still prove actual lineage.
                val source = PhysicalJdbcDescendants.knownGuard(arguments[position])
                if (source != null && adapted[position] !== arguments[position]) {
                    adapted[position]?.let { include(it, source, position) }
                } else {
                    collect(adapted[position])
                }
            }
            return PhysicalJdbcInputs(values.toTypedArray(), lives.toTypedArray(), positions.toIntArray(), pins.keys.toTypedArray())
        }
    }
}
