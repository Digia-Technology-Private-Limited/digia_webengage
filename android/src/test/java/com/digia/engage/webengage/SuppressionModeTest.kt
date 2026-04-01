package com.digia.engage.webengage

import com.digia.engage.webengage.config.SuppressionMode
import com.digia.engage.webengage.config.shouldSuppressInAppRendering
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SuppressionModeTest {

    @Test
    fun `pass through never suppresses`() {
        assertFalse(
                shouldSuppressInAppRendering(SuppressionMode.PASS_THROUGH, isDigiaCampaign = true)
        )
        assertFalse(
                shouldSuppressInAppRendering(SuppressionMode.PASS_THROUGH, isDigiaCampaign = false)
        )
    }

    @Test
    fun `suppress all always suppresses`() {
        assertTrue(
                shouldSuppressInAppRendering(SuppressionMode.SUPPRESS_ALL, isDigiaCampaign = true)
        )
        assertTrue(
                shouldSuppressInAppRendering(SuppressionMode.SUPPRESS_ALL, isDigiaCampaign = false)
        )
    }

    @Test
    fun `suppress digia only suppresses only digia campaigns`() {
        assertTrue(
                shouldSuppressInAppRendering(
                        SuppressionMode.SUPPRESS_DIGIA_ONLY,
                        isDigiaCampaign = true,
                ),
        )
        assertFalse(
                shouldSuppressInAppRendering(
                        SuppressionMode.SUPPRESS_DIGIA_ONLY,
                        isDigiaCampaign = false,
                ),
        )
    }
}
