//
//  WEDigiaSuppressProxy.h
//  digia_engage_webengage (React Native)
//
//  Lightweight proxy — no React imports — that AppDelegate can pass as the
//  WebEngage in-app notification delegate.  WEDigiaSuppressModule registers
//  itself here when the RN bridge initialises it.
//

#import <Foundation/Foundation.h>
#import <WebEngage/WEGInAppNotificationProtocol.h>

NS_ASSUME_NONNULL_BEGIN

@interface WEDigiaSuppressProxy : NSObject <WEGInAppNotificationProtocol>

/// The shared instance; always non-nil.
+ (instancetype)shared;

/// Set by WEDigiaSuppressModule when the RN bridge creates it.
@property (nonatomic, weak, nullable) id<WEGInAppNotificationProtocol> realDelegate;

@end

NS_ASSUME_NONNULL_END
