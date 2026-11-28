import Foundation

/// Word completion for Russian and English from the Android app's tables (assets/data/<lang>_words.txt:
/// `word<TAB>how common (1..250)[<TAB>how it is written]`, sorted by the word's UTF-8 bytes).
/// The file is searched in place, so the keyboard holds the bytes and nothing per word.
public final class WordList {
    private let bytes: [UInt8]
    /// Where each line starts.
    private let starts: [Int]

    public init(data: Data) {
        bytes = [UInt8](data)
        var s: [Int] = []
        s.reserveCapacity(bytes.count / 12)
        var lineStart = 0
        for (i, b) in bytes.enumerated() where b == 0x0A {
            if i > lineStart { s.append(lineStart) }
            lineStart = i + 1
        }
        if lineStart < bytes.count { s.append(lineStart) }
        starts = s
    }

    public convenience init?(url: URL) {
        guard let d = try? Data(contentsOf: url, options: .mappedIfSafe) else { return nil }
        self.init(data: d)
    }

    public var count: Int { starts.count }

    private func lineEnd(_ start: Int) -> Int {
        var i = start
        while i < bytes.count && bytes[i] != 0x0A { i += 1 }
        return i
    }

    /// The word of line [i], its weight and its written form.
    private func entry(_ i: Int) -> (word: ArraySlice<UInt8>, weight: Int, form: String?) {
        let s = starts[i], e = lineEnd(s)
        let fields = bytes[s..<e].split(separator: 0x09, omittingEmptySubsequences: false)
        let word = fields[0]
        let weight = fields.count > 1 ? Int(String(decoding: fields[1], as: UTF8.self)) ?? 0 : 0
        let form = fields.count > 2 ? String(decoding: fields[2], as: UTF8.self) : nil
        return (word, weight, form)
    }

    private func less(_ a: ArraySlice<UInt8>, _ b: [UInt8]) -> Bool {
        a.lexicographicallyPrecedes(b)
    }

    /// The first line whose word is not less than [key].
    private func lowerBound(_ key: [UInt8]) -> Int {
        var lo = 0, hi = starts.count
        while lo < hi {
            let mid = (lo + hi) / 2
            if less(entry(mid).word, key) { lo = mid + 1 } else { hi = mid }
        }
        return lo
    }

    public func contains(_ word: String) -> Bool {
        let key = Array(word.lowercased().utf8)
        let i = lowerBound(key)
        return i < starts.count && Array(entry(i).word) == key
    }

    /// Words starting with [prefix] (any case), most common first; the typed word itself first when
    /// it is a word, so a finished word can be confirmed with a tap. Written as typed: a capital
    /// first letter stays.
    public func complete(_ prefix: String, max: Int = 8) -> [String] {
        let low = prefix.lowercased()
        guard !low.isEmpty else { return [] }
        let key = Array(low.utf8)
        var found: [(String, Int)] = []
        var i = lowerBound(key)
        var scanned = 0
        while i < starts.count && scanned < 4000 {
            let e = entry(i)
            guard e.word.starts(with: key) else { break }
            let w = e.form ?? String(decoding: e.word, as: UTF8.self)
            found.append((w, Array(e.word) == key ? Int.max : e.weight))
            i += 1; scanned += 1
        }
        found.sort { $0.1 > $1.1 }
        let capital = prefix.first?.isUppercase == true
        let all = prefix.count > 1 && prefix == prefix.uppercased() && prefix != prefix.lowercased()
        var out: [String] = []
        for (w, _) in found {
            var s = w
            if all { s = w.uppercased() } else if capital, let f = s.first { s = f.uppercased() + s.dropFirst() }
            if !out.contains(s) { out.append(s) }
            if out.count == max { break }
        }
        return out
    }
}
