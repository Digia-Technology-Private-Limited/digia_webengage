// ─── Payload ──────────────────────────────────────────────────────────────────

/** A resolved campaign payload ready for Digia to render. */
export interface InAppPayload {
    readonly id: string;
    readonly content: Readonly<Record<string, unknown>>;
    readonly cepContext: Readonly<Record<string, unknown>>;
}

// ─── Experience events ────────────────────────────────────────────────────────

/** The experience became visible to the user. */
export interface ExperienceImpressed {
    readonly type: 'impressed';
}

/** The user interacted with an actionable element. */
export interface ExperienceClicked {
    readonly type: 'clicked';
    /** Identifier of the element clicked. Null if the whole surface was the tap target. */
    readonly elementId?: string;
}

/** The experience was dismissed — by the user or programmatically. */
export interface ExperienceDismissed {
    readonly type: 'dismissed';
}

/** Discriminated union of all experience event types. */
export type DigiaExperienceEvent =
    | ExperienceImpressed
    | ExperienceClicked
    | ExperienceDismissed;

/** Convenience constructors matching the Kotlin/Dart sealed-class pattern. */
export const DigiaExperienceEvent = {
    impressed: (): ExperienceImpressed => ({ type: 'impressed' }),
    clicked: (elementId?: string): ExperienceClicked => ({
        type: 'clicked',
        elementId,
    }),
    dismissed: (): ExperienceDismissed => ({ type: 'dismissed' }),
} as const;

// ─── Delegate + Plugin ────────────────────────────────────────────────────────

/** Receives resolved campaign payloads from the CEP plugin. */
export interface DigiaCEPDelegate {
    onCampaignTriggered(payload: InAppPayload): void;
    onCampaignInvalidated(campaignId: string): void;
}

/** Digia CEP plugin interface — mirrors the Kotlin/Dart DigiaCEPPlugin contract. */
export interface DigiaCEPPlugin {
    readonly identifier: string;
    setup(delegate: DigiaCEPDelegate): void;
    notifyEvent(event: DigiaExperienceEvent, payload: InAppPayload): void;
    forwardScreen(name: string): void;
    teardown(): void;
}
