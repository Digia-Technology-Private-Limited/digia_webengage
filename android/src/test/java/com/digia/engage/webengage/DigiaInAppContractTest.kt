package com.digia.engage.webengage

import com.digia.engage.webengage.contract.normalizeWithDigiaContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigiaInAppContractTest {

    // ── Top-level key delivery ────────────────────────────────────────────────

    @Test
    fun `keeps top level contract when present`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "command" to "show_bottom_sheet",
                                "viewId" to "free_gift-mmKFGD",
                                "screenId" to "*",
                                "args" to mapOf("pr" to "ansd", "sad" to "1", "sda" to true),
                        ),
                )

        assertEquals("SHOW_BOTTOM_SHEET", normalized["command"])
        assertEquals("free_gift-mmKFGD", normalized["viewId"])
        assertEquals("*", normalized["screenId"])
        assertEquals("top_level", normalized["digiaContractSource"])
    }

    @Test
    fun `extracts top level inline contract with type field`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "type" to "inline",
                                "viewId" to "hero_component",
                                "placementKey" to "hero_banner",
                        ),
                )

        assertEquals("inline", normalized["type"])
        assertNull(normalized["command"])
        assertEquals("hero_component", normalized["viewId"])
        assertEquals("hero_banner", normalized["placementKey"])
        assertEquals("top_level", normalized["digiaContractSource"])
    }

    // ── Attribute-based <digia> tag delivery ─────────────────────────────────

    @Test
    fun `extracts dialog contract from attribute-based digia tag`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia command="SHOW_DIALOG" view-id="welcome_modal" screen-id="home"></digia>""",
                        ),
                )

        assertEquals("SHOW_DIALOG", normalized["command"])
        assertEquals("welcome_modal", normalized["viewId"])
        assertEquals("home", normalized["screenId"])
        assertEquals("html_digia_tag", normalized["digiaContractSource"])
    }

    @Test
    fun `extracts bottom sheet contract from attribute-based digia tag with args`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia command="SHOW_BOTTOM_SHEET" view-id="offer_1" args='{"title":"Special Offer","cta":{"text":"Shop Now"}}'></digia>""",
                        ),
                )

        assertEquals("SHOW_BOTTOM_SHEET", normalized["command"])
        assertEquals("offer_1", normalized["viewId"])
        assertEquals("html_digia_tag", normalized["digiaContractSource"])
        @Suppress("UNCHECKED_CAST") val args = normalized["args"] as Map<String, Any?>
        assertEquals("Special Offer", args["title"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("Shop Now", (args["cta"] as Map<String, Any?>)["text"])
    }

    @Test
    fun `extracts inline contract from attribute-based digia tag`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia type="inline" view-id="hero_component" placement="hero_banner" screen-id="home"></digia>""",
                        ),
                )

        assertEquals("inline", normalized["type"])
        assertNull(normalized["command"])
        assertEquals("hero_component", normalized["viewId"])
        assertEquals("hero_banner", normalized["placementKey"])
        assertEquals("home", normalized["screenId"])
        assertEquals("html_digia_tag", normalized["digiaContractSource"])
    }

    @Test
    fun `extracts contract from self-closing attribute-based digia tag`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf("html" to """<digia command="SHOW_DIALOG" view-id="promo" />"""),
                )

        assertEquals("SHOW_DIALOG", normalized["command"])
        assertEquals("promo", normalized["viewId"])
        assertEquals("html_digia_tag", normalized["digiaContractSource"])
    }

    @Test
    fun `extracts inline contract from multiline attribute-based digia tag`() {
        val html =
                """
            <digia
              type="inline"
              view-id="offer_1"
              placement="home_banner"
              args='{"title":"Special Offer","message":"Get 20% off"}'
            ></digia>
        """.trimIndent()

        val normalized = normalizeWithDigiaContract(mapOf("html" to html))

        assertEquals("inline", normalized["type"])
        assertNull(normalized["command"])
        assertEquals("offer_1", normalized["viewId"])
        assertEquals("home_banner", normalized["placementKey"])
        assertEquals("html_digia_tag", normalized["digiaContractSource"])
        @Suppress("UNCHECKED_CAST")
        assertEquals("Special Offer", (normalized["args"] as Map<String, Any?>)["title"])
    }

    @Test
    fun `extracts contract from digia tag embedded in nested html value`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "data" to
                                        mapOf(
                                                "body" to
                                                        """Some text <digia command="SHOW_DIALOG" view-id="promo_modal"></digia> end""",
                                        ),
                        ),
                )

        assertEquals("SHOW_DIALOG", normalized["command"])
        assertEquals("promo_modal", normalized["viewId"])
        assertEquals("html_digia_tag", normalized["digiaContractSource"])
    }

    // ── Validation / negative cases ───────────────────────────────────────────

    @Test
    fun `ignores digia tag that has no recognized attributes`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        "<digia><command>SHOW_DIALOG</command><viewId>x</viewId></digia>",
                        ),
                )

        assertNull(normalized["command"])
        assertNull(normalized["viewId"])
        assertNull(normalized["digiaContractSource"])
    }

    @Test
    fun `ignores inline contract without placement`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia type="inline" view-id="hero_component"></digia>"""
                        ),
                )

        assertNull(normalized["digiaContractSource"])
    }

    @Test
    fun `ignores inline contract without view-id`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia type="inline" placement="hero_banner"></digia>"""
                        ),
                )

        assertNull(normalized["digiaContractSource"])
    }

    @Test
    fun `ignores attribute-based inline contract without placement`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia type="inline" view-id="hero_component"></digia>"""
                        ),
                )

        assertNull(normalized["digiaContractSource"])
    }

    @Test
    fun `ignores attribute-based inline contract without view-id`() {
        val normalized =
                normalizeWithDigiaContract(
                        mapOf(
                                "html" to
                                        """<digia type="inline" placement="home_banner"></digia>"""
                        ),
                )

        assertNull(normalized["digiaContractSource"])
    }
}
