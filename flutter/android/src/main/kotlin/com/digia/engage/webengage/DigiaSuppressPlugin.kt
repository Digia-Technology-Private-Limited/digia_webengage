package com.digia.engage.webengage

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
 * Flutter plugin that wires a native WebEngage [InAppNotificationCallbacks], suppresses WebEngage's
 * own in-app UI (`setShouldRender(false)`), and forwards campaign events to Dart via the
 * MethodChannel [CHANNEL].
 *
 * Channel protocol (Android → Dart):
 * - "onInAppPrepared" → Map with all keys from [InAppNotificationData.getData] plus
 * ```
 *                          `"experimentId"` and `"variationId"`.
 * ```
 * - "onInAppDismissed" → Map `{ "experimentId": String }`.
 *
 * On the Dart side, [WebEngageSdkBridge] listens on this channel when running on Android, replacing
 * the `webengage_flutter.setUpInAppCallbacks` approach that lacks the real experiment ID and cannot
 * suppress rendering.
 */
class DigiaSuppressPlugin : FlutterPlugin {

    private var channel: MethodChannel? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Whether to suppress WebEngage's own in-app renderer. Dart can toggle this at any time via a
     * "configure" MethodCall with argument `{"suppressRendering": true/false}`.
     */
    @Volatile private var suppressRendering: Boolean = false

    private var inAppCallback: InAppNotificationCallbacks? = null

    companion object {
        const val CHANNEL = "plugins.digia.tech/webengage_suppress"
    }

    override fun onAttachedToEngine(binding: FlutterPlugin.FlutterPluginBinding) {
        channel = MethodChannel(binding.binaryMessenger, CHANNEL)
        channel!!.setMethodCallHandler { call, result ->
            when (call.method) {
                "configure" -> {
                    suppressRendering = call.argument<Boolean>("suppressRendering") ?: false
                    result.success(null)
                }
                else -> result.notImplemented()
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
                        if (suppressRendering) {
                            inAppData.setShouldRender(false)
                        }

                        val payload: Map<String, Any?> = buildMap {
                            putAll(jsonObjectToMap(inAppData.getData()))
                            put("experimentId", inAppData.getExperimentId())
                            put("variationId", inAppData.getVariationId())
                        }

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
