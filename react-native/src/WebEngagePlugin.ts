import type {
    DigiaCEPDelegate,
    DigiaCEPPlugin,
    DigiaExperienceEvent,
    InAppPayload,
} from './types';
import type { WebEngageBridge } from './WebEngageBridge';
import { WebEngageSdkBridge } from './WebEngageBridge';
import { WebEngagePayloadMapper } from './WebEngagePayloadMapper';
import { WebEngageEventBridge } from './WebEngageEventBridge';
import type { WebEngagePluginConfig } from './config';

/**
 * WebEngage CEP plugin implementation for Digia React Native SDK.
 *
 * Hooks into the WebEngage in-app notification lifecycle (via
 * `webengage-react-native`) to forward Digia-configured payloads to the
 * active {@link DigiaCEPDelegate}.
 *
 * ### Rendering suppression note
 * Unlike the Android native layer, React Native (like Flutter) cannot call
 * `setShouldRender(false)` on `InAppNotificationData` from JavaScript.
 * If you need to suppress WebEngage's native in-app UI, wire up a native
 * `InAppNotificationCallbacks` on Android / iOS and call `shouldRender(false)`
 * there. See WEBENGAGE_SETUP.md for the recommended approach.
 *
 * ### WE Personalization
 * The `webengage-react-native` SDK does not expose the WE Personalization
 * (inline campaign) API. Inline payloads are therefore not supported in this
 * package. If your app fetches inline data through a web-view or another
 * channel, call {@link notifyEvent} manually with a fabricated
 * {@link InAppPayload} whose `content.type` is `'inline'`.
 */
export class WebEngagePlugin implements DigiaCEPPlugin {
    readonly identifier = 'webengage';

    private readonly _bridge: WebEngageBridge;
    private readonly _mapper: WebEngagePayloadMapper;
    private readonly _events: WebEngageEventBridge;
    private _delegate: DigiaCEPDelegate | null = null;

    constructor(options: {
        /** Inject a custom bridge — useful for testing. */
        bridge?: WebEngageBridge;
        /** Configuration overrides (merged with {@link defaultConfig}). */
        config?: Partial<WebEngagePluginConfig>;
    } = {}) {
        this._bridge = options.bridge ?? new WebEngageSdkBridge();
        this._mapper = new WebEngagePayloadMapper(options.config);
        this._events = new WebEngageEventBridge(this._bridge);
    }

    /** Attaches the delegate and begins listening to WebEngage callbacks. */
    setup(delegate: DigiaCEPDelegate): void {
        this._delegate = delegate;
        this._bridge.registerCallbacks({
            onInAppPrepared: (data) => this._handleInAppPrepared(data),
            onInAppDismissed: (campaignId) => this._handleInAppDismissed(campaignId),
        });
    }

    /** Forwards a screen-navigation event to WebEngage analytics. */
    forwardScreen(name: string): void {
        this._events.forwardScreen(name);
    }

    /**
     * Dispatches a Digia experience event (view / click / dismiss) to the
     * corresponding WebEngage system-event API.
     */
    notifyEvent(event: DigiaExperienceEvent, payload: InAppPayload): void {
        this._events.notifyEvent(event, payload);
    }

    /** Detaches the delegate and stops listening to WebEngage callbacks. */
    teardown(): void {
        this._bridge.unregisterCallbacks();
        this._delegate = null;
    }

    // ─── Private handlers ───────────────────────────────────────────────────────

    private _handleInAppPrepared(data: Record<string, unknown>): void {
        const activeDelegate = this._delegate;
        if (!activeDelegate) return;
        const payloads = this._mapper.map(data);
        for (const payload of payloads) {
            activeDelegate.onCampaignTriggered(payload);
        }
    }

    private _handleInAppDismissed(campaignId: string): void {
        if (!campaignId) return;
        this._delegate?.onCampaignInvalidated(campaignId);
    }
}
