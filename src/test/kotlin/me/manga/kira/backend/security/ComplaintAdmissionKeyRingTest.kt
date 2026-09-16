package me.manga.kira.backend.security

import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.ScopedInstallationId
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class ComplaintAdmissionKeyRingTest {
    @Test
    fun `explicit memory configuration has finite ceilings and no shared or implicit mode`() {
        admissionTestPolicy()
        listOf("", "redis", "shared", "MEMORY", " memory").forEach { mode ->
            invalid { ComplaintAdmissionPolicy(mode, 1, 64, 2048, 120, 4096, 131072, 128, ComplaintEnrollmentAdmissionPolicy.Disabled) }
        }
        listOf(0, 2, Int.MAX_VALUE).forEach { count ->
            invalid { ComplaintAdmissionPolicy("memory", count, 64, 2048, 120, 4096, 131072, 128, ComplaintEnrollmentAdmissionPolicy.Disabled) }
        }
        listOf(0, 65).forEach { invalid { admissionTestPolicy(concurrent = it) } }
        listOf(0, 2049).forEach { invalid { admissionTestPolicy(ingressBuckets = it) } }
        listOf(0, 121).forEach { invalid { admissionTestPolicy(ingressRate = it) } }
        listOf(0, 4097).forEach { invalid { admissionTestPolicy(semanticBuckets = it) } }
        listOf(0, 131073).forEach { invalid { admissionTestPolicy(semanticEvents = it) } }
        listOf(0, 129).forEach { invalid { admissionTestPolicy(prune = it) } }
    }

    @Test
    fun `dedicated key sizes IDs families and effective duplicates fail closed`() {
        ComplaintAdmissionKey("a", admissionTestBytes(1, 32))
        ComplaintAdmissionKey("A._-".repeat(16), admissionTestBytes(2, 128))
        listOf(0, 31, 129).forEach { invalid { ComplaintAdmissionKey("current", admissionTestBytes(1, it)) } }
        listOf("", "x".repeat(65), "bad id", "clé", "id\n", "../id").forEach { invalid { ComplaintAdmissionKey(it, admissionTestBytes(1)) } }
        invalid { ComplaintAdmissionKeyConfiguration(admissionTestKey(1), null, emptyList()) }
        invalid { ComplaintAdmissionForbiddenFamily("family", emptyList()) }
        invalid { ComplaintAdmissionForbiddenFamily("family", List(9) { admissionTestBytes(it + 90) }) }
        invalid { ComplaintAdmissionForbiddenFamily("clé", listOf(admissionTestBytes(91))) }
        val family = admissionTestForbidden()
        invalid { ComplaintAdmissionKeyConfiguration(admissionTestKey(1), null, List(9) { family }) }
        invalid { ComplaintAdmissionKeyConfiguration(admissionTestKey(1), null, listOf(family, family)) }
        invalid { ComplaintAdmissionKeyConfiguration(admissionTestKey(1), ComplaintAdmissionKey("key-1", admissionTestBytes(2)), listOf(family)) }
        val short = admissionTestBytes(3)
        val long = admissionTestBytes(4, 128)
        listOf(short to short, short to short.copyOf(64), long to MessageDigest.getInstance("SHA-256").digest(long)).forEach { (one, two) ->
            invalid {
                ComplaintAdmissionKeyConfiguration(ComplaintAdmissionKey("one", one), ComplaintAdmissionKey("two", two), listOf(family))
            }
        }
    }

    @Test
    fun `actual retained JWT families and input snapshots remain separated through rotation`() {
        val userBytes = admissionTestBytes(91)
        val user = InstallationJwtForbiddenFamily("user-issuer", "user-audience", listOf(InstallationJwtKeyMaterial("user", userBytes)))
        val installation = InstallationJwtKeyRing("installation", listOf(InstallationJwtKeyMaterial("installation", admissionTestBytes(92))), user)
        val families = mutableListOf(
            ComplaintAdmissionForbiddenFamily("user-admin", user.keys().map { it.secretKey().encoded }),
            ComplaintAdmissionForbiddenFamily("installation", installation.verificationKeyIds().map { checkNotNull(installation.key(it)).encoded }),
        )
        val input = admissionTestKey(1)
        val configuration = ComplaintAdmissionKeyConfiguration(input, null, families)
        val ring = ComplaintAdmissionKeyRing(configuration)
        input.destroy()
        families.clear()
        userBytes.fill(0)
        val frame = "test-frame".toByteArray()
        assertEquals(admissionTestKey(1).digest(frame), ring.keys().single().digest(frame))
        listOf(admissionTestBytes(91), admissionTestBytes(91).copyOf(64), admissionTestBytes(92)).forEach { secret ->
            invalid { ring.rotate(ComplaintAdmissionKey("forbidden", secret), 0) }
        }
        val zeroForbidden = ComplaintAdmissionForbiddenFamily("zero", listOf(ByteArray(32)))
        val zeroRing = ComplaintAdmissionKeyRing(ComplaintAdmissionKeyConfiguration(admissionTestKey(1), null, listOf(zeroForbidden)))
        val erased = admissionTestKey(2).also { it.destroy() }
        invalid { zeroRing.rotate(erased, 0) }
        assertEquals("ComplaintAdmissionKeyConfiguration(redacted)", configuration.toString())
        assertEquals("ComplaintAdmissionKeyRing(redacted)", ring.toString())
        assertEquals("ComplaintAdmissionKey(redacted)", ring.keys().single().toString())
    }

    @Test
    fun `previous key retention is runtime monotonic and startup overlap cannot assert old age`() {
        val ring = ComplaintAdmissionKeyRing(admissionTestKeys())
        ring.rotate(admissionTestKey(2), 1)
        assertEquals(listOf("key-2", "key-1"), ring.keys().map { it.id })
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { ring.rotate(admissionTestKey(3), 2) }
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { ring.retirePrevious(ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS) }
        assertEquals("key-1", ring.retirePrevious(ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS + 1))
        assertEquals(listOf("key-2"), ring.keys().map { it.id })
        val restarted = ComplaintAdmissionKeyRing(admissionTestKeys(previous = admissionTestKey(2)))
        admissionTestRefused(ComplaintAdmissionFailure.ROTATION_REFUSED) { restarted.retirePrevious(0) }
        restarted.retirePrevious(ComplaintAdmissionPolicy.PREVIOUS_RETENTION_NANOS)
    }

    @Test
    fun `canonical ASCII IP framing has a fixed binary vector and separate actor scope operation generations`() {
        val rawKey = admissionTestBytes(1)
        val keys = listOf(ComplaintAdmissionKey("key-1", rawKey))
        val ip = ComplaintAdmissionClientIp.canonicalBytes("192.0.2.1")
        assertArrayEquals("192.0.2.1".toByteArray(Charsets.US_ASCII), ip)
        val hex = "0000001b6b6972612d636f6d706c61696e742d61646d697373696f6e2d7631" +
            "0000000249500000000753455353494f4e000000093139322e302e322e31"
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(rawKey, "HmacSHA256")) }
        val expected = ComplaintAdmissionBucketKey("key-1", HexFormat.of().formatHex(mac.doFinal(HexFormat.of().parseHex(hex))))
        val sessionIp = ComplaintAdmissionPseudonyms.sessionIp(keys, ip)
        assertEquals(listOf(expected), sessionIp)
        assertNotEquals(sessionIp, ComplaintAdmissionPseudonyms.ingressIp(keys, ip))
        assertNotEquals(sessionIp, ComplaintAdmissionPseudonyms.sessionIp(listOf(admissionTestKey(2)), ip))
        val first = admissionTestActor(1)
        val testScope = ComplaintDataScope.of(UUID.fromString("00000000-0000-4000-8000-000000000002"))
        val scoped = ScopedInstallationId(first.id, testScope)
        val actor = ComplaintAdmissionPseudonyms.sessionActor(keys, first)
        assertNotEquals(actor, sessionIp)
        assertNotEquals(actor, ComplaintAdmissionPseudonyms.sessionActor(keys, admissionTestActor(2)))
        assertNotEquals(actor, ComplaintAdmissionPseudonyms.sessionActor(keys, scoped))
        assertEquals("ComplaintAdmissionBucketKey(redacted)", expected.toString())
    }

    @Test
    fun `admission uses accepted numeric canonicalization without a second parser or raw fallback`() {
        assertArrayEquals(ComplaintAdmissionClientIp.canonicalBytes("192.0.2.1"), ComplaintAdmissionClientIp.canonicalBytes("::ffff:c000:201"))
        assertArrayEquals(ComplaintAdmissionClientIp.canonicalBytes("2001:db8::1"), ComplaintAdmissionClientIp.canonicalBytes("2001:0DB8:0:0:0:0:0:1"))
        listOf("unknown", "localhost", "127.01.0.1", "fe80::1%eth0", "x".repeat(46)).forEach { address ->
            admissionTestRefused(ComplaintAdmissionFailure.UNAVAILABLE) { ComplaintAdmissionClientIp.canonicalBytes(address) }
        }
    }

    private fun invalid(operation: () -> Unit) {
        val failure = assertThrows(IllegalArgumentException::class.java) { operation() }
        assertEquals(INVALID_ADMISSION_CONFIGURATION, failure.message)
        assertNull(failure.cause)
    }
}

internal fun admissionTestBytes(seed: Int, count: Int = 32): ByteArray = ByteArray(count) { (seed + it * 7).toByte() }

internal fun admissionTestKey(seed: Int): ComplaintAdmissionKey = ComplaintAdmissionKey("key-$seed", admissionTestBytes(seed))

internal fun admissionTestForbidden(): ComplaintAdmissionForbiddenFamily = ComplaintAdmissionForbiddenFamily("user-family", listOf(admissionTestBytes(91)))

internal fun admissionTestKeys(previous: ComplaintAdmissionKey? = null): ComplaintAdmissionKeyConfiguration =
    ComplaintAdmissionKeyConfiguration(admissionTestKey(1), previous, listOf(admissionTestForbidden()))

internal fun admissionTestPolicy(
    concurrent: Int = 64,
    ingressBuckets: Int = 2048,
    ingressRate: Int = 120,
    semanticBuckets: Int = 4096,
    semanticEvents: Int = 131072,
    prune: Int = 128,
    enrollment: ComplaintEnrollmentAdmissionPolicy = ComplaintEnrollmentAdmissionPolicy.Disabled,
): ComplaintAdmissionPolicy = ComplaintAdmissionPolicy("memory", 1, concurrent, ingressBuckets, ingressRate, semanticBuckets, semanticEvents, prune, enrollment)

internal fun admissionTestActor(number: Int): ScopedInstallationId =
    ScopedInstallationId(UUID.fromString("00000000-0000-4000-8000-${number.toString().padStart(12, '0')}"), ComplaintDataScope.LIVE)

internal fun admissionTestRefused(code: ComplaintAdmissionFailure, operation: () -> Unit): ComplaintAdmissionRejected {
    val failure = assertThrows(ComplaintAdmissionRejected::class.java) { operation() }
    assertEquals(code, failure.code)
    assertEquals(if (code == ComplaintAdmissionFailure.RATE_LIMITED) 429 else 503, failure.status)
    assertEquals("Complaint admission refused", failure.message)
    assertNull(failure.cause)
    return failure
}

internal class MutableAdmissionTestClock(var value: Long = 0) : ComplaintAdmissionNanoClock {
    override fun now(): Long = value
}
