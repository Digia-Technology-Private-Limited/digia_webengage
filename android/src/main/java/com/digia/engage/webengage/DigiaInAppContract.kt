package com.digia.engage.webengage

import org.json.JSONArray
import org.json.JSONObject

internal fun normalizeWithDigiaContract(raw: Map<String, Any?>): Map<String, Any?> {
    val topLevel = parseContractFromMap(raw, "top_level")
    if (topLevel != null) return raw + topLevel

    val script = extractContractFromScript(raw)
    return if (script != null) raw + script else raw
}

private fun extractContractFromScript(raw: Map<String, Any?>): Map<String, Any?>? {
    for (text in collectTextCandidates(raw)) {
        val lower = text.lowercase()
        var index = 0
        while (index < lower.length) {
            val scriptStart = lower.indexOf("<script", index)
            if (scriptStart < 0) break

            val tagEnd = lower.indexOf('>', scriptStart)
            if (tagEnd < 0) break

            val openTag = lower.substring(scriptStart, tagEnd + 1)
            if (!openTag.contains("digia-payload")) {
                index = tagEnd + 1
                continue
            }

            val closeTag = lower.indexOf("</script>", tagEnd + 1)
            if (closeTag < 0) break

            val body = htmlUnescape(text.substring(tagEnd + 1, closeTag)).trim()
            if (body.isNotEmpty()) {
                val jsonBody = extractJsonObjectBody(body)
                if (jsonBody != null) {
                    val parsed = parseJsonObject(jsonBody)
                    if (parsed != null) {
                        val contract = parseContractFromMap(parsed, "html_script")
                        if (contract != null) return contract
                    }
                }
            }

            index = closeTag + "</script>".length
        }
    }

    return null
}

private fun parseContractFromMap(raw: Map<String, Any?>, source: String): Map<String, Any?>? {
    val command = normalizeCommand(raw["command"]?.toString()) ?: return null
    val viewId = raw["viewId"]?.toString()?.trim().orEmpty()
    if (viewId.isEmpty()) return null

    val screenId = (raw["screenId"]?.toString() ?: raw["screen_id"]?.toString())
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    val args = normalizeArgs(raw["args"])
    val result = mutableMapOf<String, Any?>(
        "command" to command,
        "viewId" to viewId,
        "args" to args,
        "digiaContractSource" to source,
    )
    if (screenId != null) {
        result["screenId"] = screenId
    }
    return result
}

private fun normalizeCommand(raw: String?): String? {
    val value = raw?.trim()?.uppercase().orEmpty()
    return when (value) {
        "SHOW_DIALOG", "SHOW_BOTTOM_SHEET" -> value
        else -> null
    }
}

private fun normalizeArgs(raw: Any?): Map<String, Any?> {
    return when (raw) {
        null -> emptyMap()
        is Map<*, *> -> raw.entries
            .mapNotNull { (k, v) -> (k as? String)?.let { it to jsonLikeValue(v) } }
            .toMap()
        is String -> parseJsonObject(raw) ?: emptyMap()
        else -> emptyMap()
    }
}

private fun parseJsonObject(raw: String): Map<String, Any?>? {
    val trimmed = raw.trim()
    if (trimmed.isEmpty()) return null
    return try {
        jsonObjectToMap(JSONObject(trimmed))
    } catch (_: Exception) {
        null
    }
}

private fun extractJsonObjectBody(raw: String): String? {
    if (raw.startsWith("{") && raw.endsWith("}")) return raw
    val start = raw.indexOf('{')
    val end = raw.lastIndexOf('}')
    if (start < 0 || end <= start) return null
    return raw.substring(start, end + 1).trim()
}

private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        out[key] = jsonLikeValue(obj.opt(key))
    }
    return out
}

private fun jsonArrayToList(array: JSONArray): List<Any?> {
    val out = mutableListOf<Any?>()
    for (i in 0 until array.length()) {
        out += jsonLikeValue(array.opt(i))
    }
    return out
}

private fun jsonLikeValue(value: Any?): Any? = when (value) {
    JSONObject.NULL -> null
    is JSONObject -> jsonObjectToMap(value)
    is JSONArray -> jsonArrayToList(value)
    else -> value
}

private fun collectTextCandidates(root: Any?): List<String> {
    val queue = ArrayDeque<Any?>()
    val out = mutableListOf<String>()
    queue.add(root)

    while (queue.isNotEmpty()) {
        when (val node = queue.removeFirst()) {
            is String -> if (node.contains("<")) out += node
            is Map<*, *> -> node.values.forEach(queue::addLast)
            is List<*> -> node.forEach(queue::addLast)
        }
    }

    return out
}

private fun htmlUnescape(value: String): String {
    return value
        .replace("&quot;", "\"")
        .replace("&#34;", "\"")
        .replace("&apos;", "'")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
