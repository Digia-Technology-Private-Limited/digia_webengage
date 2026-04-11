import type { DigiaExperienceEvent, InAppPayload } from './types';
import type { WebEngageBridge } from './WebEngageBridge';

/**
 * Translates Digia lifecycle experience events into WebEngage analytics
 * system-event calls.
 *
 * Routing is driven by `payload.content['type']`:
 * - `'inline'` → `app_personalization_*` system events (WE Personalization).
 * - Otherwise  → `notification_*`        system events (WE in-app).
 *
 * Mirrors the Flutter `WebEngageEventBridge` and Android
 * `WebEngageEventDispatcher` exactly.
 */
export class WebEngageEventBridge {
    constructor(private readonly _bridge: WebEngageBridge) { }

    /** Forwards a screen name to WebEngage analytics. */
    forwardScreen(name: string): void {
        this._bridge.navigateScreen(name);
    }

    /**
     * Dispatches a Digia experience event to the corresponding WebEngage
     * analytics system-event API.
     */
    notifyEvent(event: DigiaExperienceEvent, payload: InAppPayload): void {
        if (payload.content['type'] === 'inline') {
            this._dispatchInlineEvent(event, payload);
        } else {
            this._dispatchInAppEvent(event, payload);
        }
    }

    // ─── In-app (notification_*) ─────────────────────────────────────────────

    private _dispatchInAppEvent(
        event: DigiaExperienceEvent,
        payload: InAppPayload,
    ): void {
        let eventName: string;
        if (event.type === 'impressed') {
            eventName = 'notification_view';
        } else if (event.type === 'dismissed') {
            eventName = 'notification_close';
        } else if (event.type === 'clicked') {
            eventName = 'notification_click';
        } else {
            return;
        }


        const experimentId =
            str(
                payload.cepContext['experimentId'] ??
                payload.cepContext['campaignId'],
            ) ?? payload.id.split(':')[0];
        const variationId =
            str(payload.cepContext['variationId']) ?? payload.id;

        const systemData: Record<string, unknown> = {
            experiment_id: experimentId,
            id: variationId,
        };
        if (event.type === 'clicked') {
            const cta = event.elementId?.trim();
            if (cta) systemData['call_to_action'] = cta;
        }

        this._bridge.trackSystemEvent(eventName, systemData, {});
    }

    // ─── Inline ──────────────────────────────────────────────────────────────

    private _dispatchInlineEvent(
        event: DigiaExperienceEvent,
        payload: InAppPayload,
    ): void {
        if (event.type === 'dismissed') return;

        const experimentId =
            str(
                payload.cepContext['experimentId'] ??
                payload.cepContext['campaignId'],
            ) ?? payload.id.split(':')[0];
        const variationId =
            str(payload.cepContext['variationId']) ?? payload.id;

        const systemData: Record<string, unknown> = {
            experiment_id: experimentId,
            id: variationId,
        };

        if (event.type === 'impressed') {
            this._bridge.trackSystemEvent('notification_view', systemData, {});
            this._bridge.trackSystemEvent('notification_close', systemData, {});
        } else if (event.type === 'clicked') {
            const cta = event.elementId?.trim();
            if (cta) systemData['call_to_action'] = cta;
            this._bridge.trackSystemEvent('notification_click', systemData, {});
        }
    }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

function str(value: unknown): string | null {
    if (value == null) return null;
    const s = String(value).trim();
    return s.length > 0 ? s : null;
}
