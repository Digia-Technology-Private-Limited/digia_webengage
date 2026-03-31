package com.digia.engage.webengage

enum class SuppressionMode {
    PASS_THROUGH,
    SUPPRESS_ALL,
    SUPPRESS_DIGIA_ONLY,
}

data class WebEngagePluginConfig(
    val suppressionMode: SuppressionMode = SuppressionMode.SUPPRESS_DIGIA_ONLY,
    val diagnosticsEnabled: Boolean = false,
    val forcedDialogComponentId: String? = null,
)

internal fun shouldSuppressInAppRendering(
    mode: SuppressionMode,
    isDigiaCampaign: Boolean,
): Boolean = when (mode) {
    SuppressionMode.PASS_THROUGH -> false
    SuppressionMode.SUPPRESS_ALL -> true
    SuppressionMode.SUPPRESS_DIGIA_ONLY -> isDigiaCampaign
}
