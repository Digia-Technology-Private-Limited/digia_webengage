import Foundation
import DigiaEngage

/// Translates `DigiaExperienceEvent` + `InAppPayload` into WebEngage analytics calls.
/// Mirrors `WebEngageEventDispatcher.kt` on Android.
@MainActor
internal final class WebEngageEventDispatcher {

    private let bridge: WebEngageBridgeProtocol
    private let cache: InAppDataCache

    init(bridge: WebEngageBridgeProtocol, cache: InAppDataCache) {
        self.bridge = bridge
        self.cache = cache
    }

    // MARK: - Public

    func forwardScreen(_ name: String) {
        bridge.navigateScreen(name)
    }

    /// Returns `true` when the event was dispatched.
    @discardableResult
    func dispatch(event: DigiaExperienceEvent, payload: InAppPayload) -> Bool {
        if payload.content.type == "inline" {
            dispatchInlineEvent(event: event, payload: payload)
            return true
        }
        return dispatchInAppEvent(event: event, payload: payload)
    }

    // MARK: - In-App

    private func dispatchInAppEvent(event: DigiaExperienceEvent, payload: InAppPayload) -> Bool {
        let experimentId: String = {
            let fromCtx = payload.cepContext["experimentId"] ?? payload.cepContext["campaignId"]
            return fromCtx ?? payload.id.components(separatedBy: ":").first ?? payload.id
        }()

        let cachedData = cache.get(experimentId: experimentId)
        let weExperimentId = str(cachedData?["experimentId"]) ?? experimentId
        let weVariationId  = str(cachedData?["variationId"])
                          ?? payload.cepContext["variationId"]
                          ?? payload.id

        let eventName: String
        switch event {
        case .impressed:  eventName = "notification_view"
        case .clicked:    eventName = "notification_click"
        case .dismissed:  eventName = "notification_close"
        }

        var systemData: [String: Any] = [
            "experiment_id": weExperimentId,
            "id": weVariationId,
        ]
        if case .clicked(let elementID) = event,
           let cta = elementID?.trimmingCharacters(in: .whitespaces),
           !cta.isEmpty {
            systemData["call_to_action"] = cta
        }

        bridge.trackSystemEvent(eventName: eventName, systemData: systemData, eventData: [:])

        if case .dismissed = event {
            cache.remove(experimentId: experimentId)
            NSLog("[WebEngageEventDispatcher] dispatched: \(eventName) experimentId=\(experimentId) (evicted)")
        } else {
            NSLog("[WebEngageEventDispatcher] dispatched: \(eventName) experimentId=\(experimentId)")
        }
        return true
    }

    // MARK: - Inline

    private func dispatchInlineEvent(event: DigiaExperienceEvent, payload: InAppPayload) {
        if case .dismissed = event { return }

        let experimentId: String = {
            let fromCtx = payload.cepContext["experimentId"] ?? payload.cepContext["campaignId"]
            return fromCtx ?? payload.id.components(separatedBy: ":").first ?? payload.id
        }()
        let cachedData = cache.get(experimentId: experimentId)
        let weExperimentId = str(cachedData?["experimentId"]) ?? experimentId
        let weVariationId  = str(cachedData?["variationId"])
                          ?? payload.cepContext["variationId"]
                          ?? payload.id

        var systemData: [String: Any] = [
            "experiment_id": weExperimentId,
            "id": weVariationId,
        ]

        switch event {
        case .impressed:
            bridge.trackSystemEvent(eventName: "notification_view", systemData: systemData, eventData: [:])
            bridge.trackSystemEvent(eventName: "notification_close", systemData: systemData, eventData: [:])
            cache.remove(experimentId: experimentId)
            NSLog("[WebEngageEventDispatcher] dispatched: notification_view + notification_close (inline) experimentId=\(experimentId)")
        case .clicked(let elementID):
            if let cta = elementID?.trimmingCharacters(in: .whitespaces), !cta.isEmpty {
                systemData["call_to_action"] = cta
            }
            bridge.trackSystemEvent(eventName: "notification_click", systemData: systemData, eventData: [:])
            NSLog("[WebEngageEventDispatcher] dispatched: notification_click (inline) experimentId=\(experimentId)")
        case .dismissed:
            break
        }
    }

    // MARK: - JSONValue → Any

    private func jsonValueToAny(_ value: JSONValue) -> Any? {
        switch value {
        case .null:             return nil
        case .bool(let b):      return b
        case .int(let n):       return n
        case .double(let n):    return n
        case .string(let s):    return s
        case .object(let d):    return d.compactMapValues { jsonValueToAny($0) }
        case .array(let a):     return a.compactMap { jsonValueToAny($0) }
        }
    }

    // MARK: - Helper

    private func str(_ value: Any?) -> String? {
        guard let s = (value as? String)?.trimmingCharacters(in: .whitespaces), !s.isEmpty else { return nil }
        return s
    }
}
