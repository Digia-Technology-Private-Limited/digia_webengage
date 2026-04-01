package com.digia.engage.webengage.contract

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses `<digia>` HTML tags from campaign HTML strings.
 *
 * The Digia contract is expressed as **HTML attributes** on the tag (closing or self-closing):
 * ```html
 * <digia
 *   type="inline"
 *   view-id="offer_1"
 *   placement="home_banner"
 *   args='{"title":"Special Offer","message":"Get 20% off","cta":{"text":"Shop Now"}}'
 * ></digia>
 * ```
 * ```html
 * <digia command="SHOW_DIALOG" view-id="welcome" />
 * ```
 *
 * Attribute name aliases (all mapped to the same canonical camelCase key):
 * - `view-id` / `view_id` / `viewId` → `viewId`
 * - `placement` / `placement-key` / `placement_key` / `placementKey` → `placementKey`
 * - `screen-id` / `screen_id` / `screenId` → `screenId`
 */
internal object DigiaTagParser {

    /**
     * Scans [html] for all `<digia ...>` tags and returns each as a map of canonical camelCase
     * contract keys.
     *
     * Tags with no recognized attributes are silently skipped.
     */
    fun parseAll(html: String): List<Map<String, Any?>> {
        val results = mutableListOf<Map<String, Any?>>()
        var pos = 0

        while (pos < html.length) {
            val tagStart = html.indexOf("<digia", pos, ignoreCase = true)
            if (tagStart < 0) break

            // Guard: `<digia` must be followed by a tag boundary character, not a letter/digit,
            // so we don't accidentally match `<digiaFoo>` as a candidate.
            val nameEnd = tagStart + 6 // len("<digia") = 6
            val boundary = html.getOrNull(nameEnd)
            if (boundary != null && !boundary.isTagBoundary()) {
                pos = tagStart + 1
                continue
            }

            // Scan to the closing `>` of the opening tag, respecting quoted attribute values
            // so that `>` characters embedded in args='{"a":1}' don't terminate the tag.
            val openEnd = scanToTagClose(html, nameEnd)
            if (openEnd < 0) {
                pos = tagStart + 1
                break
            }

            // openEnd is the index AFTER the closing `>`.
            // A self-closing tag has `/` immediately before the `>`.
            val selfClosing = html.getOrNull(openEnd - 2) == '/'
            val attrsEnd = if (selfClosing) openEnd - 2 else openEnd - 1
            val rawAttrs = html.substring(nameEnd, attrsEnd).trim()
            val attrs = parseAttributes(rawAttrs)

            if (attrs.isNotEmpty()) {
                results += mapAttributes(attrs)
            }

            pos =
                    if (selfClosing) {
                        openEnd
                    } else {
                        val closeIdx = html.indexOf("</digia>", openEnd, ignoreCase = true)
                        if (closeIdx >= 0) closeIdx + 8 else openEnd
                    }
        }

        return results
    }

    // ── Tag scanning ─────────────────────────────────────────────────────────

    /**
     * Advances from [from] until an unquoted `>` is found, honoring quoted attribute values.
     * Returns the index immediately after `>`, or -1 if the end of string is reached first.
     */
    private fun scanToTagClose(html: String, from: Int): Int {
        var i = from
        var inDouble = false
        var inSingle = false
        while (i < html.length) {
            when {
                inDouble -> if (html[i] == '"') inDouble = false
                inSingle -> if (html[i] == '\'') inSingle = false
                html[i] == '"' -> inDouble = true
                html[i] == '\'' -> inSingle = true
                html[i] == '>' -> return i + 1
            }
            i++
        }
        return -1
    }

    /** Returns true when [this] is a valid character that can follow `<digia` in an opening tag. */
    private fun Char.isTagBoundary(): Boolean =
            this == ' ' ||
                    this == '\t' ||
                    this == '\n' ||
                    this == '\r' ||
                    this == '>' ||
                    this == '/'

    // ── Attribute parsing ─────────────────────────────────────────────────────

    /**
     * Parses [rawAttrs] (the attribute string of the opening `<digia ...>` tag) into a key →
     * raw-string-value map.
     *
     * Uses a character-level state machine to correctly handle:
     * - Double-quoted values: `command="SHOW_DIALOG"`
     * - Single-quoted values containing JSON and double-quotes:
     * `args='{"title":"Hello","cta":{"text":"OK"}}'`
     * - Bare (unquoted) values: `type=inline`
     */
    private fun parseAttributes(rawAttrs: String): Map<String, String> {
        if (rawAttrs.isBlank()) return emptyMap()

        val result = mutableMapOf<String, String>()
        var i = 0
        val len = rawAttrs.length

        while (i < len) {
            // Skip whitespace between attributes.
            while (i < len && rawAttrs[i].isWhitespace()) i++
            if (i >= len) break

            // Parse attribute key: letters, digits, hyphens, underscores.
            val keyStart = i
            while (i < len &&
                    (rawAttrs[i].isLetterOrDigit() || rawAttrs[i] == '-' || rawAttrs[i] == '_')) i++
            val key = rawAttrs.substring(keyStart, i)
            if (key.isEmpty()) {
                i++ // skip unexpected character
                continue
            }

            // Skip whitespace before `=`.
            while (i < len && rawAttrs[i].isWhitespace()) i++
            if (i >= len || rawAttrs[i] != '=') continue // boolean attr (no value) — skip

            i++ // consume `=`

            // Skip whitespace after `=`.
            while (i < len && rawAttrs[i].isWhitespace()) i++
            if (i >= len) break

            // Parse value: double-quoted, single-quoted, or bare.
            val value: String
            when (rawAttrs[i]) {
                '"' -> {
                    i++ // skip opening `"`
                    val start = i
                    while (i < len && rawAttrs[i] != '"') i++
                    value = rawAttrs.substring(start, i)
                    if (i < len) i++ // skip closing `"`
                }
                '\'' -> {
                    i++ // skip opening `'`
                    val start = i
                    while (i < len && rawAttrs[i] != '\'') i++
                    value = rawAttrs.substring(start, i)
                    if (i < len) i++ // skip closing `'`
                }
                else -> {
                    val start = i
                    while (i < len && !rawAttrs[i].isWhitespace()) i++
                    value = rawAttrs.substring(start, i)
                }
            }

            result[key] = value
        }

        return result
    }

    /**
     * Remaps raw HTML attribute names — including kebab-case and underscore_case aliases — to the
     * canonical camelCase keys expected by [buildContract].
     *
     * The `args` attribute value is JSON-parsed into a `Map<String, Any?>` when valid; an invalid
     * JSON string is stored as an empty map.
     */
    private fun mapAttributes(attrs: Map<String, String>): Map<String, Any?> = buildMap {
        attrs["type"]?.let { put("type", it) }
        attrs["command"]?.let { put("command", it) }
        (attrs["view-id"] ?: attrs["view_id"] ?: attrs["viewId"])?.let { put("viewId", it) }
        (attrs["placement"]
                        ?: attrs["placement-key"] ?: attrs["placement_key"]
                                ?: attrs["placementKey"])
                ?.let { put("placementKey", it) }
        (attrs["screen-id"] ?: attrs["screen_id"] ?: attrs["screenId"])?.let { put("screenId", it) }
        attrs["args"]?.trim()?.takeIf { it.isNotBlank() }?.let { rawArgs ->
            put("args", parseJsonObject(htmlUnescape(rawArgs)) ?: emptyMap<String, Any?>())
        }
    }

    // ── JSON utilities ────────────────────────────────────────────────────────

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

    // ── HTML utilities ────────────────────────────────────────────────────────

    private fun htmlUnescape(value: String): String =
            value.replace("&quot;", "\"")
                    .replace("&#34;", "\"")
                    .replace("&apos;", "'")
                    .replace("&#39;", "'")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&amp;", "&")
}
