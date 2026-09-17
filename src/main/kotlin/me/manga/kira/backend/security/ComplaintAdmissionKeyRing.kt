package me.manga.kira.backend.security

import java.security.MessageDigest
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Dedicated explicit HMAC key, not an installation JWT key or an entropy certificate. */
internal class ComplaintAdmissionKey(val id: String, bytes: ByteArray) {
    private val material: ByteArray
    private val separationTag: ByteArray

    init {
        require(id.length in 1..64 && KEY_ID.matches(id) && bytes.size in 32..128) { INVALID_ADMISSION_CONFIGURATION }
        material = bytes.copyOf()
        separationTag = admissionSeparationTag(material)
    }

    internal fun snapshot(): ComplaintAdmissionKey = ComplaintAdmissionKey(id, material)

    internal fun sameSecret(other: ComplaintAdmissionKey): Boolean = matchesTag(other.separationTag)

    internal fun matchesTag(tag: ByteArray): Boolean = MessageDigest.isEqual(separationTag, tag)

    internal fun digest(frame: ByteArray): String = HexFormat.of().formatHex(admissionMac(material, frame))

    internal fun destroy() {
        material.fill(0)
        separationTag.fill(0)
    }

    override fun toString(): String = "ComplaintAdmissionKey(redacted)"

    private companion object {
        val KEY_ID = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/** Caller supplies every actual retained family. Only bounded comparison tags are retained here. */
internal class ComplaintAdmissionForbiddenFamily private constructor(val name: String, private val tags: Array<ByteArray>) {
    constructor(name: String, keys: List<ByteArray>) : this(name, admissionForbiddenTags(keys))

    init {
        require(name.length in 1..64 && FAMILY_NAME.matches(name)) { INVALID_ADMISSION_CONFIGURATION }
    }

    internal fun snapshot(): ComplaintAdmissionForbiddenFamily = ComplaintAdmissionForbiddenFamily(name, tags.map { it.copyOf() }.toTypedArray())

    internal fun forbids(key: ComplaintAdmissionKey): Boolean = tags.any { key.matchesTag(it) }

    override fun toString(): String = "ComplaintAdmissionForbiddenFamily(redacted)"

    private companion object {
        val FAMILY_NAME = Regex("[A-Za-z0-9._-]{1,64}")
    }
}

/** No binding, keys from another profile, missing-family guess or runtime history supplied by a caller. */
internal class ComplaintAdmissionKeyConfiguration private constructor(
    current: ComplaintAdmissionKey,
    previous: ComplaintAdmissionKey?,
    forbiddenFamilies: List<ComplaintAdmissionForbiddenFamily>,
    internal val fixedBinding: Boolean,
) {
    /** Existing dynamic rotation seam; no immutable acquisition binding is asserted by this constructor. */
    constructor(
        current: ComplaintAdmissionKey,
        previous: ComplaintAdmissionKey?,
        forbiddenFamilies: List<ComplaintAdmissionForbiddenFamily>,
    ) : this(current, previous, forbiddenFamilies, false)

    private val current = current.snapshot()
    private val previous = previous?.snapshot()
    private val forbidden = boundedAdmissionCopy(forbiddenFamilies) { it.snapshot() }
    internal val currentKeyId: String get() = current.id
    internal val previousKeyId: String? get() = previous?.id

    init {
        require(forbidden.map { it.name }.distinct().size == forbidden.size) { INVALID_ADMISSION_CONFIGURATION }
        validate(this.current, emptyList())
        this.previous?.let { validate(it, listOf(this.current)) }
    }

    internal fun copiedKeys(): List<ComplaintAdmissionKey> = listOfNotNull(current.snapshot(), previous?.snapshot())

    internal fun copiedForbiddenFamilies(): List<ComplaintAdmissionForbiddenFamily> = forbidden.map { it.snapshot() }

    internal fun validate(candidate: ComplaintAdmissionKey, retained: List<ComplaintAdmissionKey>) {
        require(forbidden.none { it.forbids(candidate) }) { INVALID_ADMISSION_CONFIGURATION }
        require(retained.none { it.id == candidate.id || it.sameSecret(candidate) }) { INVALID_ADMISSION_CONFIGURATION }
    }

    override fun toString(): String = "ComplaintAdmissionKeyConfiguration(redacted)"

    companion object {
        /** A retained configuration may not drift behind its version descriptors, including by retirement. */
        internal fun fixed(
            current: ComplaintAdmissionKey,
            previous: ComplaintAdmissionKey?,
            forbiddenFamilies: List<ComplaintAdmissionForbiddenFamily>,
        ): ComplaintAdmissionKeyConfiguration =
            ComplaintAdmissionKeyConfiguration(current, previous, forbiddenFamilies, true)
    }
}

/** Owner serializes all calls and drains ingress before changing this ring. No durable history claim. */
internal class ComplaintAdmissionKeyRing(configuration: ComplaintAdmissionKeyConfiguration) {
    private val forbidden = configuration.copiedForbiddenFamilies()
    private val fixedBinding = configuration.fixedBinding
    private var current: ComplaintAdmissionKey
    private var previous: ComplaintAdmissionKey?
    private var overlapStartedAt: Long?
    var generation: Long = 1
        private set

    init {
        val initial = configuration.copiedKeys()
        current = initial.first()
        previous = initial.getOrNull(1)
        overlapStartedAt = if (previous == null) null else 0L
    }

    internal fun keys(): List<ComplaintAdmissionKey> = listOfNotNull(current, previous)

    internal fun rotate(candidate: ComplaintAdmissionKey, now: Long) {
        requireDynamicRotation()
        if (previous != null) refuseComplaintAdmission(ComplaintAdmissionFailure.ROTATION_REFUSED)
        val copied = candidate.snapshot()
        require(forbidden.none { it.forbids(copied) }) { INVALID_ADMISSION_CONFIGURATION }
        require(copied.id != current.id && !copied.sameSecret(current)) { INVALID_ADMISSION_CONFIGURATION }
        val next = Math.addExact(generation, 1)
        previous = current
        current = copied
        overlapStartedAt = now
        generation = next
    }

    internal fun retirePrevious(now: Long): String {
        requireDynamicRotation()
        val old = previous ?: refuseComplaintAdmission(ComplaintAdmissionFailure.ROTATION_REFUSED)
        val started = overlapStartedAt ?: refuseComplaintAdmission(ComplaintAdmissionFailure.ROTATION_REFUSED)
        if (now - started < ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS) refuseComplaintAdmission(ComplaintAdmissionFailure.ROTATION_REFUSED)
        val next = Math.addExact(generation, 1)
        previous = null
        overlapStartedAt = null
        generation = next
        old.destroy()
        return old.id
    }

    private fun requireDynamicRotation() {
        if (fixedBinding) refuseComplaintAdmission(ComplaintAdmissionFailure.ROTATION_REFUSED)
    }

    override fun toString(): String = "ComplaintAdmissionKeyRing(redacted)"
}

private fun admissionSeparationTag(bytes: ByteArray): ByteArray = admissionMac(bytes, "kira-complaint-admission-key-separation-v1".toByteArray())

private fun admissionForbiddenTags(keys: List<ByteArray>): Array<ByteArray> = boundedAdmissionCopy(keys) { bytes ->
    require(bytes.size in 32..128) { INVALID_ADMISSION_CONFIGURATION }
    admissionSeparationTag(bytes)
}.toTypedArray()

private fun admissionMac(bytes: ByteArray, message: ByteArray): ByteArray = Mac.getInstance("HmacSHA256").run {
    init(SecretKeySpec(bytes, "HmacSHA256"))
    doFinal(message)
}

private fun <T, R> boundedAdmissionCopy(values: List<T>, copy: (T) -> R): List<R> {
    val count = values.size
    require(count in 1..8) { INVALID_ADMISSION_CONFIGURATION }
    val iterator = values.iterator()
    val result = ArrayList<R>(count)
    repeat(count) {
        require(iterator.hasNext()) { INVALID_ADMISSION_CONFIGURATION }
        result.add(copy(iterator.next()))
    }
    require(!iterator.hasNext()) { INVALID_ADMISSION_CONFIGURATION }
    return result
}
