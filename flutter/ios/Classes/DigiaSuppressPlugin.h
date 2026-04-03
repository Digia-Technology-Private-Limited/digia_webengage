//
//  DigiaSuppressPlugin.h
//  digia_engage_webengage
//

#import <Flutter/Flutter.h>
#import <WebEngage/WebEngage.h>

NS_ASSUME_NONNULL_BEGIN

/// Flutter plugin that acts as the WebEngage WEGInAppNotificationProtocol delegate.
///
/// Routes in-app notifications:
///   - Digia campaigns  → suppress WebEngage rendering + forward to Flutter via MethodChannel.
///   - Normal campaigns → allow WebEngage SDK to render as usual.
///
/// Pass +shared as notificationDelegate inside WebEngageConfig in AppDelegate
/// AFTER calling GeneratedPluginRegistrant registerWithRegistry.
@interface DigiaSuppressPlugin : NSObject <FlutterPlugin, WEGInAppNotificationProtocol>

/// The singleton instance created during Flutter plugin registration.
@property (class, nonatomic, readonly, nullable) DigiaSuppressPlugin *shared;

@end

NS_ASSUME_NONNULL_END
