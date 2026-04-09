/**
 * Ambient type declarations for `react-native-webengage`.
 *
 * The package is a peer dependency and may not be installed in a dev
 * environment. These declarations provide just enough typing for the
 * WebEngageSdkBridge implementation to compile.
 */
// declare module 'react-native-webengage' {
//     interface EmitterSubscription {
//         remove(): void;
//     }

//     interface WebEngageNotificationChannel {
//         onPrepare(callback: (data: unknown) => void): EmitterSubscription;
//         onShown(callback: (data: unknown) => void): EmitterSubscription;
//         onClick(callback: (data: unknown, actionId: string) => void): EmitterSubscription;
//         onDismiss(callback: (data: unknown) => void): EmitterSubscription;
//     }

//     class WebEngagePlugin {
//         notification: WebEngageNotificationChannel;
//         track(eventName: string, attributes?: Record<string, unknown>): void;
//         screen(name: string, data?: Record<string, unknown>): void;
//     }

//     export default WebEngagePlugin;
// }
