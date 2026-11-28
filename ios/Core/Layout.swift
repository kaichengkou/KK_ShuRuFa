import Foundation
import Yams

/// Keyboard layouts, read from the same YAML files as the Android app (app/src/main/assets/layouts),
/// so a change to a layout changes both keyboards. See LayoutRepo in Layout.kt for the format.

public enum Dir: Int, CaseIterable, Sendable { case up, down, left, right }

/// What a key (or one of its gestures) puts on screen.
public struct Output: Equatable, Sendable {
    public let label: String
    public let text: String
    public init(_ label: String, _ text: String) { self.label = label; self.text = text }
    public static func of(_ s: String) -> Output { Output(s, s) }
}

/// normal: solid (letters, digits); fn/soft: translucent (functions, symbols); action: accent (enter); tint: a way to another panel.
public enum KeyStyle: String, Sendable { case normal, fn = "func", soft, action, tint }

public struct Key: Equatable, Sendable {
    public var main: Output?
    public var flicks: [Dir: Output] = [:]
    public var long: [Output] = []
    public var fn: String?
    public var label: String?
    public var width: Double = 1
    public var style: KeyStyle = .normal
    public var spacer = false

    public init(main: Output? = nil, flicks: [Dir: Output] = [:], long: [Output] = [], fn: String? = nil,
                label: String? = nil, width: Double = 1, style: KeyStyle? = nil, spacer: Bool = false) {
        self.main = main; self.flicks = flicks; self.long = long; self.fn = fn
        self.label = label; self.width = width; self.spacer = spacer
        self.style = style ?? Key.defaultStyle(fn)
    }

    static func defaultStyle(_ fn: String?) -> KeyStyle {
        switch fn {
        case nil, "space": return .normal
        case "enter": return .action
        default: return .fn
        }
    }

    public var isLetter: Bool {
        guard let t = main?.text, t.count == 1, let c = t.first else { return false }
        return c.isLetter
    }

    /// What a long press offers: the other case of a letter, its flick characters, then the listed ones.
    public var alternates: [Output] {
        var out: [Output] = []
        func add(_ o: Output) { if !out.contains(o) && o != main { out.append(o) } }
        if isLetter, let t = main?.text { add(.of(t.uppercased())) }
        for d in Dir.allCases { if let f = flicks[d] { add(f) } }
        long.forEach(add)
        return out
    }
}

public struct KeyboardLayout: Sendable {
    public let id: String
    public let label: String
    /// "rime" (Chinese through Rime) or "direct" (letters go straight to the text).
    public let engine: String
    public var rows: [[Key]]

    /// The widest row, in key widths.
    public var units: Double { rows.map { $0.reduce(0) { $0 + $1.width } }.max() ?? 10 }
}

public struct SymbolCategory: Sendable {
    public let id: String
    public let name: String
    public let items: [Output]
}

public enum LayoutError: Error { case missing(String), malformed(String) }

public struct LayoutRepo {
    /// Reads the text of a layout file by name ("zh.yaml").
    let source: (String) throws -> String

    public init(source: @escaping (String) throws -> String) { self.source = source }

    /// Layouts bundled as resources of [bundle] (the keyboard copies them from the Android assets).
    public init(bundle: Bundle) {
        self.source = { name in
            let base = (name as NSString).deletingPathExtension
            guard let url = bundle.url(forResource: base, withExtension: "yaml", subdirectory: "layouts")
                    ?? bundle.url(forResource: base, withExtension: "yaml") else { throw LayoutError.missing(name) }
            return try String(contentsOf: url, encoding: .utf8)
        }
    }

    private func read(_ name: String) throws -> [String: Any] {
        guard let m = try Yams.load(yaml: source(name)) as? [String: Any] else { throw LayoutError.malformed(name) }
        return m
    }

    public func keyboard(_ id: String) throws -> KeyboardLayout {
        let m = try read("\(id).yaml")
        var extraLong: [String: [Output]] = [:]
        if let l = m["long"] as? [AnyHashable: Any] {
            for (k, v) in l { extraLong["\(k)"] = outputs(v) }
        }
        guard let rows = m["rows"] as? [Any] else { throw LayoutError.malformed(id) }
        let parsed = rows.map { row in ((row as? [Any]) ?? []).map { parseKey($0, extraLong) } }
        return KeyboardLayout(id: id, label: (m["label"].map { "\($0)" }) ?? id,
                              engine: (m["engine"].map { "\($0)" }) ?? "direct", rows: parsed)
    }

    public func symbols() throws -> [SymbolCategory] {
        let m = try read("symbols.yaml")
        guard let cats = m["categories"] as? [Any] else { throw LayoutError.malformed("symbols") }
        return cats.compactMap { c in
            guard let c = c as? [String: Any] else { return nil }
            let items: [Output]
            switch c["items"] {
            case let s as String: items = s.split(whereSeparator: { $0.isWhitespace }).map { .of(String($0)) }
            case let l as [Any]:
                items = l.map { item in
                    if let d = item as? [String: Any] {
                        return Output("\(d["label"] ?? "")", unescape("\(d["text"] ?? "")"))
                    }
                    return .of("\(item)")
                }
            default: items = []
            }
            return SymbolCategory(id: "\(c["id"] ?? "")", name: "\(c["name"] ?? "")", items: items)
        }
    }

    private func unescape(_ s: String) -> String { s.replacingOccurrences(of: "\\u00A0", with: "\u{00A0}") }

    private func outputs(_ v: Any?) -> [Output] {
        switch v {
        case let l as [Any]: return l.map { .of("\($0)") }
        case let s as String: return s.split(whereSeparator: { $0.isWhitespace }).map { .of(String($0)) }
        default: return []
        }
    }

    /// "a|～" → ["a", "～"]; "\|" is a literal bar.
    static func splitPipes(_ s: String) -> [String] {
        var parts: [String] = []
        var cur = ""
        let it = Array(s)
        var i = 0
        while i < it.count {
            let c = it[i]
            if c == "\\", i + 1 < it.count, it[i + 1] == "|" { cur.append("|"); i += 2; continue }
            if c == "|" { parts.append(cur); cur = "" } else { cur.append(c) }
            i += 1
        }
        parts.append(cur)
        return parts
    }

    private func flicks(from parts: [String]) -> [Dir: Output] {
        var r: [Dir: Output] = [:]
        for (i, p) in parts.dropFirst().enumerated() where !p.isEmpty && i < 4 {
            r[Dir.allCases[i]] = .of(p)
        }
        return r
    }

    private func number(_ v: Any?) -> Double? {
        switch v {
        case let d as Double: return d
        case let i as Int: return Double(i)
        case let s as String: return Double(s)
        default: return nil
        }
    }

    private func parseKey(_ node: Any?, _ extraLong: [String: [Output]]) -> Key {
        switch node {
        case let s as String:
            let parts = LayoutRepo.splitPipes(s)
            return Key(main: .of(parts[0]), flicks: flicks(from: parts), long: extraLong[parts[0]] ?? [])
        case let m as [String: Any]:
            let w = number(m["w"]) ?? 1
            if m["spacer"] != nil {
                return Key(width: number(m["spacer"]) ?? 1, spacer: true)
            }
            if let fn = m["fn"] {
                let f = "\(fn)"
                return Key(long: outputs(m["long"]), fn: f, label: m["label"].map { "\($0)" }, width: w,
                           style: (m["style"] as? String).flatMap(KeyStyle.init(rawValue:)) ?? Key.defaultStyle(f))
            }
            let main = "\(m["key"] ?? "")"
            var fl: [Dir: Output] = [:]
            for (k, d) in [("up", Dir.up), ("down", .down), ("left", .left), ("right", .right)] {
                if let v = m[k] { fl[d] = .of("\(v)") }
            }
            return Key(main: .of(main), flicks: fl, long: outputs(m["long"]) + (extraLong[main] ?? []),
                       label: m["label"].map { "\($0)" }, width: w,
                       style: (m["style"] as? String).flatMap(KeyStyle.init(rawValue:)) ?? .normal)
        case nil:
            return Key(spacer: true)
        case let other?:
            return parseKey("\(other)", extraLong)
        }
    }
}
