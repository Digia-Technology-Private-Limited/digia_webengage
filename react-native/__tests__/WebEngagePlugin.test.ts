import { WebEngagePlugin } from '../src/WebEngagePlugin';
import { DigiaExperienceEvent } from '../src/types';
import type { DigiaCEPDelegate, InAppPayload } from '../src/types';
import type { WebEngageBridge, WEInAppData } from '../src/WebEngageBridge';
import { SuppressionMode } from '../src/config';

// ─── FakeBridge ───────────────────────────────────────────────────────────────

interface TrackedSystemEvent {
    eventName: string;
    systemData: Record<string, unknown>;
    eventData: Record<string, unknown>;
}

class FakeBridge implements WebEngageBridge {
    private _preparedCb: ((data: WEInAppData) => void) | null = null;
    private _dismissedCb: ((id: string) => void) | null = null;
    readonly trackedSystemEvents: TrackedSystemEvent[] = [];
    readonly navigatedScreens: string[] = [];
    private _available = true;

    registerCallbacks(callbacks: {
        onInAppPrepared: (data: WEInAppData) => void;
        onInAppDismissed: (campaignId: string) => void;
    }): void {
        this._preparedCb = callbacks.onInAppPrepared;
        this._dismissedCb = callbacks.onInAppDismissed;
    }

    unregisterCallbacks(): void {
        this._preparedCb = null;
        this._dismissedCb = null;
    }

    navigateScreen(name: string): void {
        this.navigatedScreens.push(name);
    }

    trackSystemEvent(
        eventName: string,
        systemData: Record<string, unknown>,
        eventData: Record<string, unknown>,
    ): void {
        this.trackedSystemEvents.push({ eventName, systemData, eventData });
    }

    isAvailable(): boolean {
        return this._available;
    }

    // ─── Test helpers ───────────────────────────────────────────────────────────

    emitInApp(data: WEInAppData): void {
        this._preparedCb?.(data);
    }

    emitDismissed(campaignId: string): void {
        this._dismissedCb?.(campaignId);
    }

    setUnavailable(): void {
        this._available = false;
    }
}

// ─── FakeDelegate ──────────────────────────────────────────────────────────────

class FakeDelegate implements DigiaCEPDelegate {
    readonly triggered: InAppPayload[] = [];
    readonly invalidated: string[] = [];

    onCampaignTriggered(payload: InAppPayload): void {
        this.triggered.push(payload);
    }

    onCampaignInvalidated(campaignId: string): void {
        this.invalidated.push(campaignId);
    }
}

// ─── Factory ──────────────────────────────────────────────────────────────────

function makePlugin(bridge: FakeBridge, configOverrides = {}) {
    return new WebEngagePlugin({ bridge, config: configOverrides });
}

// ─── Tests ────────────────────────────────────────────────────────────────────

describe('WebEngagePlugin — campaign dispatch', () => {
    it('dispatches mapped in-app payload to delegate', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        const delegate = new FakeDelegate();

        plugin.setup(delegate);
        bridge.emitInApp({
            experimentId: 'exp-1',
            command: 'SHOW_DIALOG',
            viewId: 'welcome_modal',
            screenId: 'home',
            args: { title: 'Hello' },
        });

        expect(delegate.triggered).toHaveLength(1);
        expect(delegate.triggered[0].id).toBe('exp-1');
        expect(delegate.triggered[0].content['command']).toBe('SHOW_DIALOG');
        expect(delegate.triggered[0].content['viewId']).toBe('welcome_modal');
    });

    it('dispatches invalidation to delegate', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        const delegate = new FakeDelegate();

        plugin.setup(delegate);
        bridge.emitDismissed('exp-7');

        expect(delegate.invalidated).toEqual(['exp-7']);
    });

    it('drops payloads when campaign id is missing', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        const delegate = new FakeDelegate();

        plugin.setup(delegate);
        bridge.emitInApp({ command: 'SHOW_DIALOG', viewId: 'modal' }); // no experimentId

        expect(delegate.triggered).toHaveLength(0);
    });

    it('ignores empty dismiss campaignId', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        const delegate = new FakeDelegate();

        plugin.setup(delegate);
        bridge.emitDismissed('');

        expect(delegate.invalidated).toHaveLength(0);
    });

    it('stops dispatching after teardown', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        const delegate = new FakeDelegate();

        plugin.setup(delegate);
        plugin.teardown();
        bridge.emitInApp({
            experimentId: 'exp-1',
            command: 'SHOW_DIALOG',
            viewId: 'welcome_modal',
        });
        bridge.emitDismissed('exp-1');

        expect(delegate.triggered).toHaveLength(0);
        expect(delegate.invalidated).toHaveLength(0);
    });

    it('dispatches fallback dialog for non-Digia payload in SUPPRESS_ALL mode', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge, {
            suppressionMode: SuppressionMode.SUPPRESS_ALL,
            forcedDialogComponentId: 'coupon_nudge-b6dByb',
        });
        const delegate = new FakeDelegate();

        plugin.setup(delegate);
        bridge.emitInApp({ experimentId: 'exp-2', title: 'plain webengage campaign' });

        expect(delegate.triggered).toHaveLength(1);
        expect(delegate.triggered[0].id).toBe('exp-2:forced_dialog');
        expect(delegate.triggered[0].content['command']).toBe('SHOW_DIALOG');
        expect(delegate.triggered[0].content['viewId']).toBe('coupon_nudge-b6dByb');
    });
});

// ─── In-app system events ─────────────────────────────────────────────────────

describe('WebEngagePlugin — notification_* system events', () => {
    const nudgePayload: InAppPayload = {
        id: 'exp-9',
        content: { command: 'SHOW_DIALOG', viewId: 'v1' },
        cepContext: { experimentId: 'exp-9', variationId: 'var-9' },
    };

    it('dispatches notification_view on Impressed', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.impressed(), nudgePayload);

        expect(bridge.trackedSystemEvents).toHaveLength(1);
        expect(bridge.trackedSystemEvents[0].eventName).toBe('notification_view');
        expect(bridge.trackedSystemEvents[0].systemData['experiment_id']).toBe('exp-9');
        expect(bridge.trackedSystemEvents[0].systemData['id']).toBe('var-9');
    });

    it('dispatches notification_click with call_to_action on Clicked', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.clicked('cta_apply'), nudgePayload);

        expect(bridge.trackedSystemEvents[0].eventName).toBe('notification_click');
        expect(bridge.trackedSystemEvents[0].systemData['call_to_action']).toBe('cta_apply');
    });

    it('omits call_to_action when elementId is absent on Clicked', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.clicked(), nudgePayload);

        expect(bridge.trackedSystemEvents[0].eventName).toBe('notification_click');
        expect(bridge.trackedSystemEvents[0].systemData['call_to_action']).toBeUndefined();
    });

    it('dispatches notification_close on Dismissed', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.dismissed(), nudgePayload);

        expect(bridge.trackedSystemEvents[0].eventName).toBe('notification_close');
    });

    it('dispatches all three in-app events in order', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.impressed(), nudgePayload);
        plugin.notifyEvent(DigiaExperienceEvent.clicked('cta_apply'), nudgePayload);
        plugin.notifyEvent(DigiaExperienceEvent.dismissed(), nudgePayload);

        const names = bridge.trackedSystemEvents.map((e) => e.eventName);
        expect(names).toEqual(['notification_view', 'notification_click', 'notification_close']);
    });
});

// ─── Inline system events ─────────────────────────────────────────────────────

describe('WebEngagePlugin — app_personalization_* system events', () => {
    const inlinePayload: InAppPayload = {
        id: 'cmp-1:hero_slot',
        content: {
            type: 'inline',
            placementKey: 'hero_slot',
            args: { foo: 'bar' },
        },
        cepContext: {
            campaignId: 'cmp-1',
            variationId: 'var-1',
            propertyId: 'hero_slot',
        },
    };

    it('dispatches app_personalization_view on Impressed', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.impressed(), inlinePayload);

        expect(bridge.trackedSystemEvents[0].eventName).toBe('app_personalization_view');
        expect(bridge.trackedSystemEvents[0].systemData['p_id']).toBe('hero_slot');
        expect(bridge.trackedSystemEvents[0].systemData['experiment_id']).toBe('cmp-1');
        expect(bridge.trackedSystemEvents[0].systemData['id']).toBe('var-1');
    });

    it('dispatches app_personalization_click with cta and args on Clicked', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.clicked('cta_inline'), inlinePayload);

        expect(bridge.trackedSystemEvents[0].eventName).toBe('app_personalization_click');
        expect(bridge.trackedSystemEvents[0].systemData['call_to_action']).toBe('cta_inline');
        expect(bridge.trackedSystemEvents[0].eventData['foo']).toBe('bar');
    });

    it('does not dispatch any event for inline Dismissed (no WE system event exists)', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.dismissed(), inlinePayload);

        expect(bridge.trackedSystemEvents).toHaveLength(0);
    });

    it('dispatches exactly 2 events for impressed + clicked (skips dismissed)', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.notifyEvent(DigiaExperienceEvent.impressed(), inlinePayload);
        plugin.notifyEvent(DigiaExperienceEvent.clicked('cta_inline'), inlinePayload);
        plugin.notifyEvent(DigiaExperienceEvent.dismissed(), inlinePayload);

        expect(bridge.trackedSystemEvents).toHaveLength(2);
        expect(bridge.trackedSystemEvents.map((e) => e.eventName)).toEqual([
            'app_personalization_view',
            'app_personalization_click',
        ]);
    });
});

// ─── Screen forwarding ────────────────────────────────────────────────────────

describe('WebEngagePlugin — screen forwarding', () => {
    it('forwards screen name to bridge', () => {
        const bridge = new FakeBridge();
        const plugin = makePlugin(bridge);
        plugin.setup(new FakeDelegate());

        plugin.forwardScreen('HomePage');

        expect(bridge.navigatedScreens).toEqual(['HomePage']);
    });
});

// ─── identifier ───────────────────────────────────────────────────────────────

describe('WebEngagePlugin — identifier', () => {
    it('returns "webengage"', () => {
        const plugin = makePlugin(new FakeBridge());
        expect(plugin.identifier).toBe('webengage');
    });
});
