package com.digia.engage.webengage.dispatch

import android.util.Log
import com.digia.engage.DigiaExperienceEvent
import com.digia.engage.InAppPayload
import com.digia.engage.webengage.bridge.WebEngageBridge
import com.digia.engage.webengage.cache.IInAppDataCache

/**
 * Dispatches [DigiaExperienceEvent]s to the corresponding WebEngage analytics system-event APIs.
 *
 * ## Strategy pattern Event dispatch is a dedicated responsibility, isolated here so that
 * [WebEngagePlugin] is **closed for modification** when new event types are added. Only this class
 * is updated — the plugin orchestrator is unchanged.
 *
 * Kotlin's sealed-class exhaustive `when` provides compile-time safety: adding a new
 * [DigiaExperienceEvent] subtype causes a compile error here rather than a silent runtime miss.
 *
 * ## In-app campaigns (SHOW_DIALOG / SHOW_BOTTOM_SHEET) Unlike MoEngage which exposes
 * `MoEInAppHelper.selfHandledShown/Clicked/Dismissed`, the WebEngage SDK has no equivalent
 * dedicated self-handled lifecycle API on [InAppNotificationData]. The documented approach for
 * self-handled campaigns is to call `WebEngage.get().analytics().trackSystem(eventName,
 * systemData)` with the exact experiment / variation IDs from the original [InAppNotificationData]
 * object.
 *
 * The [IInAppDataCache] is the WebEngage equivalent of MoEngage's [ICampaignCache]: it stores the
 * original [InAppNotificationData] populated in [onInAppNotificationPrepared], so we can resolve
 * `experimentId` and `variationId` precisely from the SDK object rather than re-parsing from the
 * mapped payload.
 *
 * ## Inline campaigns (SHOW_INLINE) WE Personalization has no self-handled lifecycle APIs either;
 * system-event tracking via `trackSystem` is used for `app_personalization_view` /
 * `app_personalization_click` as well.
 *
 * ## Dependencies
 * - [WebEngageBridge]: underlying analytics track + screen navigation.
 * - [IInAppDataCache]: resolves cached [InAppNotificationData] required for in-app dispatch, and
 * evicts entries post-dismiss.
 */
internal class WebEngageEventDispatcher(
        private val bridge: WebEngageBridge,
        private val cache: IInAppDataCache,
) {
        /**
         * Resolves the campaign type from [payload] and forwards [event] to the appropriate
         * WebEngage analytics track method.
         *
         * Routing is driven by the `type` field in [payload.content]:
         * - `inline` → `app_personalization_*` system events (WE Personalization API).
         * - Other → `notification_*` system events (WE in-app API).
         *
         * The [IInAppDataCache] is read when available to obtain the exact `experimentId` and
         * `variationId` from the original [InAppNotificationData] object. When the cache entry is
         * absent (tests, or a race-condition eviction) the values fall back to [payload.cepContext]
         * so that system events are always emitted.
         */
        fun dispatch(event: DigiaExperienceEvent, payload: InAppPayload): Boolean {
                val type = payload.content["type"] as? String
                return if (type == "inline") {
                        dispatchInlineEvent(event, payload)
                        true
                } else {
                        dispatchInAppEvent(event, payload)
                }
        }

        fun forwardScreen(name: String) {
                bridge.navigateScreen(name)
        }

        // ── In-app (SHOW_DIALOG / SHOW_BOTTOM_SHEET)
        // ────────────────────────────────────────────────

        private fun dispatchInAppEvent(
                event: DigiaExperienceEvent,
                payload: InAppPayload
        ): Boolean {
                val experimentId =
                        payload.cepContext.string("experimentId")
                                ?: payload.cepContext.string("campaignId")
                                        ?: payload.id.substringBefore(':', payload.id)

                // Primary source: cached InAppNotificationData populated in
                // onInAppNotificationPrepared.
                // Fallback: cepContext (used in tests and if cache was evicted before event fires).
                val cachedData = cache.get(experimentId)
                val weExperimentId =
                        cachedData?.experimentId?.takeIf { it.isNotBlank() } ?: experimentId
                val weVariationId =
                        cachedData?.variationId?.takeIf { it.isNotBlank() }
                                ?: payload.cepContext.string("variationId") ?: payload.id

                val eventName =
                        when (event) {
                                DigiaExperienceEvent.Impressed -> "notification_view"
                                is DigiaExperienceEvent.Clicked -> "notification_click"
                                // WE SDK uses "notification_close" internally
                                // (WebEngageConstant.NOTIFICATION_CLOSE
                                // → EventName.NOTIFICATION_CLOSE). "notification_dismiss" is not a
                                // WE system
                                // event.
                                DigiaExperienceEvent.Dismissed -> "notification_close"
                        }

                val systemData =
                        mutableMapOf<String, Any?>(
                                "experiment_id" to weExperimentId,
                                // WebEngageConstant.NOTIFICATION_ID = "id" = variationId
                                "id" to weVariationId,
                        )
                if (event is DigiaExperienceEvent.Clicked) {
                        event.elementId
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                // WebEngageConstant.CTA_ID = "call_to_action" (not "actionId")
                                ?.let { systemData["call_to_action"] = it }
                }

                bridge.trackSystemEvent(
                        eventName = eventName,
                        systemData = systemData,
                        eventData = emptyMap(),
                )

                if (event is DigiaExperienceEvent.Dismissed) {
                        // Campaign lifecycle complete — evict to free memory, mirroring MoEngage's
                        // cache.remove().
                        cache.remove(experimentId)
                        runCatching {
                                Log.v(
                                        TAG,
                                        "dispatched: $eventName — experimentId=$experimentId (evicted from cache)"
                                )
                        }
                } else {
                        runCatching {
                                Log.v(TAG, "dispatched: $eventName — experimentId=$experimentId")
                        }
                }

                return true
        }

        // ── Inline (SHOW_INLINE) ─────────────────────────────────────────────────────

        private fun dispatchInlineEvent(event: DigiaExperienceEvent, payload: InAppPayload) {
                val eventName =
                        when (event) {
                                DigiaExperienceEvent.Impressed -> "app_personalization_view"
                                is DigiaExperienceEvent.Clicked -> "app_personalization_click"
                                DigiaExperienceEvent.Dismissed ->
                                        return // WE has no inline dismiss system event
                        }

                val experimentId =
                        payload.cepContext.string("campaignId")
                                ?: payload.cepContext.string("experimentId")
                                        ?: payload.id.substringBefore(':', payload.id)
                val propertyId =
                        payload.cepContext.string("propertyId")
                                ?: (payload.content["placementKey"] as? String)
                                        ?: payload.id.substringAfter(':', payload.id)
                val variationId = payload.cepContext.string("variationId") ?: payload.id

                val systemData =
                        mutableMapOf<String, Any?>(
                                "experiment_id" to experimentId,
                                "p_id" to propertyId,
                                "id" to variationId,
                        )
                if (event is DigiaExperienceEvent.Clicked) {
                        event.elementId
                                ?.trim()
                                ?.takeIf { it.isNotBlank() }
                                // WebEngageConstant.CTA_ID = "call_to_action"
                                ?.let { systemData["call_to_action"] = it }
                }

                val eventData =
                        (payload.content["args"] as? Map<*, *>)
                                ?.entries
                                ?.mapNotNull { (key, value) ->
                                        (key as? String)?.let { it to value }
                                }
                                ?.toMap()
                                ?: emptyMap()

                bridge.trackSystemEvent(
                        eventName = eventName,
                        systemData = systemData,
                        eventData = eventData,
                )
                runCatching {
                        Log.v(
                                TAG,
                                "dispatched: $eventName — experimentId=$experimentId propertyId=$propertyId"
                        )
                }
        }

        private fun Map<String, Any?>.string(key: String): String? =
                (this[key] as? String)?.trim()?.takeIf { it.isNotBlank() }

        companion object {
                private const val TAG = "WebEngageEventDispatcher"
        }
}
