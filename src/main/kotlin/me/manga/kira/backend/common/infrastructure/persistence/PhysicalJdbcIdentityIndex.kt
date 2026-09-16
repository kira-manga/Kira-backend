package me.manga.kira.backend.common.infrastructure.persistence

import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference

/** Caller-lineage-only alias cache. Neither its keys nor its values invoke delegate Object methods. */
internal class PhysicalJdbcIdentityIndex<V : Any> {
    private val queue = ReferenceQueue<Any>()
    private val values = HashMap<Key, WeakReference<V>>()

    operator fun get(value: Any): V? {
        drain()
        return values[Key(value, null)]?.get()
    }

    operator fun set(value: Any, guard: V) {
        drain()
        values[Key(value, queue)] = WeakReference(guard)
    }

    private fun drain() {
        while (true) {
            val key = queue.poll() as? Key ?: return
            values.remove(key)
        }
    }

    private class Key(value: Any, queue: ReferenceQueue<Any>?) : WeakReference<Any>(value, queue) {
        private val hash = System.identityHashCode(value)

        override fun hashCode(): Int = hash

        override fun equals(other: Any?): Boolean = this === other ||
            (other is Key && get()?.let { it === other.get() } == true)
    }
}
