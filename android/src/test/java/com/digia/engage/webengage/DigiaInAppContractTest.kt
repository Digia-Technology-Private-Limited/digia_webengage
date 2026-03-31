package com.digia.engage.webengage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DigiaInAppContractTest {

    @Test
    fun `keeps top level contract when present`() {
        val normalized = normalizeWithDigiaContract(
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
    fun `ignores non script and non top level payloads`() {
        val normalized = normalizeWithDigiaContract(
            mapOf(
                "actions" to listOf(mapOf("actionLink" to "digia://inapp?command=SHOW_DIALOG&viewId=x")),
                "html" to "<digia><command>SHOW_DIALOG</command><viewId>x</viewId></digia>",
            ),
        )

        assertNull(normalized["command"])
        assertNull(normalized["viewId"])
        assertNull(normalized["digiaContractSource"])
    }
}
