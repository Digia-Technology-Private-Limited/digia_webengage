import Flutter
import WebEngage

/// Flutter plugin that acts as the WebEngage `WEGInAppNotificationProtocol` delegate on iOS.
///
/// **Campaign routing flow:**
/// ```
/// Native Layer (iOS)
///     ↓
/// notificationPrepared() called
///     ↓
/// isDigiaCampaign(data)?
///     ↓ YES                         ↓ NO
/// suppressRendering = true      suppressRendering stays false
/// deduplicate by experimentId   return data as-is
/// send data → Flutter            (WebEngage SDK renders normally)
/// return data
/// ```
///
/// A campaign is identified as "Digia" when its notification data either:
///   - contains a `viewId` key plus a routing signal (`type` or a valid `command`), OR
///   - embeds a `<digia .../>` or `<script digia-payload>` HTML tag anywhere in its values.
///
/// The plugin instance is stored as a static `shared` reference populated during
/// `register(with:)`. Pass `DigiaSuppressPlugin.shared` as the `notificationDelegate`
/// inside `WebEngageConfig` **after** calling `GeneratedPluginRegistrant.register(with:)`
/// so the instance is available. See `AppDelegate.swift`.
public final class DigiaSuppressPlugin: NSObject, FlutterPlugin, WEGInAppNotificationProtocol {

    // MARK: - Shared Instance

    /// The plugin instance created during Flutter plugin registration.
    /// Available after `GeneratedPluginRegistrant.register(with:)` is called.
    @objc public private(set) static var shared: DigiaSuppressPlugin?

    // MARK: - Private State

    private var channel: FlutterMethodChannel?

    /// Tracks the experimentId of the last Digia in-app forwarded to Dart.
    /// WebEngage evaluates campaigns on every screen navigation, producing repeated
    /// `notificationPrepared` callbacks. Only the first is forwarded; cleared on dismiss.
    private var activeExperimentId: String?

    // MARK: - FlutterPlugin Registration

    public static func register(with registrar: FlutterPluginRegistrar) {
        let instance = DigiaSuppressPlugin()
        DigiaSuppressPlugin.shared = instance

        let channel = FlutterMethodChannel(
            name: "plugins.digia.tech/webengage_suppress",
            binaryMessenger: registrar.messenger()
        )
        registrar.addMethodCallDelegate(instance, channel: channel)
        instance.channel = channel
        // NOTE: Do NOT attempt to set the WebEngage notificationDelegate here via KVC.
        // The WebEngage SDK has no public setter for notificationDelegate post-init.
        // Pass DigiaSuppressPlugin.shared via WebEngageConfig in AppDelegate instead.
    }

    public func detachFromEngine(for registrar: FlutterPluginRegistrar) {
        channel = nil
        activeExperimentId = nil
        if DigiaSuppressPlugin.shared === self {
            DigiaSuppressPlugin.shared = nil
        }
    }

    // MARK: - FlutterMethodCallDelegate

    public func handle(_ call: FlutterMethodCall, result: @escaping FlutterResult) {
        // `configure` was previously used to toggle suppressRendering globally.
        // Suppression is now handled per-campaign in notificationPrepared(_:shouldStop:),
        // so this call is accepted but has no effect.
        result(nil)
    }

    // MARK: - WEGInAppNotificationProtocol

    public func notificationPrepared(
        _ inAppNotificationData: [String: Any],
        shouldStop stopRendering: UnsafeMutablePointer<ObjCBool>
    ) -> [String: Any] {

        // ── Step 1: Identify campaign type ──────────────────────────────────
        guard isDigiaCampaign(inAppNotificationData) else {
            // Normal campaign → allow WebEngage SDK to render it as usual.
            return inAppNotificationData
        }

        // ── Step 2: Digia campaign → suppress WebEngage's own renderer ──────
        stopRendering.pointee = true

        // ── Step 3: Deduplicate repeated callbacks for the same campaign ─────
        let experimentId = inAppNotificationData["experimentId"] as? String
                        ?? inAppNotificationData["experiment_id"] as? String

        if let eid = experimentId, eid == activeExperimentId {
            return inAppNotificationData
        }
        activeExperimentId = experimentId

        // ── Step 4: Ensure experimentId is always forwarded under canonical key
        var payload = inAppNotificationData
        if let eid = experimentId, payload["experimentId"] == nil {
            payload["experimentId"] = eid
        }

        // ── Step 5: Send data to Flutter ────────────────────────────────────
        channel?.invokeMethod("onInAppPrepared", arguments: payload)
        return inAppNotificationData
    }

    public func notificationDismissed(_ inAppNotificationData: [String: Any]) {
        let experimentId = inAppNotificationData["experimentId"] as? String
                        ?? inAppNotificationData["experiment_id"] as? String

        if let eid = experimentId, eid == activeExperimentId {
            activeExperimentId = nil
        }

        channel?.invokeMethod("onInAppDismissed", arguments: ["experimentId": experimentId as Any])
    }

    // MARK: - Campaign Type Identification

    /// Returns `true` when the notification data carries a Digia rendering contract.
    ///
    /// Mirrors the Dart `WebEngagePayloadMapper._normalizeWithDigiaContract` logic:
    /// 1. **Top-level keys**: `viewId` is present **and** at least one routing signal
    ///    (`type` or a known `command`) exists.
    /// 2. **Embedded HTML**: any string value in the dictionary contains a
    ///    `<digia .../>` attribute tag or a `<script digia-payload>` block.
    private func isDigiaCampaign(_ data: [String: Any]) -> Bool {
        if hasDigiaContractKeys(in: data) { return true }
        if containsDigiaHtml(in: data)    { return true }
        return false
    }

    /// Checks for the structured contract: `viewId` + routing signal at the top level.
    private func hasDigiaContractKeys(in data: [String: Any]) -> Bool {
        let viewId = (data["viewId"] as? String)?.trimmingCharacters(in: .whitespaces) ?? ""
        guard !viewId.isEmpty else { return false }

        let type    = (data["type"] as? String)?.lowercased()
        let command = (data["command"] as? String)?.uppercased()

        if type != nil { return true }
        if command == "SHOW_DIALOG" || command == "SHOW_BOTTOM_SHEET" { return true }
        return false
    }

    /// Recursively scans all string values for embedded Digia HTML markers.
    private func containsDigiaHtml(in node: Any) -> Bool {
        if let str = node as? String {
            let lower = str.lowercased()
            return lower.contains("<digia") || lower.contains("digia-payload")
        }
        if let dict = node as? [String: Any] {
            return dict.values.contains(where: containsDigiaHtml)
        }
        if let array = node as? [Any] {
            return array.contains(where: containsDigiaHtml)
        }
        return false
    }
}
