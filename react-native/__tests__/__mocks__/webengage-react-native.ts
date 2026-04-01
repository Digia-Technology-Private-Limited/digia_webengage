/**
 * Jest mock for `webengage-react-native`.
 *
 * Stores the last registered callbacks so tests can fire them directly via
 * `_triggerPrepared`, `_triggerDismissed`, etc.  All SDK methods are jest
 * mocks so call counts / arguments can be asserted.
 */

type PreparedCb = ((data: unknown) => void) | null;
type ShownCb = ((data: unknown) => void) | null;
type ClickedCb = ((data: unknown, actionId: string) => void) | null;
type DismissedCb = ((data: unknown) => void) | null;

const _callbacks = {
    shown: null as ShownCb,
    dismissed: null as DismissedCb,
    clicked: null as ClickedCb,
    prepared: null as PreparedCb,
};

const WebEngage = {
    // ─── SDK methods (jest mocks) ────────────────────────────────────────────

    setUpInAppCallbacks: jest.fn(
        (
            shown: ShownCb,
            dismissed: DismissedCb,
            clicked: ClickedCb,
            prepared: PreparedCb,
        ) => {
            _callbacks.shown = shown;
            _callbacks.dismissed = dismissed;
            _callbacks.clicked = clicked;
            _callbacks.prepared = prepared;
        },
    ),

    trackEvent: jest.fn((_name: string, _data?: Record<string, unknown>) => { }),

    screenNavigated: jest.fn((_name: string) => { }),

    // ─── Test helpers ────────────────────────────────────────────────────────

    /** Returns the stored callback state (read-only). */
    get _callbacks() {
        return _callbacks;
    },

    /** Fire the `onInAppPrepared` callback with the given data. */
    _triggerPrepared(data: Record<string, unknown>): void {
        _callbacks.prepared?.(data);
    },

    /** Fire the `onInAppDismissed` callback with the given data. */
    _triggerDismissed(data: Record<string, unknown>): void {
        _callbacks.dismissed?.(data);
    },

    /** Fire the `onInAppClicked` callback. */
    _triggerClicked(data: Record<string, unknown>, actionId = ''): void {
        _callbacks.clicked?.(data, actionId);
    },

    /** Fire the `onInAppShown` callback. */
    _triggerShown(data: Record<string, unknown>): void {
        _callbacks.shown?.(data);
    },

    /** Reset all mock state between tests. */
    _reset(): void {
        _callbacks.shown = null;
        _callbacks.dismissed = null;
        _callbacks.clicked = null;
        _callbacks.prepared = null;
        jest.clearAllMocks();
    },
};

export default WebEngage;
