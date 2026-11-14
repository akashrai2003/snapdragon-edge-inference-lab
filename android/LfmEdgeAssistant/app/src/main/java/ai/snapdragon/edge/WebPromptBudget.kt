package ai.snapdragon.edge

import org.json.JSONArray
import org.json.JSONObject

/** Shrink excerpts fairly, retaining every source, before exact template/tokenizer checks. */
object WebPromptBudget {
    fun shrink(messages: JSONArray): Boolean {
        var changed = false
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            if (message.optString("role") != "tool") continue
            val result =
                runCatching { JSONObject(message.getString("content")) }.getOrNull() ?: continue
            val rows = result.optJSONArray("results") ?: continue
            for (j in 0 until rows.length()) {
                val row = rows.getJSONObject(j)
                for (field in listOf("text", "snippet")) {
                    val text = row.optString(field)
                    val minimum = if (field == "text") 180 else 80
                    if (text.length <= minimum) continue
                    row.put(field, text.take(maxOf(minimum, text.length * 2 / 3)))
                    row.put("truncated", true)
                    changed = true
                }
            }
            message.put("content", result.toString())
        }
        return changed
    }
}
