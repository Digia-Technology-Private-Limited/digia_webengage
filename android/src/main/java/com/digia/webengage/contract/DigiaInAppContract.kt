package com.digia.webengage.contract

import org.json.JSONArray
import org.json.JSONObject

// ─── Public API ───────────────────────────────────────────────────────────────

/**
 * Scans [raw] for a Digia contract and merges the normalized contract keys into the result.
 *
 * Contract sources are tried in priority order:
 * 1. **Top-level keys** — `command` / `type` present directly in [raw].
 * 2. **HTML `<digia>` tag** — attribute-based tag embedded in any string value.
 *
 * Returns [raw] unchanged when no valid contract is found.
 */
internal fun normalizeWithDigiaContract(raw: Map<String, Any?>): Map<String, Any?> {
    val contract = contractExtractor.extract(raw) ?: return raw
    return raw + contract
}

// ─── Chain of Responsibility ──────────────────────────────────────────────────

private val contractExtractor: DigiaContractExtractor by lazy {
    DigiaContractExtractor(sources = listOf(TopLevelContractSource, HtmlDigiaTagContractSource))
}

/**
 * Iterates over [sources] in order and returns the first non-null contract map.
 *
 * Adding a new delivery mechanism only requires registering a new [ContractSource] here — existing
 * sources remain untouched (Open/Closed Principle).
 */
internal class DigiaContractExtractor(private val sources: List<ContractSource>) {
    fun extract(raw: Map<String, Any?>): Map<String, Any?>? =
            sources.firstNotNullOfOrNull { it.extract(raw) }
}

// ─── Strategy: ContractSource ─────────────────────────────────────────────────

/**
 * Single-method strategy for extracting a Digia contract from a raw payload map.
 *
 * Each implementation represents one delivery mechanism (top-level keys, HTML tag, etc.) with no
 * knowledge of the others — Interface-Segregation + Single-Responsibility.
 */
internal fun interface ContractSource {
    fun extract(raw: Map<String, Any?>): Map<String, Any?>?
}

// ─── Source 1: top-level keys ─────────────────────────────────────────────────

/**
 * Reads the Digia contract from top-level keys in the payload map.
 *
 * Handles campaigns where `command` / `type`, `viewId`, etc. appear as first-class keys in the
 * WebEngage custom-data map.
 */
internal object TopLevelContractSource : ContractSource {
    override fun extract(raw: Map<String, Any?>): Map<String, Any?>? =
            buildContract(raw, "top_level")
}

// ─── Source 2: <digia> HTML tag ───────────────────────────────────────────────

/**
 * Scans all string values in the payload map (recursively) for embedded `<digia>` tags.
 *
 * Delegates parsing to [DigiaTagParser] which supports attribute-based formats. The first tag that
 * yields a valid contract is returned.
 */
internal object HtmlDigiaTagContractSource : ContractSource {
    override fun extract(raw: Map<String, Any?>): Map<String, Any?>? {
        for (text in collectTextCandidates(raw)) {
            for (tagMap in DigiaTagParser.parseAll(text)) {
                val contract = buildContract(tagMap, "html_digia_tag")
                if (contract != null) return contract
            }
        }
        return null
    }
}

// ─── Contract builder (validate + normalize) ──────────────────────────────────

/**
 * Validates and normalizes a raw attribute / key map into a Digia contract.
 *
 * Returns `null` when the data doesn't form a complete valid contract:
 * - `type = "inline"` → command is `INLINE`; requires non-blank `viewId` and `placementKey`.
 * - Otherwise `command` must be `SHOW_DIALOG` or `SHOW_BOTTOM_SHEET`; requires non-blank `viewId`.
 */
private fun buildContract(raw: Map<String, Any?>, source: String): Map<String, Any?>? {

    val type = raw["type"]?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    val command = normalizeCommand(raw["command"]?.toString())
    val resolvedCommand = if (type == "inline") null else command

    // ✅ At least one must exist
    if (type == null && resolvedCommand == null) {
        return null
    }

    val viewId = raw["viewId"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: return null

    val placementKey = raw["placementKey"]?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    val screenId =
            (raw["screenId"] ?: raw["screen_id"])?.toString()?.trim()?.takeIf { it.isNotEmpty() }

    val args = normalizeArgs(raw["args"])

    // Inline must have placementKey
    if (type == "inline" && placementKey == null) {
        return null
    }

    return buildMap {
        type?.let { put("type", it) }
        resolvedCommand?.let { put("command", it) }

        put("viewId", viewId)
        put("args", args)
        put("digiaContractSource", source)

        placementKey?.let { put("placementKey", it) }
        screenId?.let { put("screenId", it) }
    }
}

private fun normalizeCommand(raw: String?): String? =
        when (raw?.trim()?.uppercase().orEmpty()) {
            "SHOW_INLINE" -> "SHOW_INLINE" // legacy alias
            "SHOW_DIALOG", "SHOW_BOTTOM_SHEET", -> raw!!.trim().uppercase()
            else -> null
        }

private fun normalizeArgs(raw: Any?): Map<String, Any?> =
        when (raw) {
            null -> emptyMap()
            is Map<*, *> ->
                    buildMap {
                        raw.forEach { (k, v) -> (k as? String)?.let { put(it, jsonLikeValue(v)) } }
                    }
            is String -> parseJsonObject(raw) ?: emptyMap()
            else -> emptyMap()
        }

// ─── Text-candidate scanner ───────────────────────────────────────────────────

private fun collectTextCandidates(root: Any?): List<String> {
    val queue = ArrayDeque<Any?>()
    val results = mutableListOf<String>()
    queue.add(root)
    while (queue.isNotEmpty()) {
        when (val node = queue.removeFirst()) {
            is String -> if (node.contains("<digia", ignoreCase = true)) results += node
            is Map<*, *> -> node.values.forEach(queue::addLast)
            is List<*> -> node.forEach(queue::addLast)
        }
    }
    return results
}

// ─── JSON utilities ───────────────────────────────────────────────────────────

private fun parseJsonObject(raw: String): Map<String, Any?>? =
        raw.trim().takeIf { it.isNotEmpty() }?.let {
            runCatching { jsonObjectToMap(JSONObject(it)) }.getOrNull()
        }

private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> = buildMap {
    val keys = obj.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, jsonLikeValue(obj.opt(key)))
    }
}

private fun jsonArrayToList(array: JSONArray): List<Any?> =
        buildList(array.length()) {
            for (i in 0 until array.length()) add(jsonLikeValue(array.opt(i)))
        }

private fun jsonLikeValue(value: Any?): Any? =
        when (value) {
            JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> jsonArrayToList(value)
            else -> value
        }
