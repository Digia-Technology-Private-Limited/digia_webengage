import Foundation
import DigiaEngage

/// Maps raw WebEngage in-app / personalization payloads into `InAppPayload` values.
/// Mirrors `WebEngagePayloadMapper.kt` on Android.
internal final class WebEngagePayloadMapper {

    private let config: WebEngagePluginConfig

    init(config: WebEngagePluginConfig = WebEngagePluginConfig()) {
        self.config = config
    }

    // MARK: - In-App (modal / nudge)

    /// Maps a raw `notificationPrepared` dictionary to zero or more `InAppPayload`s.
    func map(_ data: [String: Any]) -> [InAppPayload] {
        let normalized = normalizeWithDigiaContract(data)

        guard let campaignId = str(normalized["experimentId"] ?? normalized["id"] ?? normalized["campaignId"]) else {
            logDebug("inapp_map_dropped reason=missing_campaign_id keys=\(data.keys.sorted())")
            return []
        }

        let screenId    = str(normalized["screenId"] ?? normalized["screen_id"])
        let variationId = str(normalized["variationId"])
        let layoutId    = str(normalized["layoutId"])
        let type        = str(normalized["type"])?.lowercased()
        let command     = str(normalized["command"])?.uppercased()
        let viewId      = str(normalized["viewId"])
        let args        = toJSONValueMap(normalizeArgs(normalized["args"]))

        var payloads: [InAppPayload] = []

        if type == "inline" {
            guard let pk = str(normalized["placementKey"]), let vid = viewId else {
                let reason = str(normalized["placementKey"]) == nil ? "missing_placementKey" : "missing_viewId"
                logDebug("inapp_inline_not_mapped campaignId=\(campaignId) reason=\(reason)")
                return []
            }
            payloads.append(InAppPayload(
                id: campaignId,
                content: InAppPayloadContent(
                    type: "inline",
                    placementKey: pk,
                    viewId: vid,
                    command: nil,
                    args: args,
                    screenId: screenId
                ),
                cepContext: buildContext(campaignId: campaignId, variationId: variationId, layoutId: layoutId)
            ))
        } else if let cmd = command, let vid = viewId {
            payloads.append(InAppPayload(
                id: campaignId,
                content: InAppPayloadContent(
                    type: contentType(forCommand: cmd),
                    placementKey: nil,
                    viewId: vid,
                    command: cmd,
                    args: args,
                    screenId: screenId
                ),
                cepContext: buildContext(campaignId: campaignId, variationId: variationId, layoutId: layoutId)
            ))
        } else {
            logDebug("inapp_nudge_not_mapped campaignId=\(campaignId) reason=unsupported_or_incomplete_contract")
        }

        logDebug("inapp_map_result campaignId=\(campaignId) mapped=\(payloads.count)")
        return payloads
    }

    // MARK: - Inline (WEPersonalization)

    /// Maps a WEPersonalization inline campaign to an `InAppPayload`.
    func mapInline(
        campaignId: String,
        targetViewId: String,
        customData: [String: Any],
        metadata: [String: Any]
    ) -> InAppPayload? {
        guard !campaignId.isEmpty else {
            logDebug("inline_map_dropped reason=missing_campaign_id"); return nil
        }
        guard !targetViewId.isEmpty else {
            logDebug("inline_map_dropped campaignId=\(campaignId) reason=missing_targetViewId"); return nil
        }

        let screenId    = str(customData["screenId"] ?? customData["screen_id"])
        let variationId = str(metadata["variationId"])
        let propertyId  = str(metadata["propertyId"]) ?? targetViewId
        let componentId = str(customData["componentId"])

        guard let vid = componentId else {
            logDebug("inline_map_dropped campaignId=\(campaignId) targetViewId=\(targetViewId) reason=missing_componentId")
            return nil
        }

        let args = toJSONValueMap(normalizeArgs(customData["args"]))
        var cepCtx: [String: String] = [
            "experimentId": campaignId,
            "campaignId": campaignId,
            "propertyId": propertyId,
        ]
        if let v = variationId { cepCtx["variationId"] = v }

        let payload = InAppPayload(
            id: "\(campaignId):\(targetViewId)",
            content: InAppPayloadContent(
                type: "inline",
                placementKey: targetViewId,
                viewId: vid,
                command: nil,
                args: args,
                screenId: screenId
            ),
            cepContext: cepCtx
        )
        logDebug("inline_map_result campaignId=\(campaignId) targetViewId=\(targetViewId) payloadId=\(payload.id)")
        return payload
    }

    // MARK: - Helpers

    private func buildContext(
        campaignId: String,
        variationId: String?,
        layoutId: String?
    ) -> [String: String] {
        var ctx: [String: String] = ["experimentId": campaignId, "campaignId": campaignId]
        if let v = variationId { ctx["variationId"] = v }
        if let l = layoutId   { ctx["layoutId"] = l }
        return ctx
    }

    private func contentType(forCommand command: String) -> String {
        switch command {
        case "SHOW_DIALOG":       return "dialog"
        case "SHOW_BOTTOM_SHEET": return "bottomsheet"
        default:                  return "dialog"
        }
    }

    // MARK: - JSONValue conversion

    private func toJSONValueMap(_ raw: [String: Any]) -> [String: JSONValue] {
        var out: [String: JSONValue] = [:]
        raw.forEach { key, val in if let jv = toJSONValue(val) { out[key] = jv } }
        return out
    }

    private func toJSONValue(_ value: Any?) -> JSONValue? {
        switch value {
        case nil, is NSNull:      return .null
        case let b as Bool:       return .bool(b)
        case let n as Int:        return .int(n)
        case let n as Double:     return .double(n)
        case let s as String:     return .string(s)
        case let d as [String: Any]:
            return .object(toJSONValueMap(d))
        case let a as [Any]:
            return .array(a.compactMap { toJSONValue($0) })
        default:
            return .string(String(describing: value!))
        }
    }

    // MARK: - String extractor

    internal func str(_ value: Any?) -> String? {
        guard let s = (value as? String)?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        return s
    }

    private func logDebug(_ message: String) {
        if config.diagnosticsEnabled { NSLog("[DigiaWEMapper] \(message)") }
    }
}
