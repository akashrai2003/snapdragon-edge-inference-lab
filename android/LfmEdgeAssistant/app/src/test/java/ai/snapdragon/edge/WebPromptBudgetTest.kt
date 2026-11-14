package ai.snapdragon.edge

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WebPromptBudgetTest {
    @Test
    fun fitsExcerptsWithoutSilentlyDroppingOtherSources() {
        val rows = JSONArray()
        for (id in 1..4) rows.put(
            JSONObject()
                .put("id", id)
                .put("url", "https://example.com/$id")
                .put("page_read", true)
                .put("text", "evidence ".repeat(200))
        )
        val messages =
            JSONArray()
                .put(
                    JSONObject()
                        .put("role", "tool")
                        .put("content", JSONObject().put("results", rows).toString())
                )
        assertTrue(WebPromptBudget.shrink(messages))
        val smaller =
            JSONObject(messages.getJSONObject(0).getString("content")).getJSONArray("results")
        assertEquals(4, smaller.length())
        for (i in 0..3) {
            val row = smaller.getJSONObject(i)
            assertEquals(i + 1, row.getInt("id"))
            assertEquals("https://example.com/${i + 1}", row.getString("url"))
            assertTrue(row.getString("text").length < 1800)
            assertTrue(row.getBoolean("truncated"))
        }
        repeat(20) { WebPromptBudget.shrink(messages) }
        assertFalse(WebPromptBudget.shrink(messages))
        val minimum =
            JSONObject(messages.getJSONObject(0).getString("content")).getJSONArray("results")
        assertTrue((0..3).all { minimum.getJSONObject(it).getString("text").length >= 180 })
    }
}
