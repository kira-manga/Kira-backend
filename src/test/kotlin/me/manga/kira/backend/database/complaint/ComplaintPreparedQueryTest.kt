package me.manga.kira.backend.database.complaint

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ComplaintPreparedQueryTest {
    @Test
    fun `index evidence distinguishes unexecuted plans and executed zero-row lookups`() {
        val plan = ObjectMapper().readTree(
            """
            {"Node Type":"Nested Loop","Actual Loops":1,"Plans":[
              {"Node Type":"Index Scan","Relation Name":"first","Index Name":"planned_only","Actual Loops":0,"Actual Rows":0},
              {"Node Type":"Bitmap Heap Scan","Relation Name":"second","Actual Loops":1,"Plans":[
                {"Node Type":"Bitmap Index Scan","Index Name":"executed_empty","Actual Loops":1,"Actual Rows":0}
              ]}
            ]}
            """.trimIndent(),
        )
        assertEquals(
            listOf(
                QueryIndexNode("planned_only", "first", "Index Scan", 0),
                QueryIndexNode("executed_empty", null, "Bitmap Index Scan", 1),
            ),
            planIndexNodes(plan),
        )
        assertEquals(listOf("executed_empty"), planIndexNodes(plan).filter { it.loops > 0 }.map { it.index })
    }
}
