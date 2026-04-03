import Foundation

/// Controls which WebEngage in-app notifications are suppressed (prevented from rendering).
@objc public enum SuppressionMode: Int {
    /// Never suppress — let WebEngage render every notification normally.
    case passThrough
    /// Suppress every notification, regardless of whether it is a Digia campaign.
    case suppressAll
    /// Suppress only notifications that carry a Digia contract (default).
    case suppressDigiaOnly
}

/// Configuration object passed when constructing `WebEngagePlugin`.
@objc public class WebEngagePluginConfig: NSObject {
    @objc public let suppressionMode: SuppressionMode
    @objc public let diagnosticsEnabled: Bool
    /// When set, SUPPRESS_ALL payloads without an explicit componentId are mapped to this dialog component.
    @objc public let forcedDialogComponentId: String?

    @objc public init(
        suppressionMode: SuppressionMode = .suppressDigiaOnly,
        diagnosticsEnabled: Bool = true,
        forcedDialogComponentId: String? = nil
    ) {
        self.suppressionMode = suppressionMode
        self.diagnosticsEnabled = diagnosticsEnabled
        self.forcedDialogComponentId = forcedDialogComponentId
    }
}

/// Returns `true` when the in-app notification should be prevented from rendering by WebEngage.
internal func shouldSuppressInAppRendering(mode: SuppressionMode, isDigiaCampaign: Bool) -> Bool {
    switch mode {
    case .passThrough: return false
    case .suppressAll: return true
    case .suppressDigiaOnly: return isDigiaCampaign
    }
}
