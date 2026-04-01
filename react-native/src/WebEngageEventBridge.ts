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

    // ─── Inline (app_personalization_*) ──────────────────────────────────────

    private _dispatchInlineEvent(
        event: DigiaExperienceEvent,
        payload: InAppPayload,
    ): void {
        // WebEngage has no dismiss system event for inline campaigns
        if (event.type === 'dismissed') return;

        const eventName =
            event.type === 'impressed'
                ? 'app_personalization_view'
                : 'app_personalization_click';

        const experimentId =
            str(
                payload.cepContext['campaignId'] ??
                payload.cepContext['experimentId'],
            ) ?? payload.id.split(':')[0];

        const parts = payload.id.split(':');
        const propertyId =
            str(payload.cepContext['propertyId']) ??
            str(payload.content['placementKey']) ??
            (parts.length > 1 ? parts[parts.length - 1] : payload.id);

        const variationId =
            str(payload.cepContext['variationId']) ?? payload.id;

        const systemData: Record<string, unknown> = {
            experiment_id: experimentId,
            p_id: propertyId,
            id: variationId,
        };
        if (event.type === 'clicked') {
            const cta = event.elementId?.trim();
            if (cta) systemData['call_to_action'] = cta;
        }

        const args = payload.content['args'];
        const eventData: Record<string, unknown> =
            args != null && typeof args === 'object' && !Array.isArray(args)
                ? { ...(args as Record<string, unknown>) }
                : {};

        this._bridge.trackSystemEvent(eventName, systemData, eventData);
    }
}

// ─── Utilities ────────────────────────────────────────────────────────────────

function str(value: unknown): string | null {
    if (value == null) return null;
    const s = String(value).trim();
    return s.length > 0 ? s : null;
}
