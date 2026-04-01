/** Controls which in-app campaigns have their WebEngage native UI suppressed. */
export enum SuppressionMode {
    /** Never suppress — WebEngage renders natively AND Digia also triggers. */
    PASS_THROUGH = 'PASS_THROUGH',
    /** Suppress all in-app native rendering; Digia handles everything. */
    SUPPRESS_ALL = 'SUPPRESS_ALL',
    /**
     * Suppress only campaigns that carry a Digia contract (`type`/`command` key
     * or embedded `<digia>` tag). Non-Digia campaigns render normally.
     *
     * NOTE: React Native — like Flutter — cannot call `setShouldRender(false)` on
     * `InAppNotificationData` from JS. Suppression is advisory only; the native
     * WebEngage UI will still appear unless the host app also registers a native
     * `InAppNotificationCallbacks` that performs the actual suppression.
     *
     * @see WEBENGAGE_SETUP.md for the recommended per-platform wiring.
     */
    SUPPRESS_DIGIA_ONLY = 'SUPPRESS_DIGIA_ONLY',
}

/** Configuration for the WebEngage Digia CEP plugin. */
export interface WebEngagePluginConfig {
    /**
     * Controls how WebEngage's native in-app rendering interacts with Digia.
     * Defaults to `SUPPRESS_DIGIA_ONLY`.
     */
    readonly suppressionMode: SuppressionMode;
    /** When true, verbose diagnostic logs are emitted. Defaults to false. */
    readonly diagnosticsEnabled: boolean;
    /**
     * Component ID to use as a fallback dialog when a non-Digia campaign is
     * received in `SUPPRESS_ALL` mode.
     */
    readonly forcedDialogComponentId?: string;
}

export const defaultConfig: WebEngagePluginConfig = {
    suppressionMode: SuppressionMode.SUPPRESS_DIGIA_ONLY,
    diagnosticsEnabled: false,
};

export function shouldSuppressInAppRendering(
    mode: SuppressionMode,
    isDigiaCampaign: boolean,
): boolean {
    switch (mode) {
        case SuppressionMode.PASS_THROUGH:
            return false;
        case SuppressionMode.SUPPRESS_ALL:
            return true;
        case SuppressionMode.SUPPRESS_DIGIA_ONLY:
            return isDigiaCampaign;
    }
}
