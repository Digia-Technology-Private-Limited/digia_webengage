package com.digia.engage.webengage

import android.util.Log
import com.digia.engage.InAppPayload

internal open class WebEngagePayloadMapper(
    private val config: WebEngagePluginConfig = WebEngagePluginConfig(),
) {

    /**
     * Maps an in-app notification data map (from [InAppNotificationData.getData] + enriched
     * experimentId) to a list of [InAppPayload]s for Digia to render.
     *
     * Produces one nudge payload when `command` and `viewId` are present.
     */
    open fun map(data: Map<String, Any?>): List<InAppPayload> {
        val campaignId = (
            data["experimentId"] as? String
                ?: data["id"] as? String
                ?: data["campaignId"] as? String
            )
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: run {
                logDebug("inapp_map_dropped reason=missing_campaign_id keys=${formatKeys(data)}")
                return emptyList()
            }

        val screenId = (
            data["screenId"] as? String
                ?: data["screen_id"] as? String
            )
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "*"

        val forcedDialogComponentId = config.forcedDialogComponentId?.trim().orEmpty()
        if (config.suppressionMode == SuppressionMode.SUPPRESS_ALL && forcedDialogComponentId.isNotBlank()) {
            val payload = InAppPayload(
                id = "$campaignId:forced_dialog",
                content = mapOf(
                    "command" to "SHOW_DIALOG",
                    "viewId" to forcedDialogComponentId,
                    "screenId" to "*",
                    "args" to emptyMap<String, Any?>(),
                ),
                cepContext = mapOf("experimentId" to campaignId),
            )
            logDebug(
                "inapp_map_forced_dialog campaignId=$campaignId componentId=$forcedDialogComponentId payloadId=${payload.id}",
            )
            return listOf(payload)
        }

        val args = normalizeArgs(data["args"])
        val variationId = (data["variationId"] as? String)?.trim().orEmpty()
        val layoutId = (data["layoutId"] as? String)?.trim().orEmpty()
        val payloads = mutableListOf<InAppPayload>()

        val command = (data["command"] as? String)?.trim()?.uppercase()
        val viewId = (data["viewId"] as? String)?.trim()
        if (!command.isNullOrBlank() && !viewId.isNullOrBlank()) {
            payloads += InAppPayload(
                id = campaignId,
                content = mapOf(
                    "command" to command,
                    "viewId" to viewId,
                    "screenId" to screenId,
                    "args" to args,
                ),
                cepContext = buildMap {
                    put("experimentId", campaignId)
                    if (variationId.isNotBlank()) put("variationId", variationId)
                    if (layoutId.isNotBlank()) put("layoutId", layoutId)
                },
            )
        } else {
            val reason = when {
                command.isNullOrBlank() && viewId.isNullOrBlank() -> "missing_command_and_viewId"
                command.isNullOrBlank() -> "missing_command"
                else -> "missing_viewId"
            }
            logDebug("inapp_nudge_not_mapped campaignId=$campaignId reason=$reason")
        }

        if (payloads.isEmpty()) {
            logDebug("inapp_map_result campaignId=$campaignId mapped=0")
        } else {
            logDebug(
                "inapp_map_result campaignId=$campaignId mapped=${payloads.size} " +
                    "payloadIds=${payloads.joinToString(prefix = "[", postfix = "]") { it.id }}",
            )
        }
        return payloads
    }

    /**
     * Maps an inline campaign callback (from [WECampaignCallback.onCampaignPrepared]) to a
     * single [InAppPayload] for Digia's DigiaSlot to render.
     *
     * [campaignId] — WECampaignData.campaignId
     * [targetViewId] — WECampaignData.targetViewId (the WE property ID / placement key)
     * [customData] — content.customData extracted from WECampaignData.toJSONString()
     */
    open fun mapInline(
        campaignId: String,
        targetViewId: String,
        customData: Map<String, Any?>,
        metadata: Map<String, Any?> = emptyMap(),
    ): InAppPayload? {
        if (campaignId.isBlank()) {
            logDebug("inline_map_dropped reason=missing_campaign_id")
            return null
        }
        if (targetViewId.isBlank()) {
            logDebug("inline_map_dropped campaignId=$campaignId reason=missing_targetViewId")
            return null
        }
        val forcedDialogComponentId = config.forcedDialogComponentId?.trim().orEmpty()
        if (config.suppressionMode == SuppressionMode.SUPPRESS_ALL && forcedDialogComponentId.isNotBlank()) {
            val variationId = (metadata["variationId"] as? String)?.trim().orEmpty()
            val payload = InAppPayload(
                id = "$campaignId:$targetViewId:forced_dialog",
                content = mapOf(
                    "command" to "SHOW_DIALOG",
                    "viewId" to forcedDialogComponentId,
                    "screenId" to "*",
                    "args" to emptyMap<String, Any?>(),
                ),
                cepContext = buildMap {
                    put("experimentId", campaignId)
                    put("campaignId", campaignId)
                    put("propertyId", (metadata["propertyId"] as? String)?.trim().orEmpty().ifBlank { targetViewId })
                    if (variationId.isNotBlank()) put("variationId", variationId)
                },
            )
            logDebug(
                "inline_map_forced_dialog campaignId=$campaignId targetViewId=$targetViewId " +
                    "componentId=$forcedDialogComponentId payloadId=${payload.id}",
            )
            return payload
        }
        val screenId = (
            customData["screenId"] as? String
                ?: customData["screen_id"] as? String
            )
            ?.trim()
            .orEmpty()
        val variationId = (metadata["variationId"] as? String)?.trim().orEmpty()
        val propertyId = (metadata["propertyId"] as? String)?.trim().orEmpty().ifBlank { targetViewId }
        val componentId = (customData["componentId"] as? String)?.trim().orEmpty()
        if (componentId.isBlank()) {
            logDebug(
                "inline_map_dropped campaignId=$campaignId targetViewId=$targetViewId reason=missing_componentId",
            )
            return null
        }
        val args = normalizeArgs(customData["args"])
        val payload = InAppPayload(
            id = "$campaignId:$targetViewId",
            content = mapOf(
                "command" to "SHOW_INLINE",
                "screenId" to screenId,
                "placementKey" to targetViewId,
                "componentId" to componentId,
                "args" to args,
            ),
            cepContext = buildMap {
                put("experimentId", campaignId)
                put("campaignId", campaignId)
                put("propertyId", propertyId)
                if (variationId.isNotBlank()) put("variationId", variationId)
            },
        )
        logDebug(
            "inline_map_result campaignId=$campaignId targetViewId=$targetViewId payloadId=${payload.id}",
        )
        return payload
    }

    private fun normalizeArgs(raw: Any?): Map<String, Any?> {
        val map = raw as? Map<*, *> ?: return emptyMap()
        return buildMap {
            map.forEach { (key, value) ->
                val stringKey = key as? String ?: return@forEach
                put(stringKey, value)
            }
        }
    }

    private fun logDebug(message: String) {
        if (config.diagnosticsEnabled) {
            runCatching { Log.d(LOG_TAG, message) }
        }
    }

    private fun formatKeys(map: Map<String, Any?>): String = map.keys.sorted().joinToString(prefix = "[", postfix = "]")

    companion object {
        private const val LOG_TAG = "DigiaWEMapper"
    }
}
