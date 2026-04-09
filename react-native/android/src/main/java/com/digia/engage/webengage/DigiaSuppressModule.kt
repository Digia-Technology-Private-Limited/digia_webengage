package com.digia.engage.webengage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.WritableNativeMap
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.webengage.sdk.android.WebEngage
import com.webengage.sdk.android.actions.render.InAppNotificationData
import com.webengage.sdk.android.callbacks.InAppNotificationCallbacks
import org.json.JSONArray
import org.json.JSONObject

/**
 * React Native native module that mirrors Flutter's [DigiaSuppressPlugin].
 *
 * Registers a native WebEngage [InAppNotificationCallbacks], identifies each in-app campaign as
 * Digia or normal, and routes accordingly:
 *
 * ```
 * onInAppNotificationPrepared()
 *     ↓
 * isDigiaCampaign(data)?
 *     ↓ YES                          ↓ NO
 * setShouldRender(false)         return inAppData (WebEngage renders normally)
 * emit "weDigiaInAppPrepared"
 * ```
 *
 * JS receives events on `NativeEventEmitter("DigiaSuppressModule")`:
 * - "weDigiaInAppPrepared" → all keys from inAppData + experimentId + variationId
 * - "weDigiaInAppDismissed" → { experimentId: String }
 */
class DigiaSuppressModule(
        private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var inAppCallback: InAppNotificationCallbacks? = null

    companion object {
        const val NAME = "DigiaSuppressModule"
        const val EVENT_PREPARED = "weDigiaInAppPrepared"
        const val EVENT_DISMISSED = "weDigiaInAppDismissed"
        private const val TAG = "DigiaSuppressModule"

        private val VALID_COMMANDS = setOf("SHOW_DIALOG", "SHOW_BOTTOM_SHEET")
    }

    override fun getName(): String = NAME

    override fun initialize() {
        super.initialize()
        Log.d(TAG, "initialize() — registering in-app callback")
        registerInAppCallback()
    }

    // ReactContextBaseJavaModule.invalidate() is called when the React Native
    // bridge is torn down (JS reload, hot reload, app restart). Without this
    // the old inAppCallback pointer would remain non-null in the next module
    // instance's registerInAppCallback(), causing the guard `if (inAppCallback
    // != null) return` to short-circuit and NO callbacks would fire after the
    // first reload. Flutter's equivalent is onDetachedFromEngine().
    override fun invalidate() {
        Log.d(TAG, "invalidate() — unregistering in-app callback")
        inAppCallback?.let { WebEngage.unregisterInAppNotificationCallback(it) }
        inAppCallback = null
        super.invalidate()
    }

    // Called by React Native when the module is initialised.
    @ReactMethod
    fun install() {
        registerInAppCallback()
    }

    // Required for NativeEventEmitter on Android (addListener / removeListeners
    // are no-ops here because we drive the emitter from the native side).
    @ReactMethod fun addListener(@Suppress("UNUSED_PARAMETER") eventName: String) = Unit

    @ReactMethod fun removeListeners(@Suppress("UNUSED_PARAMETER") count: Int) = Unit

    // ── Callback registration ─────────────────────────────────────────────────

    private fun registerInAppCallback() {
        if (inAppCallback != null) {
            Log.d(TAG, "registerInAppCallback() — already registered, skipping")
            return
        }
        Log.d(TAG, "registerInAppCallback() — registering new callback")

        val callback =
                object : InAppNotificationCallbacks {
                    override fun onInAppNotificationPrepared(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ): InAppNotificationData {
                        // Mirror Flutter's DigiaSuppressPlugin exactly — getData() is
                        // non-null for any real in-app notification.
                        val raw = jsonObjectToMap(inAppData.getData() ?: JSONObject())
                        val payload: Map<String, Any?> = buildMap {
                            putAll(raw)
                            put("experimentId", inAppData.getExperimentId())
                            put("variationId", inAppData.getVariationId())
                        }

                        val experimentId2 = inAppData.getExperimentId()
                        if (!isDigiaCampaign(payload)) {
                            // Normal campaign → let WebEngage render natively.
                            Log.d(TAG, "onPrepared — NON-Digia campaign, experimentId=$experimentId2 — letting WE render")
                            return inAppData
                        }

                        // Digia campaign → suppress WebEngage's own renderer.
                        Log.d(TAG, "onPrepared — Digia campaign, experimentId=$experimentId2 — suppressing WE render, emitting $EVENT_PREPARED")
                        inAppData.setShouldRender(false)

                        // Capture the plain Kotlin map here (thread-safe), then create
                        // WritableNativeMap inside mainHandler.post. WritableNativeMap is a
                        // JNI-backed bridge type — creating it on a WE background thread and
                        // consuming it on the main thread causes silent failures or crashes.
                        val payloadSnapshot = HashMap(payload)
                        mainHandler.post {
                            try {
                                val params = mapToWritableMap(payloadSnapshot)
                                reactContext
                                        .getJSModule(
                                                DeviceEventManagerModule.RCTDeviceEventEmitter::class
                                                        .java
                                        )
                                        .emit(EVENT_PREPARED, params)
                                Log.d(TAG, "onPrepared — emitted $EVENT_PREPARED to JS")
                            } catch (e: Exception) {
                                Log.e(TAG, "onPrepared — failed to emit $EVENT_PREPARED: ${e.message}")
                            }
                        }
                        return inAppData
                    }

                    override fun onInAppNotificationShown(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ) = Unit

                    override fun onInAppNotificationClicked(
                            context: Context,
                            inAppData: InAppNotificationData,
                            actionId: String,
                    ): Boolean = false

                    override fun onInAppNotificationDismissed(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ) {
                        val experimentId = inAppData.getExperimentId()
                        Log.d(TAG, "onDismissed — experimentId=$experimentId")
                        mainHandler.post {
                            try {
                                val params =
                                        WritableNativeMap().apply {
                                            putString("experimentId", experimentId)
                                        }
                                reactContext
                                        .getJSModule(
                                                DeviceEventManagerModule.RCTDeviceEventEmitter::class
                                                        .java
                                        )
                                        .emit(EVENT_DISMISSED, params)
                                Log.d(TAG, "onDismissed — emitted $EVENT_DISMISSED to JS")
                            } catch (e: Exception) {
                                Log.e(TAG, "onDismissed — failed to emit $EVENT_DISMISSED: ${e.message}")
                            }
                        }
                    }
                }

        inAppCallback = callback
        WebEngage.registerInAppNotificationCallback(callback)
    }

    // ── Campaign identification ───────────────────────────────────────────────

    private fun isDigiaCampaign(data: Map<String, Any?>): Boolean =
            hasDigiaContractKeys(data) || containsDigiaHtml(data)

    private fun hasDigiaContractKeys(data: Map<String, Any?>): Boolean {
        // Normalize the map first — WebEngage may deliver keys as view-id / view_id
        // instead of viewId. Mirrors the JS normalizeAttrKey and Flutter's
        // _normalizeAttrKey so detection is consistent across all platforms.
        val normalized = data.mapKeys { (k, _) -> normalizeKey(k) }
        val viewId = (normalized["viewId"] as? String)?.trim() ?: return false
        if (viewId.isEmpty()) return false
        val type = (normalized["type"] as? String)?.lowercase()
        val command = (normalized["command"] as? String)?.uppercase()
        return type != null || command in VALID_COMMANDS
    }

    /** Mirrors JS normalizeAttrKey / Flutter _normalizeAttrKey. */
    private fun normalizeKey(key: String): String =
            when (key) {
                "view-id", "view_id" -> "viewId"
                "placement-key", "placement_key", "placement" -> "placementKey"
                "screen-id", "screen_id" -> "screenId"
                else -> key
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

    // ── JSON helpers ──────────────────────────────────────────────────────────

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

    private fun mapToWritableMap(map: Map<String, Any?>): WritableNativeMap {
        val result = WritableNativeMap()
        for ((key, value) in map) {
            when (value) {
                null -> result.putNull(key)
                is Boolean -> result.putBoolean(key, value)
                is Int -> result.putInt(key, value)
                is Double -> result.putDouble(key, value)
                is Float -> result.putDouble(key, value.toDouble())
                is Long -> result.putDouble(key, value.toDouble())
                is String -> result.putString(key, value)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    result.putMap(key, mapToWritableMap(value as Map<String, Any?>))
                }
                is List<*> -> result.putArray(key, listToWritableArray(value))
                else -> result.putString(key, value.toString())
            }
        }
        return result
    }

    private fun listToWritableArray(list: List<*>): com.facebook.react.bridge.WritableNativeArray {
        val result = com.facebook.react.bridge.WritableNativeArray()
        for (item in list) {
            when (item) {
                null -> result.pushNull()
                is Boolean -> result.pushBoolean(item)
                is Int -> result.pushInt(item)
                is Double -> result.pushDouble(item)
                is Float -> result.pushDouble(item.toDouble())
                is Long -> result.pushDouble(item.toDouble())
                is String -> result.pushString(item)
                is Map<*, *> -> {
                    @Suppress("UNCHECKED_CAST")
                    result.pushMap(mapToWritableMap(item as Map<String, Any?>))
                }
                is List<*> -> result.pushArray(listToWritableArray(item))
                else -> result.pushString(item.toString())
            }
        }
        return result
    }
}
