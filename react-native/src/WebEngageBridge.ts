import WebEngagePlugin from 'react-native-webengage';
import { NativeModules, DeviceEventEmitter } from 'react-native';
import { parseContractFromMap, extractContractFromHtml } from './WebEngagePayloadMapper';

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

const EVENT_PREPARED = 'weDigiaInAppPrepared';
const EVENT_DISMISSED = 'weDigiaInAppDismissed';

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
export class WebEngageSdkBridge implements WebEngageBridge {
    private _weInstance: InstanceType<typeof WebEngagePlugin>;
    private _prepareSubscription: { remove(): void } | null = null;
    private _dismissSubscription: { remove(): void } | null = null;

    /**
     * @param weInstance - Optional existing WebEngagePlugin instance to reuse.
     *   Pass the same instance used for user/analytics calls to avoid
     *   registering duplicate native event listeners.
     */
    constructor(weInstance?: InstanceType<typeof WebEngagePlugin>) {
        this._weInstance = weInstance ?? new WebEngagePlugin();
    }

    registerCallbacks(callbacks: {
        onInAppPrepared: (data: WEInAppData) => void;
        onInAppDismissed: (campaignId: string) => void;
    }): void {
        const nativeModule = NativeModules.DigiaSuppressModule;
        console.log('[DigiaBridge] DigiaSuppressModule available:', !!nativeModule);
        if (!nativeModule) {
            // Native module not linked — fall back to react-native-webengage events
            // so the JS-only filter path still works (no native suppression, but
            // Digia campaigns are still forwarded).
            console.log('[DigiaBridge] Falling back to react-native-webengage callbacks');
            this._registerFallbackCallbacks(callbacks);
            return;
        }

        // Activate the native module's listener count tracking.
        nativeModule.install?.();

        // Use DeviceEventEmitter directly — on Android, NativeEventEmitter is a
        // thin wrapper over DeviceEventEmitter. Using it directly avoids any
        // wrapper-layer delivery issues.
        console.log('[DigiaBridge] Subscribing to', EVENT_PREPARED, 'via DeviceEventEmitter');

        this._prepareSubscription = DeviceEventEmitter.addListener(
            EVENT_PREPARED,
            (data: Record<string, unknown>) => {
                console.log('[DigiaBridge] Received', EVENT_PREPARED, JSON.stringify(data));
                if (data == null) return;
                callbacks.onInAppPrepared(data);
            },
        );

        this._dismissSubscription = DeviceEventEmitter.addListener(
            EVENT_DISMISSED,
            (data: Record<string, unknown>) => {
                console.log('[DigiaBridge] Received', EVENT_DISMISSED, JSON.stringify(data));
                const id = data?.experimentId;
                if (typeof id === 'string' && id.length > 0) {
                    callbacks.onInAppDismissed(id);
                }
            },
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
        const attributes = { ...systemData, ...eventData };
        this._weInstance.track(eventName, attributes);
    }

    isAvailable(): boolean {
        return !!NativeModules.DigiaSuppressModule;
    }

    // ── Fallback: JS-only filter (no native suppression) ─────────────────────
    // Used when the native module is not linked (e.g. bare JS tests, Expo Go).
    // Mirrors the previous behaviour: detect Digia contracts in onPrepare and
    // forward only those to the Digia delegate.

    private _fallbackCounter = 0;
    private _fallbackActiveId: string | null = null;

    private _registerFallbackCallbacks(callbacks: {
        onInAppPrepared: (data: WEInAppData) => void;
        onInAppDismissed: (campaignId: string) => void;
    }): void {
        this._dismissSubscription = this._weInstance.notification.onDismiss(
            (_data: unknown) => {
                const id = this._fallbackActiveId;
                this._fallbackActiveId = null;
                if (id != null) callbacks.onInAppDismissed(id);
            },
        );

        this._prepareSubscription = this._weInstance.notification.onPrepare(
            (data: unknown) => {
                if (data == null) return;
                const raw = data as Record<string, unknown>;
                if (!isDigiaCampaign(raw)) return;

                this._fallbackCounter++;
                const id = `we_inapp_${this._fallbackCounter}`;
                this._fallbackActiveId = id;
                callbacks.onInAppPrepared({ ...raw, experimentId: id });
            },
        );
    }
}

// ─── JS-side Digia detection (used only in the fallback path) ────────────────
// Kept private to this module; mirrors the logic in DigiaSuppressModule.kt / .m.

// ─── JS-side Digia detection (fallback path only) ───────────────────────────
// Uses the same parseContractFromMap + extractContractFromHtml from
// WebEngagePayloadMapper — identical to Flutter reusing
// WebEngagePayloadMapper._normalizeWithDigiaContract for detection.
// Includes full HTML attribute normalization (view-id→viewId etc.) and
// HTML unescaping, matching the Flutter and Android implementations exactly.

function isDigiaCampaign(data: Record<string, unknown>): boolean {
    return parseContractFromMap(data, 'top_level') != null ||
        extractContractFromHtml(data) != null;
}
