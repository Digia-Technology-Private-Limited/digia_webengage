package com.digia.webengage.mapping

import android.util.Log
import com.digia.engage.InAppPayload
import com.digia.webengage.config.SuppressionMode
import com.digia.webengage.config.WebEngagePluginConfig

internal open class WebEngagePayloadMapper(
        private val config: WebEngagePluginConfig = WebEngagePluginConfig(),
) {

        private data class InAppContext(
                val campaignId: String,
                val screenId: String,
                val args: Map<String, Any?>,
                val variationId: String,
                val layoutId: String,
        )

        /**
         * Maps an in-app notification data map (from [InAppNotificationData.getData] + enriched
         * experimentId) to a list of [InAppPayload]s for Digia to render.
         *
         * Produces one nudge payload when `command` and `viewId` are present.
         */
        open fun map(data: Map<String, Any?>): List<InAppPayload> {
                val campaignId =
                        (data["experimentId"] as? String
                                        ?: data["id"] as? String ?: data["campaignId"] as? String)
                                ?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?: run {
                                        logDebug(
                                                "inapp_map_dropped reason=missing_campaign_id keys=${formatKeys(data)}"
                                        )
                                        return emptyList()
                                }

                val screenId =
                        (data["screenId"] as? String ?: data["screen_id"] as? String)?.trim()
                                ?.takeIf { it.isNotEmpty() }
                                ?: "*"

                val context =
                        InAppContext(
                                campaignId = campaignId,
                                screenId = screenId,
                                args = normalizeArgs(data["args"]),
                                variationId = (data["variationId"] as? String)?.trim().orEmpty(),
                                layoutId = (data["layoutId"] as? String)?.trim().orEmpty(),
                        )

                val forcedDialogComponentId = config.forcedDialogComponentId?.trim().orEmpty()
                val payloads = mutableListOf<InAppPayload>()

                val command = (data["command"] as? String)
                val viewId = (data["viewId"] as? String)?.trim().orEmpty()
                val type = (data["type"] as? String)?.trim().orEmpty()

                when {
                        type == "inline" -> {
                                val placementKey =
                                        (data["placementKey"] as? String)?.trim().orEmpty()
                                val inlinePayload =
                                        buildInlinePayload(context, placementKey, viewId)
                                if (inlinePayload != null) {
                                        payloads += inlinePayload
                                } else {
                                        val reason =
                                                if (placementKey.isBlank()) "missing_placementKey"
                                                else "missing_viewId"
                                        logDebug(
                                                "inapp_inline_not_mapped campaignId=$campaignId reason=$reason"
                                        )
                                }
                        }
                        !command.isNullOrBlank() && viewId.isNotEmpty() -> {
                                payloads += buildNudgePayload(context, command, viewId)
                        }
                        else -> {
                                if (config.suppressionMode == SuppressionMode.SUPPRESS_ALL &&
                                                forcedDialogComponentId.isNotBlank()
                                ) {
                                        payloads +=
                                                buildForcedDialogPayload(
                                                        campaignId,
                                                        forcedDialogComponentId
                                                )
                                } else {
                                        logDebug(
                                                "inapp_nudge_not_mapped campaignId=$campaignId reason=unsupported_or_incomplete_contract"
                                        )
                                }
                        }
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
         * [campaignId] — WECampaignData.campaignId [targetViewId] — WECampaignData.targetViewId
         * (the WE property ID / placement key) [customData] — content.customData extracted from
         * WECampaignData.toJSONString()
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
                        logDebug(
                                "inline_map_dropped campaignId=$campaignId reason=missing_targetViewId"
                        )
                        return null
                }
                val forcedDialogComponentId = config.forcedDialogComponentId?.trim().orEmpty()
                val screenId =
                        (customData["screenId"] as? String ?: customData["screen_id"] as? String)
                                ?.trim()
                                .orEmpty()
                val variationId = (metadata["variationId"] as? String)?.trim().orEmpty()
                val propertyId =
                        (metadata["propertyId"] as? String)?.trim().orEmpty().ifBlank {
                                targetViewId
                        }
                val componentId = (customData["componentId"] as? String)?.trim().orEmpty()
                if (componentId.isBlank()) {
                        if (config.suppressionMode == SuppressionMode.SUPPRESS_ALL &&
                                        forcedDialogComponentId.isNotBlank()
                        ) {
                                val payload =
                                        buildForcedDialogPayload(
                                                campaignId,
                                                forcedDialogComponentId,
                                                targetViewId
                                        )
                                logDebug(
                                        "inline_map_forced_dialog campaignId=$campaignId targetViewId=$targetViewId " +
                                                "componentId=$forcedDialogComponentId payloadId=${payload.id}",
                                )
                                return payload
                        }
                        logDebug(
                                "inline_map_dropped campaignId=$campaignId targetViewId=$targetViewId reason=missing_componentId",
                        )
                        return null
                }
                val args = normalizeArgs(customData["args"])
                val payload =
                        InAppPayload(
                                id = "$campaignId:$targetViewId",
                                content =
                                        mapOf(
                                                "type" to "inline",
                                                "screenId" to screenId,
                                                "placementKey" to targetViewId,
                                                "componentId" to componentId,
                                                "args" to args,
                                        ),
                                cepContext =
                                        buildMap {
                                                put("experimentId", campaignId)
                                                put("campaignId", campaignId)
                                                put("propertyId", propertyId)
                                                if (variationId.isNotBlank())
                                                        put("variationId", variationId)
                                        },
                        )
                logDebug(
                        "inline_map_result campaignId=$campaignId targetViewId=$targetViewId payloadId=${payload.id}",
                )
                return payload
        }

        private fun buildInlinePayload(
                context: InAppContext,
                placementKey: String,
                componentId: String,
        ): InAppPayload? {
                if (placementKey.isBlank() || componentId.isBlank()) return null
                return InAppPayload(
                        id = context.campaignId,
                        content =
                                mapOf(
                                        "type" to "inline",
                                        "screenId" to context.screenId,
                                        "placementKey" to placementKey,
                                        "componentId" to componentId,
                                        "args" to context.args,
                                ),
                        cepContext =
                                buildMap {
                                        put("experimentId", context.campaignId)
                                        put("campaignId", context.campaignId)
                                        if (context.variationId.isNotBlank())
                                                put("variationId", context.variationId)
                                        if (context.layoutId.isNotBlank())
                                                put("layoutId", context.layoutId)
                                },
                )
        }

        private fun buildNudgePayload(
                context: InAppContext,
                command: String,
                viewId: String,
        ): InAppPayload =
                InAppPayload(
                        id = context.campaignId,
                        content =
                                mapOf(
                                        "command" to command,
                                        "viewId" to viewId,
                                        "screenId" to context.screenId,
                                        "args" to context.args,
                                ),
                        cepContext =
                                buildMap {
                                        put("experimentId", context.campaignId)
                                        if (context.variationId.isNotBlank())
                                                put("variationId", context.variationId)
                                        if (context.layoutId.isNotBlank())
                                                put("layoutId", context.layoutId)
                                },
                )

        private fun buildForcedDialogPayload(
                campaignId: String,
                forcedDialogComponentId: String,
                targetViewId: String? = null,
        ): InAppPayload =
                InAppPayload(
                        id =
                                if (targetViewId == null) "$campaignId:forced_dialog"
                                else "$campaignId:$targetViewId:forced_dialog",
                        content =
                                mapOf(
                                        "command" to "SHOW_DIALOG",
                                        "viewId" to forcedDialogComponentId,
                                        "screenId" to "*",
                                        "args" to emptyMap<String, Any?>(),
                                ),
                        cepContext = mapOf("experimentId" to campaignId),
                )

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

        private fun formatKeys(map: Map<String, Any?>): String =
                map.keys.sorted().joinToString(prefix = "[", postfix = "]")

        companion object {
                private const val LOG_TAG = "DigiaWEMapper"
        }
}
