package com.digia.engage.webengage

import android.util.Log
import com.digia.engage.DiagnosticReport
import com.digia.engage.DigiaCEPDelegate
import com.digia.engage.DigiaCEPPlugin
import com.digia.engage.DigiaExperienceEvent
import com.digia.engage.InAppPayload
import java.lang.ref.WeakReference

/**
 * WebEngage CEP plugin implementation for Digia.
 *
 * Hooks into the WebEngage in-app notification lifecycle and the WE Personalization inline
 * campaign callback to forward Digia-configured payload maps to the active [DigiaCEPDelegate].
 *
 * Since the Digia backend is not yet available, [InAppPayload.id] and [InAppPayload.idKey] are
 * both derived from the WebEngage campaign / experiment ID so that the delegate can render
 * directly without a CampaignStore lookup.
 */
class WebEngagePlugin internal constructor(
    private val bridge: WebEngageBridge,
    private val mapper: WebEngagePayloadMapper,
    private val config: WebEngagePluginConfig,
) : DigiaCEPPlugin {

    /** Creates a production plugin backed by the WebEngage Android SDK. */
    constructor(
        config: WebEngagePluginConfig = WebEngagePluginConfig(),
    ) : this(
        bridge = WebEngageSdkBridge(config),
        mapper = WebEngagePayloadMapper(config),
        config = config,
    )

    override val identifier: String = "webengage"

    private var delegateRef: WeakReference<DigiaCEPDelegate>? = null
    private var callbacksRegistered: Boolean = false
    private val events = WebEngageEventBridge(bridge)

    override fun setup(delegate: DigiaCEPDelegate) {
        logDebug(
            "setup start suppressionMode=${config.suppressionMode} diagnostics=${config.diagnosticsEnabled}",
        )
        delegateRef?.clear()
        delegateRef = WeakReference(delegate)

        bridge.registerInAppListener(
            onPayload = ::dispatchMappedPayloads,
            onInvalidate = ::dispatchInvalidation,
        )
        bridge.registerInlineListener { campaignId, targetViewId, customData, metadata ->
            dispatchInlinePayload(campaignId, targetViewId, customData, metadata)
        }
        callbacksRegistered = true
        logDebug("setup complete delegateAttached=${delegateRef?.get() != null}")
    }

    override fun forwardScreen(name: String) {
        logDebug("forward_screen name=$name")
        events.forwardScreen(name)
    }

    override fun notifyEvent(event: DigiaExperienceEvent, payload: InAppPayload) {
        logDebug("notify_event type=${event.javaClass.simpleName} payloadId=${payload.id}")
        events.notifyEvent(event, payload)
    }

    override fun teardown() {
        logDebug("teardown start")
        bridge.unregisterInAppListener()
        bridge.unregisterInlineListener()
        callbacksRegistered = false
        delegateRef?.clear()
        delegateRef = null
        logDebug("teardown complete")
    }

    override fun healthCheck(): DiagnosticReport {
        val hasDelegate = delegateRef?.get() != null
        val isAvailable = bridge.isAvailable()
        val isHealthy = hasDelegate && isAvailable
        val report = DiagnosticReport(
            isHealthy = isHealthy,
            issue = if (isHealthy) null else "webengage plugin not fully wired",
            resolution = if (isHealthy) null
            else "call Digia.register(WebEngagePlugin()) after WebEngage SDK initializes",
            metadata = mapOf(
                "identifier" to identifier,
                "delegateAttached" to hasDelegate,
                "bridgeAvailable" to isAvailable,
                "callbacksRegistered" to callbacksRegistered,
                "suppressionMode" to config.suppressionMode.name,
                "diagnosticsEnabled" to config.diagnosticsEnabled,
            ),
        )
        logDebug("healthcheck healthy=$isHealthy metadata=${report.metadata}")
        return report
    }

    private fun dispatchMappedPayloads(rawPayload: Map<String, Any?>) {
        val activeDelegate = delegateRef?.get()
        if (activeDelegate == null) {
            logWarning("inapp_payload_dropped reason=delegate_unavailable")
            return
        }
        logDebug("inapp_payload_received keys=${rawPayload.keys.sorted()}")
        val payloads = mapper.map(rawPayload)
        if (payloads.isEmpty()) {
            logWarning("inapp_payload_dropped reason=mapper_returned_empty")
            return
        }
        payloads.forEach { payload ->
            logDebug("dispatch_inapp payloadId=${payload.id}")
            activeDelegate.onCampaignTriggered(payload)
        }
    }

    private fun dispatchInlinePayload(
        campaignId: String,
        targetViewId: String,
        customData: Map<String, Any?>,
        metadata: Map<String, Any?>,
    ) {
        val activeDelegate = delegateRef?.get()
        if (activeDelegate == null) {
            logWarning(
                "inline_payload_dropped campaignId=$campaignId targetViewId=$targetViewId reason=delegate_unavailable",
            )
            return
        }
        logDebug("inline_payload_received campaignId=$campaignId targetViewId=$targetViewId")
        val payload = mapper.mapInline(campaignId, targetViewId, customData, metadata) ?: return
        logDebug("dispatch_inline payloadId=${payload.id}")
        activeDelegate.onCampaignTriggered(payload)
    }

    private fun dispatchInvalidation(campaignId: String) {
        val activeDelegate = delegateRef?.get()
        if (activeDelegate == null) {
            logWarning("invalidation_dropped campaignId=$campaignId reason=delegate_unavailable")
            return
        }
        logDebug("dispatch_invalidation campaignId=$campaignId")
        activeDelegate.onCampaignInvalidated(campaignId)
    }

    private fun logDebug(message: String) {
        if (config.diagnosticsEnabled) {
            runCatching { Log.d(LOG_TAG, message) }
        }
    }

    private fun logWarning(message: String) {
        runCatching { Log.w(LOG_TAG, message) }
    }

    companion object {
        private const val LOG_TAG = "DigiaWEPlugin"

        internal fun createForTest(
            bridge: WebEngageBridge,
            mapper: WebEngagePayloadMapper = WebEngagePayloadMapper(),
            config: WebEngagePluginConfig = WebEngagePluginConfig(),
        ): WebEngagePlugin = WebEngagePlugin(
            bridge = bridge,
            mapper = mapper,
            config = config,
        )
    }
}
