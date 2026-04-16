//
//  DigiaSuppressPlugin.m
//  digia_webengage_plugin
//

#import "DigiaSuppressPlugin.h"
#import <objc/message.h>

// Valid command values that identify a Digia nudge campaign.
static NSString * const kCommandShowDialog       = @"SHOW_DIALOG";
static NSString * const kCommandShowBottomSheet  = @"SHOW_BOTTOM_SHEET";

// MethodChannel name — must match Dart and Android.
static NSString * const kChannelName = @"plugins.digia.tech/webengage_suppress";

@interface DigiaSuppressPlugin ()
@property (nonatomic, strong, nullable) FlutterMethodChannel *channel;
@end

@implementation DigiaSuppressPlugin

// MARK: - Shared Instance

static DigiaSuppressPlugin *_shared = nil;

+ (nullable DigiaSuppressPlugin *)shared {
    return _shared;
}

// MARK: - FlutterPlugin Registration

+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
    DigiaSuppressPlugin *instance = [[DigiaSuppressPlugin alloc] init];
    _shared = instance;

    FlutterMethodChannel *channel =
        [FlutterMethodChannel methodChannelWithName:kChannelName
                                    binaryMessenger:[registrar messenger]];
    [registrar addMethodCallDelegate:instance channel:channel];
    instance.channel = channel;
    // NOTE: Do NOT set notificationDelegate here via KVC.
    // Pass +shared via WebEngageConfig in AppDelegate after registering plugins.
}

- (void)detachFromEngineForRegistrar:(NSObject<FlutterPluginRegistrar> *)registrar {
    self.channel = nil;
    if (_shared == self) {
        _shared = nil;
    }
}

// MARK: - FlutterMethodCallDelegate

- (void)handleMethodCall:(FlutterMethodCall *)call result:(FlutterResult)result {
    if ([call.method isEqualToString:@"trackSystemEvent"]) {
        NSDictionary *args       = call.arguments;
        NSString     *eventName  = args[@"eventName"];
        NSDictionary *systemData = args[@"systemData"] ?: @{};
        NSDictionary *eventData  = args[@"eventData"]  ?: @{};
        if (eventName.length) {
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

            NSLog(@"[DigiaSuppressPlugin] trackSystemEvent — name=%@ andValue=%@", eventName, andValue);

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
                                NSLog(@"[DigiaSuppressPlugin] Marked %@ in sessionCloses: %@", closeKey, closes);
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
                    NSLog(@"[DigiaSuppressPlugin] trackSystemEvent — fired: %@", eventName);
                } else {
                    NSLog(@"[DigiaSuppressPlugin] trackSystemEvent — analytics not available");
                }
            }

        } else {
            NSLog(@"[DigiaSuppressPlugin] trackSystemEvent: skipped — eventName is empty");
        }
        result(nil);
    } else {
        // `configure` was previously used to toggle suppressRendering globally.
        // Suppression is now handled per-campaign in notificationPrepared:shouldStop:.
        result(nil);
    }
}

// MARK: - WEGInAppNotificationProtocol

- (NSDictionary *)notificationPrepared:(NSDictionary<NSString *, id> *)inAppNotificationData
                             shouldStop:(BOOL *)stopRendering {

    // ── Step 1: Identify campaign type ──────────────────────────────────────
    if (![self isDigiaCampaign:inAppNotificationData]) {
        NSLog(@"[DigiaSuppressPlugin] notificationPrepared — non-Digia campaign, allowing normal rendering");
        // Normal campaign → allow WebEngage SDK to render it as usual.
        return inAppNotificationData;
    }
    // ── Step 2: Digia campaign → suppress WebEngage's own renderer ──────────
    *stopRendering = YES;

    // ── Step 3: Ensure experimentId is forwarded under the canonical key ─────
    NSString *experimentId = inAppNotificationData[@"notificationEncId"]
                          ?: inAppNotificationData[@"experimentId"]
                          ?: inAppNotificationData[@"experiment_id"];
    NSMutableDictionary *payload = [inAppNotificationData mutableCopy];
    if (experimentId && !payload[@"experimentId"]) {
        payload[@"experimentId"] = experimentId;
    }

    // ── Step 5: Send data to Flutter on the main thread ──────────────────────
    FlutterMethodChannel *channel = self.channel;
    NSDictionary *immutablePayload = [payload copy];
    dispatch_async(dispatch_get_main_queue(), ^{
        [channel invokeMethod:@"onInAppPrepared" arguments:immutablePayload];
    });

    return inAppNotificationData;
}

- (void)notificationDismissed:(NSDictionary<NSString *, id> *)inAppNotificationData {
    NSString *experimentId = inAppNotificationData[@"notificationEncId"]
                          ?: inAppNotificationData[@"experimentId"]
                          ?: inAppNotificationData[@"experiment_id"];

    FlutterMethodChannel *channel = self.channel;
    NSDictionary *args = @{ @"experimentId": experimentId ?: [NSNull null] };
    dispatch_async(dispatch_get_main_queue(), ^{
        [channel invokeMethod:@"onInAppDismissed" arguments:args];
    });
}

// MARK: - Campaign Type Identification

/// Returns YES when the notification data carries a Digia rendering contract.
///
/// Mirrors the Dart WebEngagePayloadMapper._normalizeWithDigiaContract logic:
///   1. Structured keys: viewId present + at least one routing signal (type or known command).
///   2. Embedded HTML: any string value contains <digia or digia-payload.
- (BOOL)isDigiaCampaign:(NSDictionary<NSString *, id> *)data {
    return [self hasDigiaContractKeysInData:data] || [self containsDigiaHtmlInNode:data];
}

/// Checks for the structured contract: viewId + routing signal at the top level.
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

/// Recursively scans all string values for embedded Digia HTML markers.
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
