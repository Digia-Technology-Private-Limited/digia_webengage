import Foundation

// MARK: - Contract extraction (mirrors DigiaInAppContract.kt)

/// Merges a Digia rendering contract extracted from `raw` into a copy of `raw`,
/// or returns `raw` unchanged when no contract is found.
internal func normalizeWithDigiaContract(_ raw: [String: Any]) -> [String: Any] {
    guard let contract = contractExtractor.extract(raw) else { return raw }
    return raw.merging(contract) { _, new in new }
}

private let contractExtractor = DigiaContractExtractor(sources: [
    TopLevelContractSource(),
    HtmlDigiaTagContractSource(),
])

// MARK: - Extractor

internal final class DigiaContractExtractor: @unchecked Sendable {
    private let sources: [ContractSource]
    init(sources: [ContractSource]) { self.sources = sources }

    func extract(_ raw: [String: Any]) -> [String: Any]? {
        for source in sources {
            if let contract = source.extract(raw) { return contract }
        }
        return nil
    }
}

internal protocol ContractSource {
    func extract(_ raw: [String: Any]) -> [String: Any]?
}

// MARK: - Top-level source

internal final class TopLevelContractSource: ContractSource {
    func extract(_ raw: [String: Any]) -> [String: Any]? {
        return buildContract(raw, source: "top_level")
    }
}

// MARK: - HTML <digia …> tag source

internal final class HtmlDigiaTagContractSource: ContractSource {
    func extract(_ raw: [String: Any]) -> [String: Any]? {
        for text in collectTextCandidates(raw) {
            for tagMap in DigiaTagParser.parseAll(html: text) {
                if let contract = buildContract(tagMap, source: "html_digia_tag") {
                    return contract
                }
            }
        }
        return nil
    }
}

// MARK: - Contract builder

private func buildContract(_ raw: [String: Any], source: String) -> [String: Any]? {
    let type = (raw["type"] as? String)?.trimmingCharacters(in: .whitespaces).lowercased().nilIfEmpty()
    let command = normalizeCommand(raw["command"] as? String)
    let resolvedCommand: String? = type == "inline" ? nil : command
    guard type != nil || resolvedCommand != nil else { return nil }

    guard let viewId = (raw["viewId"] as? String)?.trimmingCharacters(in: .whitespaces).nilIfEmpty()
    else { return nil }

    let placementKey = (raw["placementKey"] as? String)?.trimmingCharacters(in: .whitespaces).nilIfEmpty()
    let rawScreenId = raw["screenId"] ?? raw["screen_id"]
    let screenId = (rawScreenId as? String)?.trimmingCharacters(in: .whitespaces).nilIfEmpty()
    let args = normalizeArgs(raw["args"])

    if type == "inline" && placementKey == nil { return nil }

    var result: [String: Any] = [
        "viewId": viewId,
        "args": args,
        "digiaContractSource": source,
    ]
    if let t = type             { result["type"]         = t  }
    if let c = resolvedCommand  { result["command"]      = c  }
    if let pk = placementKey    { result["placementKey"] = pk }
    if let sid = screenId       { result["screenId"]     = sid }
    return result
}

internal func normalizeCommand(_ raw: String?) -> String? {
    switch raw?.trimmingCharacters(in: .whitespaces).uppercased() ?? "" {
    case "SHOW_INLINE":       return "SHOW_INLINE"
    case "SHOW_DIALOG":       return "SHOW_DIALOG"
    case "SHOW_BOTTOM_SHEET": return "SHOW_BOTTOM_SHEET"
    default: return nil
    }
}

internal func normalizeArgs(_ raw: Any?) -> [String: Any] {
    switch raw {
    case nil: return [:]
    case let map as [String: Any]: return map
    case let map as [AnyHashable: Any]:
        var out: [String: Any] = [:]
        map.forEach { if let k = $0.key as? String { out[k] = $0.value } }
        return out
    case let string as String:
        guard let data = string.data(using: .utf8),
              let obj = try? JSONSerialization.jsonObject(with: data),
              let dict = obj as? [String: Any] else { return [:] }
        return dict
    default: return [:]
    }
}

// MARK: - Text candidate collector

internal func collectTextCandidates(_ root: Any) -> [String] {
    var results: [String] = []
    var queue: [Any] = [root]
    while !queue.isEmpty {
        let node = queue.removeFirst()
        switch node {
        case let s as String:
            if s.range(of: "<digia", options: .caseInsensitive) != nil { results.append(s) }
        case let map as [String: Any]:
            queue.append(contentsOf: map.values)
        case let map as [AnyHashable: Any]:
            queue.append(contentsOf: map.values)
        case let list as [Any]:
            queue.append(contentsOf: list)
        default: break
        }
    }
    return results
}

// MARK: - String helper

internal extension String {
    func nilIfEmpty() -> String? { isEmpty ? nil : self }
}
