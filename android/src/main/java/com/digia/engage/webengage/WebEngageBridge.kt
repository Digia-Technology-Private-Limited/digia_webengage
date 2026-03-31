package com.digia.engage.webengage

import android.content.Context
import android.util.Log
import com.webengage.personalization.WEPersonalization
import com.webengage.personalization.callbacks.WECampaignCallback
import com.webengage.personalization.data.WECampaignData
import com.webengage.sdk.android.WebEngage
import com.webengage.sdk.android.actions.render.InAppNotificationData
import com.webengage.sdk.android.callbacks.InAppNotificationCallbacks
import org.json.JSONArray
import org.json.JSONObject

internal interface WebEngageBridge {
    fun registerInAppListener(
        onPayload: (Map<String, Any?>) -> Unit,
        onInvalidate: (String) -> Unit,
    )

    fun registerInlineListener(
        onInlineCampaign: (
            campaignId: String,
            targetViewId: String,
            customData: Map<String, Any?>,
            metadata: Map<String, Any?>,
        ) -> Unit,
    )

    fun trackSystemEvent(
        eventName: String,
        systemData: Map<String, Any?>,
        eventData: Map<String, Any?> = emptyMap(),
    )

    fun unregisterInAppListener()
    fun unregisterInlineListener()
    fun navigateScreen(name: String)
    fun isAvailable(): Boolean
}

internal class WebEngageSdkBridge(
    private val config: WebEngagePluginConfig = WebEngagePluginConfig(),
) : WebEngageBridge {

    private var inAppCallback: InAppNotificationCallbacks? = null
    private var campaignCallback: WECampaignCallback? = null

    override fun registerInAppListener(
        onPayload: (Map<String, Any?>) -> Unit,
        onInvalidate: (String) -> Unit,
    ) {
        inAppCallback = object : InAppNotificationCallbacks {
            override fun onInAppNotificationPrepared(
                context: Context,
                inAppData: InAppNotificationData,
            ): InAppNotificationData {
                val json: JSONObject = inAppData.getData() ?: run {
                    logWarning("in-app prepared with null data; skipping")
                    return inAppData
                }
                val originalMap = jsonObjectToMap(json)
                val map = normalizeWithDigiaContract(originalMap)
                val contractSource = map["digiaContractSource"] as? String
                if (!contractSource.isNullOrBlank()) {
                    logDebug(
                        "inapp_contract_extracted exp=${inAppData.experimentId.orEmpty()} source=$contractSource",
                    )
                }
                val isDigiaCampaign = isDigiaCampaign(map)
                val beforeRender = inAppData.shouldRender()
                val shouldSuppress =
                    shouldSuppressInAppRendering(config.suppressionMode, isDigiaCampaign)
                if (shouldSuppress) {
                    inAppData.setShouldRender(false)
                }
                val afterRender = inAppData.shouldRender()
                val experimentId = inAppData.experimentId.orEmpty()
                val layoutId = inAppData.layoutId.orEmpty()
                val variationId = inAppData.variationId.orEmpty()
                logDebug(
                    "inapp_prepared exp=$experimentId layout=$layoutId variation=$variationId " +
                        "mode=${config.suppressionMode} isDigia=$isDigiaCampaign " +
                        "shouldRender=$beforeRender->$afterRender keys=${formatKeys(map)}",
                )
                val shouldDispatchToDigia = isDigiaCampaign || config.suppressionMode == SuppressionMode.SUPPRESS_ALL
                if (!shouldDispatchToDigia) {
                    logDebug(
                        "inapp_prepared skip_digia_dispatch exp=$experimentId reason=non_digia_campaign",
                    )
                    return inAppData
                }
                val enriched = map.toMutableMap()
                enriched["experimentId"] = inAppData.experimentId
                enriched["layoutId"] = inAppData.layoutId
                enriched["variationId"] = inAppData.variationId
                onPayload(enriched)
                return inAppData
            }

            override fun onInAppNotificationShown(
                context: Context,
                inAppData: InAppNotificationData,
            ) {
                logDebug(
                    "inapp_shown exp=${inAppData.experimentId.orEmpty()} " +
                        "layout=${inAppData.layoutId.orEmpty()} variation=${inAppData.variationId.orEmpty()} " +
                        "shouldRender=${inAppData.shouldRender()}",
                )
            }

            override fun onInAppNotificationClicked(
                context: Context,
                inAppData: InAppNotificationData,
                actionId: String,
            ): Boolean {
                logDebug(
                    "inapp_clicked exp=${inAppData.experimentId.orEmpty()} " +
                        "actionId=$actionId shouldRender=${inAppData.shouldRender()}",
                )
                return false
            }

            override fun onInAppNotificationDismissed(
                context: Context,
                inAppData: InAppNotificationData,
            ) {
                val id = inAppData.experimentId
                logDebug(
                    "inapp_dismissed exp=${id.orEmpty()} " +
                        "layout=${inAppData.layoutId.orEmpty()} variation=${inAppData.variationId.orEmpty()}",
                )
                if (id != null) onInvalidate(id)
            }
        }
        logDebug("register_inapp_listener mode=${config.suppressionMode}")
        WebEngage.registerInAppNotificationCallback(inAppCallback)
    }

    override fun registerInlineListener(
        onInlineCampaign: (
            campaignId: String,
            targetViewId: String,
            customData: Map<String, Any?>,
            metadata: Map<String, Any?>,
        ) -> Unit,
    ) {
        campaignCallback = object : WECampaignCallback {
            override fun onCampaignPrepared(data: WECampaignData): WECampaignData? {
                val envelope = extractInlineEnvelope(data) ?: return data
                logDebug(
                    "inline_prepared campaignId=${envelope.campaignId} targetViewId=${envelope.targetViewId} " +
                        "customDataKeys=${formatKeys(envelope.customData)} metadataKeys=${formatKeys(envelope.metadata)}",
                )
                if (envelope.customData.isNotEmpty()) {
                    onInlineCampaign(
                        envelope.campaignId,
                        envelope.targetViewId,
                        envelope.customData,
                        envelope.metadata,
                    )
                } else {
                    logWarning(
                        "inline_prepared skipped campaignId=${envelope.campaignId} " +
                            "targetViewId=${envelope.targetViewId} reason=missing_custom_data",
                    )
                }
                return data
            }

            override fun onCampaignShown(data: WECampaignData) {
                logDebug(
                    "inline_shown campaignId=${data.campaignId.orEmpty()} targetViewId=${data.targetViewId.orEmpty()}",
                )
            }

            override fun onCampaignClicked(
                actionId: String,
                deepLink: String,
                data: WECampaignData,
            ): Boolean {
                logDebug(
                    "inline_clicked campaignId=${data.campaignId.orEmpty()} targetViewId=${data.targetViewId.orEmpty()} " +
                        "actionId=$actionId deepLink=$deepLink",
                )
                return false
            }

            override fun onCampaignException(
                campaignId: String?,
                targetViewId: String,
                error: Exception,
            ) {
                logWarning(
                    "inline_exception campaignId=${campaignId.orEmpty()} targetViewId=$targetViewId " +
                        "error=${error.message.orEmpty()}",
                )
            }
        }
        logDebug("register_inline_listener")
        WEPersonalization.get().registerWECampaignCallback(campaignCallback!!)
    }

    override fun trackSystemEvent(
        eventName: String,
        systemData: Map<String, Any?>,
        eventData: Map<String, Any?>,
    ) {
        val sanitizedSystemData = sanitizePayload(systemData)
        val sanitizedEventData = sanitizePayload(eventData)
        WebEngage.get().analytics().trackSystem(
            eventName,
            sanitizedSystemData,
            sanitizedEventData,
        )
    }

    override fun unregisterInAppListener() {
        logDebug("unregister_inapp_listener")
        WebEngage.unregisterInAppNotificationCallback(inAppCallback)
        inAppCallback = null
    }

    override fun unregisterInlineListener() {
        logDebug("unregister_inline_listener")
        campaignCallback?.let { WEPersonalization.get().unregisterWECampaignCallback(it) }
        campaignCallback = null
    }

    override fun navigateScreen(name: String) {
        logDebug("forward_screen name=$name")
        WebEngage.get().analytics().screenNavigated(name)
    }

    override fun isAvailable(): Boolean = WebEngage.isEngaged()

    // A campaign is Digia-controlled when the PM has set command or viewId key-values.
    private fun isDigiaCampaign(map: Map<String, Any?>): Boolean {
        val command = (map["command"] as? String)?.trim()?.uppercase()
        val viewId = (map["viewId"] as? String)?.trim()
        val isSupportedCommand = command == "SHOW_DIALOG" || command == "SHOW_BOTTOM_SHEET"
        return isSupportedCommand && !viewId.isNullOrBlank()
    }

    private fun extractInlineEnvelope(data: WECampaignData): InlineCampaignEnvelope? {
        return try {
            val root = JSONObject(data.toJSONString())
            val campaignId = data.campaignId?.takeIf { it.isNotBlank() }
                ?: root.optString("campaignId").takeIf { it.isNotBlank() }
                ?: return null
            val targetViewId = data.targetViewId?.takeIf { it.isNotBlank() }
                ?: root.optString("targetViewId").takeIf { it.isNotBlank() }
                ?: return null

            val customData = extractCustomData(root)
            val metadata = extractInlineMetadata(root, campaignId, targetViewId)
            InlineCampaignEnvelope(
                campaignId = campaignId,
                targetViewId = targetViewId,
                customData = customData,
                metadata = metadata,
            )
        } catch (error: Exception) {
            logWarning("inline_campaign_parse_failed error=${error.message.orEmpty()}")
            null
        }
    }

    private fun extractCustomData(root: JSONObject): Map<String, Any?> {
        return try {
            val content = root.optJSONObject("content") ?: return emptyMap()
            val customData = content.optJSONObject("customData") ?: return emptyMap()
            jsonObjectToMap(customData)
        } catch (error: Exception) {
            logWarning("inline_custom_data_parse_failed error=${error.message.orEmpty()}")
            emptyMap()
        }
    }

    private fun extractInlineMetadata(
        root: JSONObject,
        campaignId: String,
        targetViewId: String,
    ): Map<String, Any?> = buildMap {
        put("campaignId", campaignId)
        put("targetViewId", targetViewId)
        val propertyId = root.optString("propertyId").takeIf { it.isNotBlank() } ?: targetViewId
        put("propertyId", propertyId)
        root.optString("variationId")
            .takeIf { it.isNotBlank() }
            ?.let { put("variationId", it) }
        root.optString("parserType")
            .takeIf { it.isNotBlank() }
            ?.let { put("parserType", it) }
    }

    private fun jsonObjectToMap(json: JSONObject): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        val iterator = json.keys()
        while (iterator.hasNext()) {
            val key = iterator.next()
            map[key] = jsonValueToAny(json.opt(key))
        }
        return map
    }

    private fun jsonArrayToList(array: JSONArray): List<Any?> =
        buildList(array.length()) {
            for (index in 0 until array.length()) {
                add(jsonValueToAny(array.opt(index)))
            }
        }

    private fun jsonValueToAny(value: Any?): Any? = when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        JSONObject.NULL -> null
        else -> value
    }

    private fun logDebug(message: String) {
        if (config.diagnosticsEnabled) {
            runCatching { Log.d(LOG_TAG, message) }
        }
    }

    private fun logWarning(message: String) {
        runCatching { Log.w(LOG_TAG, message) }
    }

    private fun formatKeys(map: Map<String, Any?>): String =
        map.keys.sorted().joinToString(prefix = "[", postfix = "]")

    private fun sanitizePayload(payload: Map<String, Any?>): HashMap<String, Any> =
        HashMap<String, Any>().apply {
            payload.forEach { (key, value) ->
                when (value) {
                    null -> Unit
                    is Number, is String, is Boolean -> put(key, value)
                    else -> put(key, value.toString())
                }
            }
        }

    companion object {
        private const val LOG_TAG = "DigiaWEBridge"
    }
}

private data class InlineCampaignEnvelope(
    val campaignId: String,
    val targetViewId: String,
    val customData: Map<String, Any?>,
    val metadata: Map<String, Any?>,
)
