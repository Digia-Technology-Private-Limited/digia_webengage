//
//  DigiaSuppressPlugin.m
//  digia_webengage_plugin
//

#import "DigiaSuppressPlugin.h"

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
    // `configure` was previously used to toggle suppressRendering globally.
    // Suppression is now handled per-campaign in notificationPrepared:shouldStop:.
    result(nil);
}

// MARK: - WEGInAppNotificationProtocol

- (NSDictionary *)notificationPrepared:(NSDictionary<NSString *, id> *)inAppNotificationData
                             shouldStop:(BOOL *)stopRendering {

    // ── Step 1: Identify campaign type ──────────────────────────────────────
    if (![self isDigiaCampaign:inAppNotificationData]) {
        // Normal campaign → allow WebEngage SDK to render it as usual.
        return inAppNotificationData;
    }

    // ── Step 2: Digia campaign → suppress WebEngage's own renderer ──────────
    *stopRendering = YES;

    // ── Step 3: Ensure experimentId is forwarded under the canonical key ─────
    NSString *experimentId = inAppNotificationData[@"experimentId"]
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
    NSString *experimentId = inAppNotificationData[@"experimentId"]
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
