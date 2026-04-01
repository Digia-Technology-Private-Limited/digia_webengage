package com.digia.engage.webengage

import com.digia.engage.webengage.config.SuppressionMode
import com.digia.engage.webengage.config.WebEngagePluginConfig
import com.digia.engage.webengage.mapping.WebEngagePayloadMapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebEngagePayloadMapperTest {

    private val mapper =
            WebEngagePayloadMapper(
                    config = WebEngagePluginConfig(diagnosticsEnabled = false),
            )

    @Test
    fun `inapp map returns empty when campaign id missing`() {
        val result =
                mapper.map(
                        mapOf(
                                "command" to "SHOW_DIALOG",
                                "viewId" to "welcome_modal",
                        ),
                )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `inapp map returns empty when command missing`() {
        val result =
                mapper.map(
                        mapOf(
                                "experimentId" to "exp-1",
                                "screenId" to "home",
                        ),
                )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `inapp map ignores placement component pairs in inapp payload`() {
        val result =
                mapper.map(
                        mapOf(
                                "experimentId" to "exp-1",
                                "screenId" to "home",
                                "hero_slot" to "hero_component",
                        ),
                )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `inline map returns null when component id missing`() {
        val result =
                mapper.mapInline(
                        campaignId = "cmp-1",
                        targetViewId = "hero_slot",
                        customData = mapOf("screenId" to "home"),
                )

        assertNull(result)
    }

    @Test
    fun `inline map creates payload when required fields present`() {
        val result =
                mapper.mapInline(
                        campaignId = "cmp-1",
                        targetViewId = "hero_slot",
                        customData =
                                mapOf(
                                        "screenId" to "home",
                                        "componentId" to "hero_component",
                                        "args" to mapOf("title" to "hello"),
                                ),
                        metadata =
                                mapOf(
                                        "variationId" to "var-1",
                                        "propertyId" to "hero_slot",
                                ),
                )

        assertNotNull(result)
        assertEquals("cmp-1:hero_slot", result?.id)
        assertEquals("inline", result?.content?.get("type"))
        assertEquals("hero_slot", result?.content?.get("placementKey"))
        assertEquals("hero_component", result?.content?.get("componentId"))
        assertEquals("var-1", result?.cepContext?.get("variationId"))
        assertEquals("hero_slot", result?.cepContext?.get("propertyId"))
    }

    @Test
    fun `inapp map uses forced dialog in suppress all mode`() {
        val forcedMapper =
                WebEngagePayloadMapper(
                        config =
                                WebEngagePluginConfig(
                                        suppressionMode = SuppressionMode.SUPPRESS_ALL,
                                        diagnosticsEnabled = false,
                                        forcedDialogComponentId = "coupon_nudge-b6dByb",
                                ),
                )
        val result =
                forcedMapper.map(
                        mapOf(
                                "experimentId" to "exp-1",
                                "title" to "Any campaign payload",
                        ),
                )

        assertEquals(1, result.size)
        assertEquals("exp-1:forced_dialog", result.first().id)
        assertEquals("SHOW_DIALOG", result.first().content["command"])
        assertEquals("coupon_nudge-b6dByb", result.first().content["viewId"])
        assertEquals("*", result.first().content["screenId"])
    }

    @Test
    fun `inapp map creates show_inline payload from digia contract with type inline`() {
        val result =
                mapper.map(
                        mapOf(
                                "experimentId" to "exp-5",
                                "type" to "inline",
                                "viewId" to "hero_component",
                                "placementKey" to "hero_banner",
                                "screenId" to "home",
                                "args" to mapOf("title" to "Flash Sale"),
                                "variationId" to "var-5",
                        ),
                )

        assertEquals(1, result.size)
        val payload = result.first()
        assertEquals("exp-5", payload.id)
        assertEquals("inline", payload.content["type"])
        assertEquals("hero_banner", payload.content["placementKey"])
        assertEquals("hero_component", payload.content["componentId"])
        assertEquals("home", payload.content["screenId"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("Flash Sale", (payload.content["args"] as Map<String, Any?>)["title"])
        assertEquals("exp-5", payload.cepContext["experimentId"])
        assertEquals("var-5", payload.cepContext["variationId"])
    }

    @Test
    fun `inapp map drops show_inline when placementKey missing`() {
        val result =
                mapper.map(
                        mapOf(
                                "experimentId" to "exp-6",
                                "type" to "inline",
                                "viewId" to "hero_component",
                        ),
                )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `inline map uses forced dialog in suppress all mode`() {
        val forcedMapper =
                WebEngagePayloadMapper(
                        config =
                                WebEngagePluginConfig(
                                        suppressionMode = SuppressionMode.SUPPRESS_ALL,
                                        diagnosticsEnabled = false,
                                        forcedDialogComponentId = "coupon_nudge-b6dByb",
                                ),
                )
        val result =
                forcedMapper.mapInline(
                        campaignId = "cmp-1",
                        targetViewId = "hero_slot",
                        customData = emptyMap(),
                )

        assertNotNull(result)
        assertEquals("cmp-1:hero_slot:forced_dialog", result?.id)
        assertEquals("SHOW_DIALOG", result?.content?.get("command"))
        assertEquals("coupon_nudge-b6dByb", result?.content?.get("viewId"))
    }
}
