import Foundation

/// Configuration object passed when constructing `WebEngagePlugin`.
@objc public class WebEngagePluginConfig: NSObject {
    @objc public let diagnosticsEnabled: Bool

    @objc public init(
        diagnosticsEnabled: Bool = true
    ) {
        self.diagnosticsEnabled = diagnosticsEnabled
    }
}
