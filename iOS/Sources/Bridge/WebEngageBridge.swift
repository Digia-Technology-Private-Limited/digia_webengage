@preconcurrency import Foundation
@preconcurrency import WebEngage
@preconcurrency import WEPersonalization

// MARK: - Bridge Protocol

/// Abstracts all WebEngage SDK calls so they can be mocked in unit tests.
/// Mirrors `WebEngageBridge.kt` interface on Android.
/// NOT @MainActor — uses regular @escaping closures; the plugin dispatches onto the main actor.
internal protocol WebEngageBridgeProtocol: AnyObject {
    func registerInAppListener(
        onPayload: @escaping ([String: Any]) -> Void,
        onInvalidate: @escaping (String) -> Void
    )
    func registerInlineListener(
        onInlineCampaign: @escaping (
            _ campaignId: String,
            _ targetViewId: String,
            _ customData: [String: Any],
            _ metadata: [String: Any]
        ) -> Void
    )
    func trackSystemEvent(eventName: String, systemData: [String: Any], eventData: [String: Any])
    func navigateScreen(_ name: String)
    func unregisterInAppListener()
    func unregisterInlineListener()
    func isAvailable() -> Bool
}

// MARK: - SDK Bridge

/// Concrete bridge that delegates to the WebEngage iOS SDK.
/// Mirrors `WebEngageSdkBridge` on Android.
///
/// WebEngage iOS accepts one `WEGInAppNotificationProtocol` delegate at a time.
/// This bridge must be registered as `notificationDelegate` **at WebEngage SDK
/// initialization time** in your `AppDelegate`. Use `WebEngagePlugin.makeWebEngageConfig()`
/// to get a `WebEngageConfig` with the bridge pre-wired, then pass it to:
/// `WebEngage.sharedInstance().application(_:didFinishLaunchingWithOptions:webengageConfig:)`
internal final class WebEngageSdkBridge: NSObject, WebEngageBridgeProtocol, @unchecked Sendable {

    // Singleton used for AppDelegate registration (see WebEngagePlugin.makeWebEngageConfig()).
    nonisolated(unsafe) internal static let shared = WebEngageSdkBridge()

    // `var` so that WebEngagePlugin.init(config:) can apply a custom config after the
    // singleton is created with default settings.
    internal var config: WebEngagePluginConfig
    private let dataCache: InAppDataCache

    private var onPayloadCallback:      (([String: Any]) -> Void)?
    private var onInvalidateCallback:   ((String) -> Void)?
    private var onInlineCampaignCallback: ((String, String, [String: Any], [String: Any]) -> Void)?

    init(config: WebEngagePluginConfig = WebEngagePluginConfig(),
         dataCache: InAppDataCache = InAppDataCache()) {
        self.config = config
        self.dataCache = dataCache
        super.init()
    }

    // MARK: - WebEngageBridgeProtocol

    func registerInAppListener(
        onPayload: @escaping ([String: Any]) -> Void,
        onInvalidate: @escaping (String) -> Void
    ) {
        onPayloadCallback    = onPayload
        onInvalidateCallback = onInvalidate
        // NOTE: The delegate binding happens at WebEngage init time in AppDelegate via
        // WebEngagePlugin.makeWebEngageConfig(). Nothing to do here at runtime.
        logDebug("register_inapp_listener mode=\(config.suppressionMode.rawValue)")
    }

    func registerInlineListener(
        onInlineCampaign: @escaping (String, String, [String: Any], [String: Any]) -> Void
    ) {
        onInlineCampaignCallback = onInlineCampaign
        WEPersonalization.shared.registerWECampaignCallback(self)
        logDebug("register_inline_listener")
    }

    func trackSystemEvent(eventName: String, systemData: [String: Any], eventData: [String: Any]) {
        var attrs = systemData
        eventData.forEach { attrs[$0] = $1 }
        WebEngage.sharedInstance().analytics.trackSystemEvent(withName: eventName, andValue: attrs)
    }

    func navigateScreen(_ name: String) {
        WebEngage.sharedInstance().analytics.navigatingToScreen(withName: name)
        logDebug("navigate_screen name=\(name)")
    }

    func unregisterInAppListener() {
        logDebug("unregister_inapp_listener")
        WebEngage.sharedInstance().setValue(nil, forKey: "notificationDelegate")
        onPayloadCallback    = nil
        onInvalidateCallback = nil
    }

    func unregisterInlineListener() {
        logDebug("unregister_inline_listener")
        WEPersonalization.shared.unregisterWECampaignCallback(self)
        onInlineCampaignCallback = nil
    }

    func isAvailable() -> Bool { true }

    // MARK: - Logging

    private func logDebug(_ message: String) {
        if config.diagnosticsEnabled { NSLog("[DigiaWEBridge] \(message)") }
    }

    private func logWarning(_ message: String) {
        NSLog("[DigiaWEBridge] WARNING: \(message)")
    }
}

// MARK: - WEGInAppNotificationProtocol

extension WebEngageSdkBridge: WEGInAppNotificationProtocol {

    @objc func notificationPrepared(
        _ inAppNotificationData: [String: Any]!,
        shouldStop stopRendering: UnsafeMutablePointer<ObjCBool>!
    ) -> [AnyHashable: Any]! {
        // Unconditional log — confirms WebEngage is calling the delegate.
        NSLog("[DigiaWEBridge] ✅ notificationPrepared called keys=\(inAppNotificationData?.keys.sorted() ?? [])")

        guard let raw = inAppNotificationData else {
            logWarning("in-app prepared with nil data; skipping")
            return inAppNotificationData
        }

        let map: [String: Any] = raw

        let normalizedMap  = normalizeWithDigiaContract(map)
        let isDigiaCampaign = (normalizedMap["digiaContractSource"] as? String)?.isEmpty == false

        let suppress = shouldSuppressInAppRendering(
            mode: config.suppressionMode,
            isDigiaCampaign: isDigiaCampaign
        )
        if suppress { stopRendering?.pointee = true }

        // Prefer keys from normalizedMap (contract extraction may have resolved them).
        let experimentId = str(normalizedMap["experimentId"] ?? normalizedMap["experiment_id"]
                                ?? map["experimentId"] ?? map["experiment_id"]) ?? ""
        let layoutId     = str(normalizedMap["layoutId"]     ?? normalizedMap["layout_id"]
                                ?? map["layoutId"] ?? map["layout_id"])     ?? ""
        let variationId  = str(normalizedMap["variationId"]  ?? normalizedMap["variation_id"]
                                ?? map["variationId"] ?? map["variation_id"])  ?? ""

        logDebug("inapp_prepared exp=\(experimentId) isDigia=\(isDigiaCampaign) suppress=\(suppress)")

        // Always dispatch Digia campaigns regardless of suppressionMode so the
        // CEP delegate can render them. Non-Digia campaigns in suppressAll mode
        // are also dispatched (forced rendering override).
        let shouldDispatch = isDigiaCampaign || config.suppressionMode == .suppressAll
        guard shouldDispatch else {
            logDebug("inapp_prepared skip_dispatch exp=\(experimentId) reason=non_digia_campaign")
            return inAppNotificationData
        }

        if !experimentId.isEmpty {
            var cacheEntry = normalizedMap
            cacheEntry["experimentId"] = experimentId
            cacheEntry["layoutId"]     = layoutId
            cacheEntry["variationId"]  = variationId
            dataCache.put(experimentId: experimentId, data: cacheEntry)
        }

        var enriched = normalizedMap
        if !experimentId.isEmpty { enriched["experimentId"] = experimentId }
        if !layoutId.isEmpty     { enriched["layoutId"]     = layoutId }
        if !variationId.isEmpty  { enriched["variationId"]  = variationId }

        onPayloadCallback?(enriched)
        return inAppNotificationData
    }

    @objc func notificationShown(_ inAppNotificationData: [String: Any]!) {
        let id = inAppNotificationData?["experimentId"] as? String ?? ""
        logDebug("inapp_shown exp=\(id)")
    }

    @objc func notificationDismissed(_ inAppNotificationData: [String: Any]!) {
        let id = str(inAppNotificationData?["experimentId"] ?? inAppNotificationData?["experiment_id"]) ?? ""
        logDebug("inapp_dismissed exp=\(id)")
        if !id.isEmpty { onInvalidateCallback?(id) }
    }

    // Correct ObjC selector: notification:clickedWithAction:
    @objc func notification(_ inAppNotificationData: [String: Any]!, clickedWithAction actionId: String!) {
        let id = inAppNotificationData?["experimentId"] as? String ?? ""
        logDebug("inapp_clicked exp=\(id) actionId=\(actionId ?? "")")
    }

    // MARK: - Suppression helper

    private func shouldSuppressInAppRendering(mode: SuppressionMode, isDigiaCampaign: Bool) -> Bool {
        switch mode {
        case .passThrough:    return false
        case .suppressAll:    return true
        case .suppressDigiaOnly: return isDigiaCampaign
        }
    }
}

// MARK: - WECampaignCallback (WEPersonalization inline)

extension WebEngageSdkBridge: WECampaignCallback {

    // Called when campaign data is received — primary hook for inline slots.
    func onCampaignPrepared(_ data: WECampaignData) -> WECampaignData {
        let campaignId   = data.campaignId ?? ""
        // targetViewId is now an Int tag in the new API; stringify for downstream.
        let targetViewId = String(data.targetViewTag)

        guard !campaignId.isEmpty else { return data }

        let customData = extractCustomData(data)
        let metadata   = extractInlineMetadata(data)

        logDebug("inline_prepared campaignId=\(campaignId) targetViewTag=\(data.targetViewTag)")

        if customData.isEmpty {
            logWarning("inline_prepared skipped campaignId=\(campaignId) reason=missing_custom_data")
        } else {
            onInlineCampaignCallback?(campaignId, targetViewId, customData, metadata)
        }
        return data
    }

    func onCampaignShown(data: WECampaignData) {
        logDebug("inline_shown campaignId=\(data.campaignId ?? "") targetViewTag=\(data.targetViewTag)")
    }

    func onCampaignClicked(actionId: String, deepLink: String, data: WECampaignData) -> Bool {
        logDebug("inline_clicked campaignId=\(data.campaignId ?? "") actionId=\(actionId)")
        return false
    }

    func onCampaignException(_ campaignId: String?, _ targetViewId: String, _ exception: any Error) {
        logWarning("inline_exception campaignId=\(campaignId ?? "") targetViewId=\(targetViewId) error=\(exception.localizedDescription)")
    }

    // MARK: - Inline helpers

    private func extractCustomData(_ data: WECampaignData) -> [String: Any] {
        // WECampaignContent.custom holds the integrator-supplied custom data dict.
        return data.content?.custom ?? [:]
    }

    private func extractInlineMetadata(_ data: WECampaignData) -> [String: Any] {
        let campaignId   = data.campaignId ?? ""
        let targetViewId = String(data.targetViewTag)
        var meta: [String: Any] = [
            "campaignId":   campaignId,
            "targetViewId": targetViewId,
            "propertyId":   targetViewId,
        ]
        if let vid = (data as AnyObject).value(forKey: "variationId") as? String, !vid.isEmpty {
            meta["variationId"] = vid
        }
        return meta
    }
}

// MARK: - String helper

private func str(_ value: Any?) -> String? {
    guard let s = (value as? String)?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
    return s
}

