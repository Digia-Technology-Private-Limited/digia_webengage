/**
 * Ambient type declarations for `webengage-react-native`.
 *
 * The package is a peer dependency and may not be installed in a dev
 * environment. These declarations provide just enough typing for the
 * WebEngageSdkBridge implementation to compile.
 */
declare module 'webengage-react-native' {
    interface WebEngageModule {
        /**
         * Registers in-app notification lifecycle callbacks.
         *
         * Parameter order (v2.x): shown, dismissed, clicked, prepared.
         */
        setUpInAppCallbacks(
            onInAppShown: ((data: unknown) => void) | null,
            onInAppDismissed: ((data: unknown) => void) | null,
            onInAppClicked: ((data: unknown, actionId: string) => void) | null,
            onInAppPrepared: ((data: unknown) => void) | null,
        ): void;

        /** Tracks a custom event. */
        trackEvent(
            eventName: string,
            attributes?: Record<string, unknown>,
        ): void;

        /** Records a screen navigation event. */
        screenNavigated(screenName: string): void;
    }

    const WebEngage: WebEngageModule;
    export default WebEngage;
}
