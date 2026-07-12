package me.manga.kira.backend.sourceconfig.public

import me.manga.kira.backend.common.Sha256
import me.manga.kira.backend.sourceconfig.SourceConfigFixtures
import me.manga.kira.backend.sourceconfig.admin.AbstractAdminSourceIT
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * PLAN §11 test 34 — `RawBytesChecksumIT`: *what is served is what was checksummed*. Fetch the public
 * document and hash the RAW response bytes → it equals both the `ETag` (quoted) and `X-Config-Checksum`
 * header — proving the stored canonical bytes are written verbatim with no message-converter
 * re-serialization drift (PLAN §4.1).
 */
class RawBytesChecksumIT : AbstractAdminSourceIT() {

    @Test
    fun `hashing the raw public document bytes reproduces the etag and checksum header`() {
        val api = "Raw"
        createSource(SourceConfigFixtures.validGenericSource(api)).andExpect { status { isCreated() } }
        publish(api, 1).andExpect { status { isOk() } }

        val response =
            getPublicDocument().andExpect { status { isOk() } }.andReturn().response
        val bytes = response.contentAsByteArray
        val hash = Sha256.hexUtf8(bytes.toString(Charsets.UTF_8))

        assertEquals(hash, response.getHeader("X-Config-Checksum"), "X-Config-Checksum must be the hash of the served bytes")
        assertEquals("\"$hash\"", response.getHeader("ETag"), "ETag must be the quoted hash of the served bytes")
        assertEquals(latestPointer().toString(), response.getHeader("X-Config-Revision"))
    }
}
