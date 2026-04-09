//
//  WEDigiaSuppressModule.m
//  digia_engage_webengage (React Native)
//
//  Mirrors Flutter's DigiaSuppressPlugin.m exactly.
//  Registers as the WebEngage in-app notification delegate, detects Digia
//  campaigns, suppresses render for those, and emits to JS via RCTEventEmitter.
//

#import "WEDigiaSuppressModule.h"

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
    }
    return self;
}

// MARK: - RCTBridgeModule

RCT_EXPORT_MODULE(DigiaSuppressModule)

RCT_EXPORT_METHOD(install) {
    // no-op — delegate is set in AppDelegate; method exposed so JS can call it
    // to confirm the module loaded.
}

// Required by RCTEventEmitter.
- (NSArray<NSString *> *)supportedEvents {
    return @[kEventPrepared, kEventDismissed];
}

// RCTEventEmitter override — called when JS adds its first listener.
- (void)startObserving {
    // no-op: _shared is set in init
}

// RCTEventEmitter override — called when the last JS listener is removed.
- (void)stopObserving {
    // no-op: keep _shared alive for the module's lifetime
}

// MARK: - WEGInAppNotificationProtocol

- (NSDictionary *)notificationPrepared:(NSDictionary<NSString *, id> *)inAppNotificationData
                             shouldStop:(BOOL *)stopRendering {

    // ── Step 1: Identify campaign type ──────────────────────────────────────
    if (![self isDigiaCampaign:inAppNotificationData]) {
        // Normal campaign → allow WebEngage SDK to render.
        return inAppNotificationData;
    }

    // ── Step 2: Digia campaign → suppress WebEngage's renderer ──────────────
    *stopRendering = YES;

    // ── Step 3: Normalise experimentId key ──────────────────────────────────
    NSString *experimentId = inAppNotificationData[@"experimentId"]
                          ?: inAppNotificationData[@"experiment_id"];
    NSMutableDictionary *payload = [inAppNotificationData mutableCopy];
    if (experimentId && !payload[@"experimentId"]) {
        payload[@"experimentId"] = experimentId;
    }

    // ── Step 4: Emit to JS on main thread ────────────────────────────────────
    NSDictionary *immutablePayload = [payload copy];
    dispatch_async(dispatch_get_main_queue(), ^{
        [self sendEventWithName:kEventPrepared body:immutablePayload];
    });

    return inAppNotificationData;
}

- (void)notificationDismissed:(NSDictionary<NSString *, id> *)inAppNotificationData {
    NSString *experimentId = inAppNotificationData[@"experimentId"]
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
    NSString *upper = [command uppercaseString];
    return [upper isEqualToString:kCommandShowDialog] ||
           [upper isEqualToString:kCommandShowBottomSheet];
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
