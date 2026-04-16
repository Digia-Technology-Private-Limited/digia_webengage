// ─── Public API barrel ────────────────────────────────────────────────────────
//
// Consumers import everything from '@digia/engage-webengage'.

// Types (union types exported via `type`; DigiaExperienceEvent is exported as
// a value below so its type is automatically included)
export type {
    InAppPayload,
    ExperienceImpressed,
    ExperienceClicked,
    ExperienceDismissed,
    DigiaCEPDelegate,
    DigiaCEPPlugin,
} from './types';
// DigiaExperienceEvent: exported as a value (factory object) which also
// carries the union type for consumers.
export { DigiaExperienceEvent } from './types';

// Config
export { defaultConfig } from './config';
export type { WebEngagePluginConfig } from './config';

// Bridge (exported for custom bridge injection / testing)
export type { WebEngageBridge, WEInAppData } from './WebEngageBridge';
export { WebEngageSdkBridge } from './WebEngageBridge';

// Mapper (exported for unit testing and manual use)
export { WebEngagePayloadMapper } from './WebEngagePayloadMapper';

// Event bridge (exported for manual dispatch)
export { WebEngageEventBridge } from './WebEngageEventBridge';

// Main plugin — primary entry point
export { WebEngagePlugin } from './WebEngagePlugin';
