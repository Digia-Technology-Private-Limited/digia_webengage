package com.digia.engage.webengage

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.facebook.react.bridge.*
import com.facebook.react.modules.core.DeviceEventManagerModule
import com.webengage.sdk.android.WebEngage
import com.webengage.sdk.android.actions.render.InAppNotificationData
import com.webengage.sdk.android.callbacks.InAppNotificationCallbacks
import org.json.JSONArray
import org.json.JSONObject

class DigiaSuppressModule(
        private val reactContext: ReactApplicationContext,
) : ReactContextBaseJavaModule(reactContext) {

    private var inAppCallback: InAppNotificationCallbacks? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        const val NAME = "DigiaSuppressModule"
        const val EVENT_PREPARED = "weDigiaInAppPrepared"
        const val EVENT_DISMISSED = "weDigiaInAppDismissed"
        private const val TAG = "DigiaSuppressModule"

        private val VALID_COMMANDS = setOf("SHOW_DIALOG", "SHOW_BOTTOM_SHEET")
    }

    init {
        Log.d(TAG, "init — registering in-app callback")
        registerInAppCallback()
    }

    override fun getName(): String = NAME

    override fun invalidate() {
        Log.d(TAG, "invalidate() — unregistering in-app callback")
        inAppCallback?.let { WebEngage.unregisterInAppNotificationCallback(it) }
        inAppCallback = null
        super.invalidate()
    }

    @ReactMethod
    fun addListener(eventName: String) {
        Log.d(TAG, "addListener — event=$eventName")
    }

    @ReactMethod
    fun removeListeners(count: Int) {
        Log.d(TAG, "removeListeners — count=$count")
    }

    @ReactMethod
    fun trackSystemEvent(eventName: String, systemData: ReadableMap, eventData: ReadableMap) {
        if (eventName.isBlank()) {
            Log.w(TAG, "trackSystemEvent — skipped, eventName is blank")
            return
        }
        val system = systemData.toHashMap() as Map<String, Any?>
        val event = eventData.toHashMap() as Map<String, Any?>
        runCatching {
            WebEngage.get().analytics().trackSystem(eventName, system, event)
            Log.v(TAG, "trackSystemEvent — $eventName systemData=$system")
        }
                .onFailure { e -> Log.e(TAG, "trackSystemEvent — failed: ${e.message}", e) }
    }

    // -------------------------------------------------------------------------
    // Event Emission — fire directly, no queuing
    // -------------------------------------------------------------------------

    private fun emitEvent(eventName: String, params: WritableMap) {
        mainHandler.post {
            try {
                reactContext
                        .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter::class.java)
                        .emit(eventName, params)
                Log.d(TAG, "emitEvent — emitted $eventName on main thread")
            } catch (e: Exception) {
                Log.e(TAG, "emitEvent — failed to emit $eventName: ${e.message}", e)
            }
        }
    }

    // -------------------------------------------------------------------------
    // WebEngage Callback Registration
    // -------------------------------------------------------------------------

    private fun registerInAppCallback() {
        inAppCallback?.let { WebEngage.unregisterInAppNotificationCallback(it) }

        val callback =
                object : InAppNotificationCallbacks {

                    override fun onInAppNotificationPrepared(
                            context: Context,
                            inAppData: InAppNotificationData,
                    ): InAppNotificationData {

                        val raw = jsonObjectToMap(inAppData.getData() ?: JSONObject())
                        val payload: Map<String, Any?> = buildMap {
                            putAll(raw)
                            put("experimentId", inAppData.getExperimentId())
                            put("variationId", inAppData.getVariationId())
                        }

                        if (!isDigiaCampaign(payload)) {
                            Log.d(TAG, "onPrepared — Non-Digia campaign, allowing render")
                            return inAppData
                        }

                        Log.d(TAG, "onPrepared — Digia campaign detected")
                        inAppData.setShouldRender(false)

                        val params = mapToWritableMap(payload)
                        emitEvent(EVENT_PREPARED, params)

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
                        val params =
                                WritableNativeMap().apply {
                                    putString("experimentId", inAppData.getExperimentId())
                                }

                        Log.d(TAG, "onDismissed — experimentId=${inAppData.getExperimentId()}")
                        emitEvent(EVENT_DISMISSED, params)
                    }
                }

        inAppCallback = callback
        WebEngage.registerInAppNotificationCallback(callback)
        Log.d(TAG, "registerInAppCallback() — callback registered")
    }

    // -------------------------------------------------------------------------
    // Campaign Identification
    // -------------------------------------------------------------------------

    private fun isDigiaCampaign(data: Map<String, Any?>): Boolean =
            hasDigiaContractKeys(data) || containsDigiaHtml(data)

    private fun hasDigiaContractKeys(data: Map<String, Any?>): Boolean {
        val normalized = data.mapKeys { (k, _) -> normalizeKey(k) }
        val viewId = (normalized["viewId"] as? String)?.trim() ?: return false
        if (viewId.isEmpty()) return false
        val type = (normalized["type"] as? String)?.lowercase()
        val command = (normalized["command"] as? String)?.uppercase()
        return type != null || command in VALID_COMMANDS
    }

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

    // -------------------------------------------------------------------------
    // JSON Helpers
    // -------------------------------------------------------------------------

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

    private fun listToWritableArray(list: List<*>): WritableNativeArray {
        val result = WritableNativeArray()
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
