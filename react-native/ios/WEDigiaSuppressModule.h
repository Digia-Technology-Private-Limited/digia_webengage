//
//  WEDigiaSuppressModule.h
//  digia_engage_webengage (React Native)
//

#import <React/RCTBridgeModule.h>
#import <React/RCTEventEmitter.h>

/**
 * React Native native module that mirrors Flutter's DigiaSuppressPlugin.
 *
 * Registers as a WebEngage in-app notification delegate, detects Digia campaigns,
 * suppresses WebEngage's own renderer for those, and emits events to JS:
 *   - "weDigiaInAppPrepared"  — the full notification data + experimentId
 *   - "weDigiaInAppDismissed" — { experimentId: string }
 *
 * The module must be set as the WebEngage notification delegate in AppDelegate:
 * ```objc
 * [[WebEngage sharedInstance] setNotificationDelegate:[WEDigiaSuppressModule shared]];
 * ```
 */
NS_ASSUME_NONNULL_BEGIN

@interface WEDigiaSuppressModule : RCTEventEmitter <RCTBridgeModule>

/// The singleton instance created by React Native's bridge.
+ (nullable instancetype)shared;

@end

NS_ASSUME_NONNULL_END
