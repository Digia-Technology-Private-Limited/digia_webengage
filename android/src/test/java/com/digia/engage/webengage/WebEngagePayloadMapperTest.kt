package com.digia.engage.webengage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class WebEngagePayloadMapperTest {

    private val mapper = WebEngagePayloadMapper(
        config = WebEngagePluginConfig(diagnosticsEnabled = false),
    )

    @Test
    fun `inapp map returns empty when campaign id missing`() {
        val result = mapper.map(
            mapOf(
                "command" to "SHOW_DIALOG",
                "viewId" to "welcome_modal",
            ),
        )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `inapp map returns empty when command missing`() {
        val result = mapper.map(
            mapOf(
                "experimentId" to "exp-1",
                "screenId" to "home",
            ),
        )

        assertEquals(emptyList<Any>(), result)
    }

    @Test
    fun `inapp map ignores placement component pairs in inapp payload`() {
        val result = mapper.map(
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
        val result = mapper.mapInline(
            campaignId = "cmp-1",
            targetViewId = "hero_slot",
            customData = mapOf("screenId" to "home"),
        )

        assertNull(result)
    }

    @Test
    fun `inline map creates payload when required fields present`() {
        val result = mapper.mapInline(
            campaignId = "cmp-1",
            targetViewId = "hero_slot",
            customData = mapOf(
                "screenId" to "home",
                "componentId" to "hero_component",
                "args" to mapOf("title" to "hello"),
            ),
            metadata = mapOf(
                "variationId" to "var-1",
                "propertyId" to "hero_slot",
            ),
        )

        assertNotNull(result)
        assertEquals("cmp-1:hero_slot", result?.id)
        assertEquals("SHOW_INLINE", result?.content?.get("command"))
        assertEquals("hero_slot", result?.content?.get("placementKey"))
        assertEquals("hero_component", result?.content?.get("componentId"))
        assertEquals("var-1", result?.cepContext?.get("variationId"))
        assertEquals("hero_slot", result?.cepContext?.get("propertyId"))
    }

    @Test
    fun `inapp map uses forced dialog in suppress all mode`() {
        val forcedMapper = WebEngagePayloadMapper(
            config = WebEngagePluginConfig(
                suppressionMode = SuppressionMode.SUPPRESS_ALL,
                diagnosticsEnabled = false,
                forcedDialogComponentId = "coupon_nudge-b6dByb",
            ),
        )
        val result = forcedMapper.map(
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
    fun `inline map uses forced dialog in suppress all mode`() {
        val forcedMapper = WebEngagePayloadMapper(
            config = WebEngagePluginConfig(
                suppressionMode = SuppressionMode.SUPPRESS_ALL,
                diagnosticsEnabled = false,
                forcedDialogComponentId = "coupon_nudge-b6dByb",
            ),
        )
        val result = forcedMapper.mapInline(
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
