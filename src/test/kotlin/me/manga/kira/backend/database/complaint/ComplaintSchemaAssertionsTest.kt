package me.manga.kira.backend.database.complaint

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintSchemaAssertionsTest {
    @Test
    fun `schema normalization changes only the anchored DDL header`() {
        val index = "index|complaint_journal_publications|publication_ref|CREATE INDEX publication_ref " +
            "ON public.complaint_journal_publications USING btree (object_key) WHERE object_key <> 'public'"
        assertEquals(index.replace("ON public.", "ON <schema>."), normalizeSchemaHeader(index, "public"))
        val function = "function|public_fixture|CREATE OR REPLACE FUNCTION public.public_fixture() RETURNS text AS 'SELECT ''public'''"
        assertEquals(function.replace("FUNCTION public.", "FUNCTION <schema>."), normalizeSchemaHeader(function, "public"))
        val literal = "constraint|publication_ref|CHECK (value = 'public')"
        assertEquals(literal, normalizeSchemaHeader(literal, "public"))
    }
}
