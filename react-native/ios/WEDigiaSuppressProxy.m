//
//  WEDigiaSuppressProxy.m
//  digia_engage_webengage (React Native)
//

#import "WEDigiaSuppressProxy.h"

@implementation WEDigiaSuppressProxy

+ (instancetype)shared {
    static WEDigiaSuppressProxy *instance = nil;
    static dispatch_once_t onceToken;
    dispatch_once(&onceToken, ^{ instance = [WEDigiaSuppressProxy new]; });
    return instance;
}

// MARK: - WEGInAppNotificationProtocol

- (NSDictionary *)notificationPrepared:(NSDictionary<NSString *, id> *)inAppNotificationData
                             shouldStop:(BOOL *)stopRendering {
    NSLog(@"[WEDigiaSuppressProxy] notificationPrepared: realDelegate=%@", self.realDelegate);
    if (self.realDelegate) {
        NSDictionary *result = [self.realDelegate notificationPrepared:inAppNotificationData shouldStop:stopRendering];
        NSLog(@"[WEDigiaSuppressProxy] notificationPrepared: stopRendering=%d", *stopRendering);
        return result;
    }
    NSLog(@"[WEDigiaSuppressProxy] notificationPrepared: WARNING — realDelegate is nil, WebEngage will render normally");
    return inAppNotificationData;
}

- (void)notificationShown:(NSDictionary<NSString *, id> *)inAppNotificationData {
    if ([self.realDelegate respondsToSelector:@selector(notificationShown:)]) {
        [self.realDelegate notificationShown:inAppNotificationData];
    }
}

- (void)notification:(NSDictionary<NSString *, id> *)inAppNotificationData clickedWithAction:(NSString *)actionId {
    if ([self.realDelegate respondsToSelector:@selector(notification:clickedWithAction:)]) {
        [self.realDelegate notification:inAppNotificationData clickedWithAction:actionId];
    }
}

- (void)notificationDismissed:(NSDictionary<NSString *, id> *)inAppNotificationData {
    [self.realDelegate notificationDismissed:inAppNotificationData];
}

@end
