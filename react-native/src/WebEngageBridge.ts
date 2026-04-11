// import { parseContractFromMap, extractContractFromHtml } from './WebEngagePayloadMapper';

// ─── Abstract interface ───────────────────────────────────────────────────────

/** Raw in-app notification data as received from the native suppress module. */
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
     * [onInAppPrepared] is called **only for Digia campaigns** — detected and
     * suppressed at the native layer by `DigiaSuppressModule` (Android) /
     * `WEDigiaSuppressModule` (iOS), which mirror Flutter's `DigiaSuppressPlugin`:
     *
     * ```
     *   Native: onInAppNotificationPrepared()
     *       ↓
     *   isDigiaCampaign?
     *       YES → setShouldRender(false) → emit "weDigiaInAppPrepared" → JS
     *       NO  → WebEngage renders natively (JS not involved)
     * ```
     *
     * [onInAppDismissed] is called with the campaign's experimentId when the
     * in-app is dismissed.
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

// ─── Event names emitted by the native DigiaSuppressModule ───────────────────



// ─── Production implementation ────────────────────────────────────────────────

/**
 * Production [WebEngageBridge] backed by the native `DigiaSuppressModule`
 * (Android) and `WEDigiaSuppressModule` (iOS).
 *
 * Suppression flow (identical to Flutter's `DigiaSuppressPlugin`):
 *   1. The native module intercepts every `onInAppNotificationPrepared` callback.
 *   2. It identifies the campaign type using the same `isDigiaCampaign` logic
 *      as the Android bridge and Flutter plugin.
 *   3. Digia campaigns: `setShouldRender(false)` / `*stopRendering = YES` is
 *      called natively, then the payload is emitted to JS as `weDigiaInAppPrepared`.
 *   4. Non-Digia campaigns: WebEngage renders them natively; JS is not involved.
 *
 * The `react-native-webengage` instance is kept only for screen navigation and
 * event tracking — its `onPrepare` / `onDismiss` callbacks are no longer used.
 */
import { NativeModules, DeviceEventEmitter } from 'react-native';
import WebEngagePlugin from 'react-native-webengage';

const { DigiaSuppressModule } = NativeModules;

export const EVENT_PREPARED = 'weDigiaInAppPrepared';
export const EVENT_DISMISSED = 'weDigiaInAppDismissed';

export class WebEngageSdkBridge implements WebEngageBridge {
    private _weInstance: InstanceType<typeof WebEngagePlugin>;
    private _prepareSubscription: { remove(): void } | null = null;
    private _dismissSubscription: { remove(): void } | null = null;

    constructor(weInstance?: InstanceType<typeof WebEngagePlugin>) {
        this._weInstance = weInstance ?? new WebEngagePlugin();
    }

    registerCallbacks(callbacks: {
        onInAppPrepared: (data: any) => void;
        onInAppDismissed: (campaignId: string) => void;
    }): void {
        if (!DigiaSuppressModule) {
            console.warn('[DigiaBridge] DigiaSuppressModule not available');
            return;
        }

        console.log('[DigiaBridge] Registering listeners via DeviceEventEmitter');

        // Use DeviceEventEmitter directly — bypasses NativeEventEmitter which
        // may not properly call native addListener in bridgeless mode.
        this._prepareSubscription = DeviceEventEmitter.addListener(
            EVENT_PREPARED,
            (data) => {
                console.log('[DigiaBridge] Received', EVENT_PREPARED, data);
                if (data) callbacks.onInAppPrepared(data);
            }
        );

        this._dismissSubscription = DeviceEventEmitter.addListener(
            EVENT_DISMISSED,
            (data) => {
                console.log('[DigiaBridge] Received', EVENT_DISMISSED, data);
                const id = data?.experimentId;
                if (typeof id === 'string') {
                    callbacks.onInAppDismissed(id);
                }
            }
        );
    }

    unregisterCallbacks(): void {
        this._prepareSubscription?.remove();
        this._dismissSubscription?.remove();
        this._prepareSubscription = null;
        this._dismissSubscription = null;
    }

    navigateScreen(name: string): void {
        this._weInstance.screen(name);
    }

    trackSystemEvent(
        eventName: string,
        systemData: Record<string, unknown>,
        eventData: Record<string, unknown>,
    ): void {
        if (DigiaSuppressModule?.trackSystemEvent) {
            DigiaSuppressModule.trackSystemEvent(eventName, systemData, eventData);
        } else {
            // Fallback: merge and dispatch via the JS WebEngage SDK.
            this._weInstance.track(eventName, { ...systemData, ...eventData });
        }
    }

    isAvailable(): boolean {
        return !!DigiaSuppressModule;
    }
}