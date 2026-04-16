//
//  WEDigiaSuppressModule.m
//  digia_engage_webengage (React Native)
//
//  Mirrors Flutter's DigiaSuppressPlugin.m exactly.
//  Registers as the WebEngage in-app notification delegate, detects Digia
//  campaigns, suppresses render for those, and emits to JS via RCTEventEmitter.
//

#import "WEDigiaSuppressModule.h"
#import "WEDigiaSuppressProxy.h"
#import <WebEngage/WebEngage.h>
#import <objc/message.h>

static NSString * const kCommandShowDialog      = @"SHOW_DIALOG";
static NSString * const kCommandShowBottomSheet = @"SHOW_BOTTOM_SHEET";
static NSString * const kEventPrepared          = @"weDigiaInAppPrepared";
static NSString * const kEventDismissed         = @"weDigiaInAppDismissed";

@implementation WEDigiaSuppressModule

// MARK: - Singleton

static WEDigiaSuppressModule *_shared = nil;

+ (nullable instancetype)shared {
    return _shared;
}

- (instancetype)init {
    self = [super init];
    if (self) {
        _shared = self;
        [WEDigiaSuppressProxy shared].realDelegate = self;

        // Pre-seed _listenerCount = 1 so sendEventWithName: never silently drops
        // events when JS uses DeviceEventEmitter (which doesn't call native
        // addListener: on iOS and therefore never increments the count).
        [self addListener:kEventPrepared];

        NSLog(@"[WEDigiaSuppressModule] init — module created, set as realDelegate on WEDigiaSuppressProxy");
    }
    return self;
}

// MARK: - RCTBridgeModule

RCT_EXPORT_MODULE(DigiaSuppressModule)

RCT_EXPORT_METHOD(install) {
    // no-op — delegate is set in AppDelegate; method exposed so JS can call it
    // to confirm the module loaded.
}

RCT_EXPORT_METHOD(trackSystemEvent:(NSString *)eventName
                  systemData:(NSDictionary *)systemData
                   eventData:(NSDictionary *)eventData) {
    if (!eventName.length) {
        NSLog(@"[WEDigiaSuppressModule] trackSystemEvent: skipped — eventName is empty");
        return;
    }

    // Build the andValue dict the same way WebEngage fires notification_view
    // internally: put campaign data under "system_data_overrides" so the
    // WEGEvent factory merges it into system_data (not event_data).
    NSMutableDictionary *andValue = [NSMutableDictionary dictionary];
    if (systemData.count) {
        andValue[@"system_data_overrides"] = systemData;
    }
    if (eventData.count) {
        andValue[@"event_data_overrides"] = eventData;
    }

    NSLog(@"[WEDigiaSuppressModule] trackSystemEvent — name=%@ andValue=%@", eventName, andValue);

    // Mark sessionCloses BEFORE firing the event so re-evaluation
    // triggered by the event already sees the mark.
    if ([eventName isEqualToString:@"notification_close"]) {
        NSString *expId = systemData[@"experiment_id"];
        if (expId.length > 0) {
            Class rendererCls = NSClassFromString(@"WEGRenderer");
            SEL sharedSel2 = NSSelectorFromString(@"sharedInstance");
            SEL closesSel  = NSSelectorFromString(@"sessionCloses");
            if (rendererCls && [rendererCls respondsToSelector:sharedSel2]) {
                id renderer = ((id (*)(Class, SEL))objc_msgSend)(rendererCls, sharedSel2);
                if (renderer && [renderer respondsToSelector:closesSel]) {
                    NSMutableDictionary *closes = ((id (*)(id, SEL))objc_msgSend)(renderer, closesSel);
                    if ([closes isKindOfClass:[NSMutableDictionary class]]) {
                        NSString *closeKey = [expId stringByAppendingString:@"_close"];
                        closes[closeKey] = @1;
                        NSLog(@"[WEDigiaSuppressModule] Marked %@ in sessionCloses", closeKey);
                    }
                }
            }
        }
    }

    // Use trackSDKEventWithName:andValue: which bypasses reserved-name
    // guards and routes through the full SDK event pipeline.
    SEL sharedSel = NSSelectorFromString(@"sharedInstance");
    SEL trackSel  = NSSelectorFromString(@"trackSDKEventWithName:andValue:");

    Class analyticsCls = NSClassFromString(@"WEGAnalyticsImpl");
    if (analyticsCls) {
        typedef id  (*SharedFn)(Class, SEL);
        typedef void (*TrackFn)(id, SEL, NSString *, NSDictionary *);

        id analytics = ((SharedFn)objc_msgSend)(analyticsCls, sharedSel);
        if (analytics) {
            ((TrackFn)objc_msgSend)(analytics, trackSel, eventName, andValue);
            NSLog(@"[WEDigiaSuppressModule] trackSystemEvent — fired: %@", eventName);
        } else {
            NSLog(@"[WEDigiaSuppressModule] trackSystemEvent — analytics not available");
        }
    }
}

// Required by RCTEventEmitter.
- (NSArray<NSString *> *)supportedEvents {
    return @[kEventPrepared, kEventDismissed];
}

// RCTEventEmitter override — called when JS adds its first listener.
- (void)startObserving {
    // no-op: _shared is set in init
    NSLog(@"[WEDigiaSuppressModule] startObserving — JS added first listener");
}

// RCTEventEmitter override — called when the last JS listener is removed.
- (void)stopObserving {
    // no-op: keep _shared alive for the module's lifetime
    NSLog(@"[WEDigiaSuppressModule] stopObserving — all JS listeners removed");
}

// MARK: - WEGInAppNotificationProtocol

- (NSDictionary *)notificationPrepared:(NSDictionary<NSString *, id> *)inAppNotificationData
                             shouldStop:(BOOL *)stopRendering {

    NSLog(@"[WEDigiaSuppressModule] notificationPrepared called. Keys: %@", inAppNotificationData.allKeys);
    NSLog(@"[WEDigiaSuppressModule] notificationPrepared data: %@", inAppNotificationData);

    // ── Step 1: Identify campaign type ──────────────────────────────────────
    if (![self isDigiaCampaign:inAppNotificationData]) {
        NSLog(@"[WEDigiaSuppressModule] notificationPrepared: NOT a Digia campaign — allowing WebEngage to render");
        // Normal campaign → allow WebEngage SDK to render.
        return inAppNotificationData;
    }

    NSLog(@"[WEDigiaSuppressModule] notificationPrepared: IS a Digia campaign — suppressing render");

    // ── Step 2: Digia campaign → suppress WebEngage's renderer ──────────────
    *stopRendering = YES;

    // ── Step 3: Normalise experimentId ────────────────────────────────────
    NSString *experimentId = inAppNotificationData[@"notificationEncId"]
                          ?: inAppNotificationData[@"experimentId"]
                          ?: inAppNotificationData[@"experiment_id"];
    NSMutableDictionary *payload = [inAppNotificationData mutableCopy];
    if (experimentId && !payload[@"experimentId"]) {
        payload[@"experimentId"] = experimentId;
    }

    NSLog(@"[WEDigiaSuppressModule] notificationPrepared: emitting %@ with experimentId=%@", kEventPrepared, experimentId);

    // ── Step 4: Emit to JS on main thread ────────────────────────────────────
    NSDictionary *immutablePayload = [payload copy];
    dispatch_async(dispatch_get_main_queue(), ^{
        NSLog(@"[WEDigiaSuppressModule] sendEventWithName:%@ body keys: %@", kEventPrepared, immutablePayload.allKeys);
        [self sendEventWithName:kEventPrepared body:immutablePayload];
    });

    return inAppNotificationData;
}

- (void)notificationDismissed:(NSDictionary<NSString *, id> *)inAppNotificationData {
    NSString *experimentId = inAppNotificationData[@"notificationEncId"]
                          ?: inAppNotificationData[@"experimentId"]
                          ?: inAppNotificationData[@"experiment_id"];
    NSDictionary *args = @{ @"experimentId": experimentId ?: [NSNull null] };
    dispatch_async(dispatch_get_main_queue(), ^{
        [self sendEventWithName:kEventDismissed body:args];
    });
}

// MARK: - Campaign Type Identification

/// Returns YES when the notification data carries a Digia rendering contract.
/// Mirrors Flutter's DigiaSuppressPlugin isDigiaCampaign logic exactly.
- (BOOL)isDigiaCampaign:(NSDictionary<NSString *, id> *)data {
    return [self hasDigiaContractKeysInData:data] || [self containsDigiaHtmlInNode:data];
}

- (BOOL)hasDigiaContractKeysInData:(NSDictionary<NSString *, id> *)data {
    NSString *viewId = [data[@"viewId"] isKindOfClass:[NSString class]]
                     ? [data[@"viewId"] stringByTrimmingCharactersInSet:NSCharacterSet.whitespaceCharacterSet]
                     : nil;
    if (!viewId.length) return NO;

    NSString *type    = [data[@"type"]    isKindOfClass:[NSString class]] ? data[@"type"]    : nil;
    NSString *command = [data[@"command"] isKindOfClass:[NSString class]] ? data[@"command"] : nil;

    if (type) return YES;
    NSString *upperCommand = [command uppercaseString];
    if ([upperCommand isEqualToString:kCommandShowDialog] ||
        [upperCommand isEqualToString:kCommandShowBottomSheet]) return YES;

    return NO;
}

- (BOOL)containsDigiaHtmlInNode:(id)node {
    if ([node isKindOfClass:[NSString class]]) {
        NSString *lower = [(NSString *)node lowercaseString];
        return [lower containsString:@"<digia"] || [lower containsString:@"digia-payload"];
    }
    if ([node isKindOfClass:[NSDictionary class]]) {
        for (id value in [(NSDictionary *)node allValues]) {
            if ([self containsDigiaHtmlInNode:value]) return YES;
        }
    }
    if ([node isKindOfClass:[NSArray class]]) {
        for (id item in (NSArray *)node) {
            if ([self containsDigiaHtmlInNode:item]) return YES;
        }
    }
    return NO;
}

@end
