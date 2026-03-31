package com.digia.engage.webengage

import com.digia.engage.DigiaCEPDelegate
import com.digia.engage.DigiaExperienceEvent
import com.digia.engage.InAppPayload
import org.junit.Assert.assertEquals
import org.junit.Test
import java.lang.ref.WeakReference

class WebEngagePluginTest {

    @Test
    fun `dispatches mapped inapp payload to delegate`() {
        val bridge = FakeBridge()
        val config = WebEngagePluginConfig(diagnosticsEnabled = false)
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(config),
            config = config,
        )
        val delegate = FakeDelegate()

        plugin.setup(delegate)
        bridge.emitInApp(
            mapOf(
                "experimentId" to "exp-1",
                "command" to "SHOW_DIALOG",
                "viewId" to "welcome_modal",
                "screenId" to "home",
                "args" to mapOf("title" to "Hello"),
            ),
        )

        assertEquals(1, delegate.triggered.size)
        assertEquals("exp-1", delegate.triggered.first().id)
    }

    @Test
    fun `dispatches invalidation to delegate`() {
        val bridge = FakeBridge()
        val config = WebEngagePluginConfig(diagnosticsEnabled = false)
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(config),
            config = config,
        )
        val delegate = FakeDelegate()

        plugin.setup(delegate)
        bridge.emitInvalidate("exp-7")

        assertEquals(listOf("exp-7"), delegate.invalidated)
    }

    @Test
    fun `dispatches inline payload to delegate`() {
        val bridge = FakeBridge()
        val config = WebEngagePluginConfig(diagnosticsEnabled = false)
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(config),
            config = config,
        )
        val delegate = FakeDelegate()

        plugin.setup(delegate)
        bridge.emitInline(
            campaignId = "cmp-1",
            targetViewId = "hero_slot",
            customData = mapOf("componentId" to "hero_component"),
            metadata = mapOf("variationId" to "var-1", "propertyId" to "hero_slot"),
        )

        assertEquals(1, delegate.triggered.size)
        assertEquals("cmp-1:hero_slot", delegate.triggered.first().id)
    }

    @Test
    fun `drops payloads when delegate reference is unavailable`() {
        val bridge = FakeBridge()
        val config = WebEngagePluginConfig(diagnosticsEnabled = false)
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(config),
            config = config,
        )
        val delegate = FakeDelegate()

        plugin.setup(delegate)
        clearDelegateReference(plugin)
        bridge.emitInApp(
            mapOf(
                "experimentId" to "exp-1",
                "command" to "SHOW_DIALOG",
                "viewId" to "welcome_modal",
            ),
        )
        bridge.emitInline(
            campaignId = "cmp-1",
            targetViewId = "hero_slot",
            customData = mapOf("componentId" to "hero_component"),
            metadata = emptyMap(),
        )
        bridge.emitInvalidate("exp-1")

        assertEquals(emptyList<InAppPayload>(), delegate.triggered)
        assertEquals(emptyList<String>(), delegate.invalidated)
    }

    @Test
    fun `dispatches fallback dialog for non digia payload in suppress all mode`() {
        val bridge = FakeBridge()
        val config = WebEngagePluginConfig(
            suppressionMode = SuppressionMode.SUPPRESS_ALL,
            diagnosticsEnabled = false,
            forcedDialogComponentId = "coupon_nudge-b6dByb",
        )
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(config),
            config = config,
        )
        val delegate = FakeDelegate()

        plugin.setup(delegate)
        bridge.emitInApp(
            mapOf(
                "experimentId" to "exp-2",
                "title" to "plain webengage campaign",
            ),
        )

        assertEquals(1, delegate.triggered.size)
        assertEquals("SHOW_DIALOG", delegate.triggered.first().content["command"])
        assertEquals("coupon_nudge-b6dByb", delegate.triggered.first().content["viewId"])
    }

    @Test
    fun `notifyEvent maps only nudge impression to webengage notification system events`() {
        val bridge = FakeBridge()
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(WebEngagePluginConfig(diagnosticsEnabled = false)),
            config = WebEngagePluginConfig(diagnosticsEnabled = false),
        )
        plugin.setup(FakeDelegate())

        val payload = InAppPayload(
            id = "exp-9",
            content = mapOf("command" to "SHOW_DIALOG"),
            cepContext = mapOf(
                "experimentId" to "exp-9",
                "variationId" to "var-9",
            ),
        )

        plugin.notifyEvent(DigiaExperienceEvent.Impressed, payload)
        plugin.notifyEvent(DigiaExperienceEvent.Clicked("cta_apply"), payload)
        plugin.notifyEvent(DigiaExperienceEvent.Dismissed, payload)

        assertEquals(1, bridge.trackedSystemEvents.size)
        assertEquals("notification_view", bridge.trackedSystemEvents[0].eventName)
    }

    @Test
    fun `notifyEvent maps inline events to personalization system events`() {
        val bridge = FakeBridge()
        val plugin = WebEngagePlugin.createForTest(
            bridge = bridge,
            mapper = WebEngagePayloadMapper(WebEngagePluginConfig(diagnosticsEnabled = false)),
            config = WebEngagePluginConfig(diagnosticsEnabled = false),
        )
        plugin.setup(FakeDelegate())

        val payload = InAppPayload(
            id = "cmp-1:hero_slot",
            content = mapOf(
                "command" to "SHOW_INLINE",
                "placementKey" to "hero_slot",
                "args" to mapOf("foo" to "bar"),
            ),
            cepContext = mapOf(
                "campaignId" to "cmp-1",
                "variationId" to "var-1",
                "propertyId" to "hero_slot",
            ),
        )

        plugin.notifyEvent(DigiaExperienceEvent.Impressed, payload)
        plugin.notifyEvent(DigiaExperienceEvent.Clicked("cta_inline"), payload)
        plugin.notifyEvent(DigiaExperienceEvent.Dismissed, payload)

        assertEquals(2, bridge.trackedSystemEvents.size)
        assertEquals("app_personalization_view", bridge.trackedSystemEvents[0].eventName)
        assertEquals("app_personalization_click", bridge.trackedSystemEvents[1].eventName)
        assertEquals("hero_slot", bridge.trackedSystemEvents[0].systemData["p_id"])
        assertEquals("cta_inline", bridge.trackedSystemEvents[1].systemData["actionId"])
        assertEquals("bar", bridge.trackedSystemEvents[1].eventData["foo"])
    }

    private fun clearDelegateReference(plugin: WebEngagePlugin) {
        val field = WebEngagePlugin::class.java.getDeclaredField("delegateRef")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val weakRef = field.get(plugin) as? WeakReference<DigiaCEPDelegate>
        weakRef?.clear()
    }

    private class FakeDelegate : DigiaCEPDelegate {
        val triggered = mutableListOf<InAppPayload>()
        val invalidated = mutableListOf<String>()

        override fun onCampaignTriggered(payload: InAppPayload) {
            triggered += payload
        }

        override fun onCampaignInvalidated(campaignId: String) {
            invalidated += campaignId
        }
    }

    private class FakeBridge : WebEngageBridge {
        private var inAppCallback: ((Map<String, Any?>) -> Unit)? = null
        private var invalidateCallback: ((String) -> Unit)? = null
        private var inlineCallback: ((String, String, Map<String, Any?>, Map<String, Any?>) -> Unit)? = null
        val trackedSystemEvents = mutableListOf<TrackedSystemEvent>()

        override fun registerInAppListener(
            onPayload: (Map<String, Any?>) -> Unit,
            onInvalidate: (String) -> Unit,
        ) {
            inAppCallback = onPayload
            invalidateCallback = onInvalidate
        }

        override fun registerInlineListener(
            onInlineCampaign: (
                campaignId: String,
                targetViewId: String,
                customData: Map<String, Any?>,
                metadata: Map<String, Any?>,
            ) -> Unit,
        ) {
            inlineCallback = onInlineCampaign
        }

        override fun trackSystemEvent(
            eventName: String,
            systemData: Map<String, Any?>,
            eventData: Map<String, Any?>,
        ) {
            trackedSystemEvents += TrackedSystemEvent(
                eventName = eventName,
                systemData = systemData,
                eventData = eventData,
            )
        }

        override fun unregisterInAppListener() {
            inAppCallback = null
            invalidateCallback = null
        }

        override fun unregisterInlineListener() {
            inlineCallback = null
        }

        override fun navigateScreen(name: String) = Unit

        override fun isAvailable(): Boolean = true

        fun emitInApp(payload: Map<String, Any?>) {
            inAppCallback?.invoke(payload)
        }

        fun emitInvalidate(campaignId: String) {
            invalidateCallback?.invoke(campaignId)
        }

        fun emitInline(
            campaignId: String,
            targetViewId: String,
            customData: Map<String, Any?>,
            metadata: Map<String, Any?>,
        ) {
            inlineCallback?.invoke(campaignId, targetViewId, customData, metadata)
        }
    }

    private data class TrackedSystemEvent(
        val eventName: String,
        val systemData: Map<String, Any?>,
        val eventData: Map<String, Any?>,
    )
}
