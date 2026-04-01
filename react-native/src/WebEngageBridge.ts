import WebEngage from 'webengage-react-native';

// ─── Abstract interface ───────────────────────────────────────────────────────

/** Raw in-app notification data as received from the WebEngage React Native SDK. */
export type WEInAppData = Record<string, unknown>;

/**
 * Abstraction over WebEngage SDK interactions.
 *
 * Isolates native SDK calls so the plugin + mapper can be unit-tested
 * without requiring a real WebEngage environment.
 */
export interface WebEngageBridge {
    /**
     * Registers in-app notification callbacks.
     *
     * [onInAppPrepared] is called with the raw campaign data map before the
     * native WebEngage UI is shown.
     *
     * [onInAppDismissed] is called with the same data map when the in-app is
     * dismissed. The bridge is responsible for correlating the campaign ID.
     *
     * NOTE: `webengage-react-native` does not expose a way to suppress the
     * native in-app rendering from JavaScript. The native `shouldRender(false)`
     * equivalent must be wired separately at the Android/iOS layer if needed.
     */
    registerCallbacks(callbacks: {
        onInAppPrepared: (data: WEInAppData) => void;
        onInAppDismissed: (campaignId: string) => void;
    }): void;

    /** Unregisters all previously registered callbacks. */
    unregisterCallbacks(): void;

    /** Forwards screen navigation to WebEngage analytics. */
    navigateScreen(name: string): void;

    /**
     * Tracks a system event with the given name, system data and event data.
     *
     * On React Native the WebEngage SDK exposes `trackEvent()` for all event
     * tracking. System data and event data are merged into a single attributes
     * map, matching the semantics of the Android `analytics().trackSystem()` call.
     */
    trackSystemEvent(
        eventName: string,
        systemData: Record<string, unknown>,
        eventData: Record<string, unknown>,
    ): void;

    /** Returns whether the underlying WebEngage SDK bridge is available. */
    isAvailable(): boolean;
}

// ─── Production implementation ────────────────────────────────────────────────

/**
 * Production [WebEngageBridge] backed by `webengage-react-native`.
 *
 * The `webengage-react-native` SDK does not expose `InAppNotificationData`
 * (the native object) to JavaScript, so rendering suppression must be
 * handled at the native layer separately.
 */
export class WebEngageSdkBridge implements WebEngageBridge {
    /**
     * Monotonically-increasing counter for generating stable per-session IDs.
     * The React Native SDK does not expose the underlying experiment ID in all
     * callback paths, so we synthesise one.
     */
    private _counter = 0;
    private _activeInAppId: string | null = null;

    registerCallbacks(callbacks: {
        onInAppPrepared: (data: WEInAppData) => void;
        onInAppDismissed: (campaignId: string) => void;
    }): void {
        WebEngage.setUpInAppCallbacks(
            // onInAppShown — not used
            null,
            // onInAppDismissed
            (_data: unknown) => {
                const id = this._activeInAppId;
                this._activeInAppId = null;
                if (id != null) callbacks.onInAppDismissed(id);
            },
            // onInAppClicked — not used
            null,
            // onInAppPrepared
            (data: unknown) => {
                if (data == null) return;
                this._counter++;
                const id = `we_inapp_${this._counter}`;
                this._activeInAppId = id;
                // Inject a stable experimentId so the mapper and delegate have a
                // correlatable campaign ID without requiring Digia backend support.
                const enriched: WEInAppData = {
                    ...(data as Record<string, unknown>),
                    experimentId: id,
                };
                callbacks.onInAppPrepared(enriched);
            },
        );
    }

    unregisterCallbacks(): void {
        // webengage-react-native has no explicit deregister; replace with no-ops.
        WebEngage.setUpInAppCallbacks(null, null, null, null);
        this._activeInAppId = null;
    }

    navigateScreen(name: string): void {
        WebEngage.screenNavigated(name);
    }

    trackSystemEvent(
        eventName: string,
        systemData: Record<string, unknown>,
        eventData: Record<string, unknown>,
    ): void {
        const attributes = { ...systemData, ...eventData };
        WebEngage.trackEvent(eventName, attributes);
    }

    isAvailable(): boolean {
        return true;
    }
}
