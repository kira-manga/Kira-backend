package me.manga.kira.backend.complaint.infrastructure

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.core.StreamReadConstraints
import com.fasterxml.jackson.core.StreamReadFeature
import me.manga.kira.backend.common.infrastructure.persistence.PersistencePhaseException
import me.manga.kira.backend.common.infrastructure.persistence.requireConnectionFree
import me.manga.kira.backend.complaint.domain.ComplaintAdminDetailQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadAuthentication
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadFailure
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPort
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadPosition
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadQuery
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadRequestContext
import me.manga.kira.backend.complaint.domain.ComplaintAdminReadResult
import me.manga.kira.backend.complaint.domain.ComplaintAdminSearchQuery
import me.manga.kira.backend.complaint.domain.ComplaintDataScope
import me.manga.kira.backend.complaint.domain.rejectAdminRead
import me.manga.kira.backend.complaint.infrastructure.transaction.ComplaintAdminReadPhaseExecutor
import me.manga.kira.backend.security.ComplaintAdminCursorCodec
import me.manga.kira.backend.security.ComplaintIngressAdmission
import me.manga.kira.backend.security.ComplaintIngressContext
import me.manga.kira.backend.security.JwtService
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtException
import java.io.IOException
import java.time.DateTimeException
import java.time.Duration
import java.util.Base64
import java.util.UUID

/** Real normal-user crypto/current-ADMIN reads. No Spring registration or TEST projection authority. */
internal class ComplaintAdminReadAdapter(
    private val testScope: ComplaintDataScope,
    @Qualifier("jwtDecoder") private val userDecoder: JwtDecoder,
    private val cursors: ComplaintAdminCursorCodec,
    private val phases: ComplaintAdminReadPhaseExecutor,
    private val admission: ComplaintIngressAdmission,
    private val userClockSkew: Duration,
) : ComplaintAdminReadPort {
    init {
        require(testScope.testOnly && !userClockSkew.isNegative && userClockSkew <= Duration.ofSeconds(60)) { "Invalid TEST Admin read composition." }
    }

    @Suppress("SwallowedException")
    override fun authenticate(context: ComplaintAdminReadRequestContext, bearer: String, query: ComplaintAdminReadQuery): ComplaintAdminReadAuthentication {
        requireConnectionFree()
        val ingress = context as? ComplaintIngressContext ?: rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        when (query) {
            is ComplaintAdminSearchQuery -> admission.startAdminSearch(ingress)
            is ComplaintAdminDetailQuery -> admission.startAdminDetail(ingress)
        }
        val identity = identity(bearer)
        // This proves only a signed token identity/filter binding, never the current DB ADMIN role.
        val position = (query as? ComplaintAdminSearchQuery)?.let { search -> search.cursor?.let { cursors.decode(it, identity.actor, search) } }
        val current = try {
            phases.authenticate(identity)
        } catch (failure: PersistencePhaseException) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        }
        requireAllowed(current)
        if (query.scope != testScope) rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND)
        identity.requireCurrent()
        return Authenticated(this, Thread.currentThread(), ingress, identity, query, position)
    }

    @Suppress("SwallowedException")
    override fun read(context: ComplaintAdminReadRequestContext, authentication: ComplaintAdminReadAuthentication): ComplaintAdminReadResult {
        requireConnectionFree()
        val selected = authentication as? Authenticated ?: rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
        val ingress = context as? ComplaintIngressContext ?: rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        if (selected.owner !== this || selected.caller !== Thread.currentThread() || selected.context !== ingress || selected.consumed) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
        }
        selected.identity.requireCurrent()
        selected.consumed = true
        // Both operations are connection-free and follow actual original preflight release.
        admission.chargeAdminRead(ingress, selected.identity.actor, testScope, selected.admissionIdentity)
        admission.consumeAdminRead(ingress, selected.admissionIdentity)
        val rows = try {
            when (val query = selected.query) {
                is ComplaintAdminSearchQuery -> phases.search(selected.identity, query, selected.position)
                is ComplaintAdminDetailQuery -> phases.detail(selected.identity, query.id)
            }
        } catch (failure: PersistencePhaseException) {
            rejectAdminRead(ComplaintAdminReadFailure.UNAVAILABLE)
        }
        requireAllowed(rows)
        return when (val query = selected.query) {
            is ComplaintAdminDetailQuery -> ComplaintAdminReadResult.Detail(rows.items.singleOrNull() ?: rejectAdminRead(ComplaintAdminReadFailure.NOT_FOUND))
            is ComplaintAdminSearchQuery -> {
                val items = rows.items.take(query.limit)
                val next = if (rows.items.size > query.limit) {
                    val last = items.last()
                    cursors.encode(selected.identity.actor, query, ComplaintAdminReadPosition(last.updatedAt, last.id))
                } else {
                    null
                }
                ComplaintAdminReadResult.Page(items, next)
            }
        }
    }

    private fun requireAllowed(rows: ComplaintAdminReadRows) {
        if (!rows.contractValid) rejectAdminRead(ComplaintAdminReadFailure.INTERNAL)
        rows.verdict.failure?.let(::rejectAdminRead)
    }

    @Suppress("SwallowedException")
    private fun identity(bearer: String): ComplaintAdminReadIdentity = try {
        boundedToken(bearer)
        val jwt = userDecoder.decode(bearer)
        val subject = jwt.claims["sub"] as? String ?: unauthorized()
        if (!UUID_TEXT.matches(subject)) unauthorized()
        val actor = UUID.fromString(subject)
        val version = jwt.claims[JwtService.CLAIM_CREDENTIAL_VERSION] as? String ?: unauthorized()
        val generation = version.toLongOrNull() ?: unauthorized()
        if (generation < 0 || version != generation.toString()) unauthorized()
        // Composition supplies the same skew used by the qualified normal decoder; no token role grants anything.
        val until = (jwt.expiresAt ?: unauthorized()).plus(userClockSkew)
        ComplaintAdminReadIdentity(actor, testScope, version, jwt.notBefore?.minus(userClockSkew), until)
    } catch (failure: JwtException) {
        unauthorized()
    } catch (failure: IOException) {
        unauthorized()
    } catch (failure: IllegalArgumentException) {
        unauthorized()
    } catch (failure: DateTimeException) {
        unauthorized()
    } catch (failure: ArithmeticException) {
        unauthorized()
    }

    private fun boundedToken(value: String) {
        if (value.length !in 1..4096 || !TOKEN.matches(value)) unauthorized()
        val parts = value.split('.')
        val header = decode(parts[0], 512)
        try {
            TOKEN_JSON.createParser(header).use { json ->
                if (json.nextToken() != JsonToken.START_OBJECT) unauthorized()
                val fields = mutableMapOf<String, String>()
                while (json.nextToken() != JsonToken.END_OBJECT) {
                    if (json.currentToken != JsonToken.FIELD_NAME || fields.size == 3) unauthorized()
                    val name = json.currentName()
                    if (name !in setOf("alg", "kid", "typ") || json.nextToken() != JsonToken.VALUE_STRING) unauthorized()
                    fields[checkNotNull(name)] = json.text
                }
                if (json.nextToken() != null || fields["alg"] != "HS256" || fields["kid"] != JwtService.KEY_ID ||
                    fields["typ"]?.let { it != "JWT" } == true
                ) unauthorized()
            }
        } finally {
            header.fill(0)
        }
        val claims = decode(parts[1], 3072)
        try {
            // Bound malformed unauthenticated claim nesting/strings before the normal crypto decoder sees it.
            TOKEN_JSON.createParser(claims).use { json ->
                if (json.nextToken() != JsonToken.START_OBJECT) unauthorized()
                var count = 1
                var depth = 1
                while (depth > 0) {
                    val token = json.nextToken() ?: unauthorized()
                    if (++count > 128) unauthorized()
                    if (token.isStructStart) depth++
                    if (token.isStructEnd) depth--
                    if (token == JsonToken.VALUE_STRING && json.text.length > 2048) unauthorized()
                }
                if (json.nextToken() != null) unauthorized()
            }
        } finally {
            claims.fill(0)
        }
        val signature = decode(parts[2], 32)
        try {
            if (signature.size != 32) unauthorized()
        } finally {
            signature.fill(0)
        }
    }

    private fun decode(value: String, maximum: Int): ByteArray {
        if (value.length > (maximum * 4 + 2) / 3) unauthorized()
        val result = Base64.getUrlDecoder().decode(value)
        if (result.size !in 1..maximum || Base64.getUrlEncoder().withoutPadding().encodeToString(result) != value) {
            result.fill(0)
            unauthorized()
        }
        return result
    }

    override fun toString(): String = "ComplaintAdminReadAdapter(TEST-only,no-mode-authority)"

    private class Authenticated(
        val owner: ComplaintAdminReadAdapter,
        val caller: Thread,
        val context: ComplaintIngressContext,
        val identity: ComplaintAdminReadIdentity,
        val query: ComplaintAdminReadQuery,
        val position: ComplaintAdminReadPosition?,
    ) : ComplaintAdminReadAuthentication {
        val admissionIdentity = Any()
        var consumed = false
        override fun toString(): String = "ComplaintAdminReadAuthentication(redacted)"
    }

    private companion object {
        val TOKEN = Regex("[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+")
        val UUID_TEXT = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        val TOKEN_JSON = JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .disable(StreamReadFeature.INCLUDE_SOURCE_IN_LOCATION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4).maxStringLength(2048).maxNameLength(64).maxNumberLength(20).build())
            .build()

        fun unauthorized(): Nothing = rejectAdminRead(ComplaintAdminReadFailure.UNAUTHORIZED)
    }
}
