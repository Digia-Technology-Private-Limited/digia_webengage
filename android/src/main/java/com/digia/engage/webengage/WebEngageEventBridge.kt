package com.digia.engage.webengage

import com.digia.engage.DigiaExperienceEvent
import com.digia.engage.InAppPayload

internal class WebEngageEventBridge(private val bridge: WebEngageBridge) {

    fun forwardScreen(name: String) {
        bridge.navigateScreen(name)
    }

    fun notifyEvent(event: DigiaExperienceEvent, payload: InAppPayload) {
        val command = (payload.content["command"] as? String)?.trim()?.uppercase()
        if (command == "SHOW_INLINE") {
            notifyInlineEvent(event, payload)
            return
        }
        notifyInAppEvent(event, payload)
    }

    private fun notifyInAppEvent(event: DigiaExperienceEvent, payload: InAppPayload) {
        if (event !is DigiaExperienceEvent.Impressed) return
        val eventName = "notification_view"

        val experimentId = payload.cepContext.string("experimentId")
            ?: payload.cepContext.string("campaignId")
            ?: payload.id.substringBefore(':', payload.id)
        val variationId = payload.cepContext.string("variationId")
            ?: payload.id

        val systemData = mutableMapOf<String, Any?>(
            "experiment_id" to experimentId,
            "id" to variationId,
        )
        bridge.trackSystemEvent(
            eventName = eventName,
            systemData = systemData,
            eventData = emptyMap(),
        )
    }

    private fun notifyInlineEvent(event: DigiaExperienceEvent, payload: InAppPayload) {
        val eventName = when (event) {
            DigiaExperienceEvent.Impressed -> "app_personalization_view"
            is DigiaExperienceEvent.Clicked -> "app_personalization_click"
            DigiaExperienceEvent.Dismissed -> return
        }

        val experimentId = payload.cepContext.string("campaignId")
            ?: payload.cepContext.string("experimentId")
            ?: payload.id.substringBefore(':', payload.id)
        val propertyId = payload.cepContext.string("propertyId")
            ?: (payload.content["placementKey"] as? String)
            ?: payload.id.substringAfter(':', payload.id)
        val variationId = payload.cepContext.string("variationId")
            ?: payload.id

        val systemData = mutableMapOf<String, Any?>(
            "experiment_id" to experimentId,
            "p_id" to propertyId,
            "id" to variationId,
        )
        if (event is DigiaExperienceEvent.Clicked) {
            event.elementId?.trim()
                ?.takeIf { it.isNotBlank() }
                ?.let { systemData["actionId"] = it }
        }

        val eventData = (payload.content["args"] as? Map<*, *>)
            ?.entries
            ?.mapNotNull { (key, value) -> (key as? String)?.let { it to value } }
            ?.toMap()
            ?: emptyMap()

        bridge.trackSystemEvent(
            eventName = eventName,
            systemData = systemData,
            eventData = eventData,
        )
    }

    private fun Map<String, Any?>.string(key: String): String? =
        (this[key] as? String)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
}
