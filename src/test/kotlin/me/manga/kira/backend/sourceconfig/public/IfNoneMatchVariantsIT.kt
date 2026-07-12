package me.manga.kira.backend.sourceconfig.public

import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 35 — `IfNoneMatchVariantsIT`: conditional-GET correctness. `If-None-Match: *` → 304;
 * a comma-separated list that includes the current ETag → 304; a non-matching list → 200; a **weak
 * validator `W/"<current-hash>"` → 200** (strong comparison never matches a weak validator, RFC 9110
 * §8.8.3.2); every 304 carries no body (PLAN §4.1).
 */
class IfNoneMatchVariantsIT : AbstractAdminSourceIT() {

    private val other1 = "\"0000000000000000000000000000000000000000000000000000000000000000\""
    private val other2 = "\"1111111111111111111111111111111111111111111111111111111111111111\""

    @Test
    fun `if-none-match variants follow strong comparison`() {
        val api = "Variants"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }
        val etag = getPublicDocument().andExpect { status { isOk() } }.andReturn().response.getHeader("ETag")!!

        // '*' matches any existing document → 304, no body.
        getPublicDocument(ifNoneMatch = "*").andExpect {
            status { isNotModified() }
            content { string("") }
        }

        // A comma-separated list that includes the current ETag → 304, no body.
        getPublicDocument(ifNoneMatch = "$other1, $etag, $other2").andExpect {
            status { isNotModified() }
            content { string("") }
        }

        // A list with no matching entry → 200 full body.
        getPublicDocument(ifNoneMatch = "$other1, $other2").andExpect { status { isOk() } }

        // A weak validator carrying the identical opaque hash NEVER strongly matches → 200 full body.
        getPublicDocument(ifNoneMatch = "W/$etag").andExpect { status { isOk() } }
    }
}
