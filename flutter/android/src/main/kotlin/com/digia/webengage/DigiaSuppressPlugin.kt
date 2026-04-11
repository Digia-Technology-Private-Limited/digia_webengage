package com.digia.webengage

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.webengage.sdk.android.WebEngage
import com.webengage.sdk.android.actions.render.InAppNotificationData
import com.webengage.sdk.android.callbacks.InAppNotificationCallbacks
import io.flutter.embedding.engine.plugins.FlutterPlugin
import io.flutter.plugin.common.MethodChannel
import org.json.JSONArray
import org.json.JSONObject

/**
 * Flutter plugin that wires a native WebEngage [InAppNotificationCallbacks], identifies campaign
 * type (Digia vs normal), and routes them accordingly:
 *
 * ```
 * Native Layer (Android)
 *     ↓
 * onInAppNotificationPrepared() called
 *     ↓
 * isDigiaCampaign(data)?
 *     ↓ YES                         ↓ NO
 * setShouldRender(false)        allow SDK rendering (return as-is)
 * send data → Flutter
 * ```
 *
 * A campaign is "Digia" when its data contains a `viewId` key plus at least one routing signal
 * (`type` or a known `command`), or when any HTML string in the data embeds a `<digia .../>`
 * attribute tag or a `<script digia-payload>` block.
 *
 * Channel protocol (Android → Dart):
 * - "onInAppPrepared" → Map with all keys from [InAppNotificationData.getData] plus
 * ```
 *                        `"experimentId"` and `"variationId"`.
 * ```
 * - "onInAppDismissed" → Map `{ "experimentId": String }`.
 */
class DigiaSuppressPlugin : FlutterPlugin {

    private var channel: MethodChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var inAppCallback: InAppNotificationCallbacks? = null

    companion object {
        const val CHANNEL = "plugins.digia.tech/webengage_suppress"
        private const val TAG = "DigiaSuppressPlugin"

        private val VALID_COMMANDS = setOf("SHOW_DIALOG", "SHOW_BOTTOM_SHEET")
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "trackSystemEvent" -> {
                    val args = call.arguments as? Map<*, *>
                    val eventName = args?.get("eventName") as? String
                    @Suppress("UNCHECKED_CAST")
                    val systemData = (args?.get("systemData") as? Map<String, Any?>) ?: emptyMap()
                    @Suppress("UNCHECKED_CAST")
                    val eventData = (args?.get("eventData") as? Map<String, Any?>) ?: emptyMap()
                    if (!eventName.isNullOrBlank()) {
                        runCatching {
                            WebEngage.get()
                                    .analytics()
                                    .trackSystem(eventName, systemData, eventData)
                            android.util.Log.v(
                                    TAG,
                                    "trackSystemEvent: $eventName systemData=$systemData"
                            )
                        }
                    } else {
                        android.util.Log.w(TAG, "trackSystemEvent: skipped — eventName is blank")
                    }
                    result.success(null)
                }
                // `configure` is kept for API compatibility but suppression is now per-campaign.
                else -> result.success(null)
            }
        }
        registerInAppCallback()
    }

    override fun onDetachedFromEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel?.setMethodCallHandler(null)
        inAppCallback?.let { WebEngage.unregisterInAppNotificationCallback(it) }
        inAppCallback = null
        channel = null
    }

    // ── Callback registration ─────────────────────────────────────────────────

    private fun registerInAppCallback() {
        val callback =
                object : InAppNotificationCallbacks {

                    override fun onInAppNotificationPrepared(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ): InAppNotificationData {
                        val rawData = jsonObjectToMap(inAppData.getData())
                        val payload: Map<String, Any?> = buildMap {
                            putAll(rawData)
                            put("experimentId", inAppData.getExperimentId())
                            put("variationId", inAppData.getVariationId())
                        }

                        if (!isDigiaCampaign(payload)) {
                            // Normal campaign → let WebEngage SDK render it.
                            return inAppData
                        }

                        // Digia campaign → suppress WebEngage's own renderer.
                        inAppData.setShouldRender(false)

                        // invokeMethod must run on the main thread when called from a
                        // WebEngage background worker.
                        mainHandler.post { channel?.invokeMethod("onInAppPrepared", payload) }

                        return inAppData
                    }

                    override fun onInAppNotificationShown(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ) = Unit // no-op

                    override fun onInAppNotificationClicked(
                            context: Context,
                            inAppData: InAppNotificationData,
                            actionId: String,
                    ): Boolean = false // let WebEngage handle CTA routing

                    override fun onInAppNotificationDismissed(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ) {
                        val payload = mapOf("experimentId" to inAppData.getExperimentId())
                        mainHandler.post { channel?.invokeMethod("onInAppDismissed", payload) }
                    }
                }

        inAppCallback = callback
        WebEngage.registerInAppNotificationCallback(callback)
    }

    // ── Campaign Type Identification ──────────────────────────────────────────

    /**
     * Returns true when [data] carries a Digia rendering contract.
     *
     * Mirrors Dart's `WebEngagePayloadMapper._normalizeWithDigiaContract`:
     * 1. **Structured keys**: `viewId` present + at least one routing signal (`type` or `command`).
     * 2. **Embedded HTML**: any string value contains `<digia` or `digia-payload`.
     */
    private fun isDigiaCampaign(data: Map<String, Any?>): Boolean =
            hasDigiaContractKeys(data) || containsDigiaHtml(data)

    private fun hasDigiaContractKeys(data: Map<String, Any?>): Boolean {
        val viewId = (data["viewId"] as? String)?.trim() ?: return false
        if (viewId.isEmpty()) return false

        val type = (data["type"] as? String)?.lowercase()
        val command = (data["command"] as? String)?.uppercase()

        return type != null || command in VALID_COMMANDS
    }

    private fun containsDigiaHtml(node: Any?): Boolean =
            when (node) {
                is String -> {
                    val lower = node.lowercase()
                    "<digia" in lower || "digia-payload" in lower
                }
                is Map<*, *> -> node.values.any { containsDigiaHtml(it) }
                is List<*> -> node.any { containsDigiaHtml(it) }
                else -> false
            }

    // ── JSON → Dart-compatible Map helpers ────────────────────────────────────

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> =
            json.keys().asSequence().associateWith { key -> unpack(json.opt(key)) }

    private fun jsonArrayToList(array: JSONArray): List<Any?> =
            (0 until array.length()).map { i -> unpack(array.opt(i)) }

    private fun unpack(value: Any?): Any? =
            when (value) {
                null, JSONObject.NULL -> null
                is JSONObject -> jsonObjectToMap(value)
                is JSONArray -> jsonArrayToList(value)
                else -> value
            }
}
