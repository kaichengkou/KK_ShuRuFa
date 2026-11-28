import Foundation

/// Rules of the Russian and English keyboards that only look at the text before the caret.
public enum TextRules {
    /// A sentence starts here: at the start of the text, after a new line, or after . ! ? … and a space.
    public static func sentenceStart(_ before: String?) -> Bool {
        guard let before, !before.isEmpty else { return true }
        if before.last == "\n" { return true }
        let trimmed = before.reversed().drop(while: { $0 == " " || $0 == "\u{00A0}" })
        guard trimmed.count < before.count else { return false }   // no space right before the caret
        guard let c = trimmed.first else { return true }
        return ".!?…。！？\n".contains(c)
    }

    /// A second space right after a word turns into ". " (as on the Android keyboard).
    /// Returns how many characters to delete before inserting ". ", or nil to type a plain space.
    public static func doubleSpacePeriod(_ before: String?) -> Int? {
        guard let before, before.hasSuffix(" "), !before.hasSuffix("  ") else { return nil }
        let prev = before.dropLast().last
        guard let p = prev, p.isLetter || p.isNumber || "»\")".contains(p) else { return nil }
        return 1
    }

    /// The letters (and inner hyphens, apostrophes) of the word right before the caret.
    public static func currentWord(_ before: String?) -> String {
        guard let before else { return "" }
        var out: [Character] = []
        for c in before.reversed() {
            if c.isLetter || c == "-" || c == "'" || c == "’" { out.append(c) } else { break }
        }
        let marks: Set<Character> = ["-", "'", "’"]
        var word = String(out.reversed())
        while let f = word.first, marks.contains(f) { word.removeFirst() }
        while let l = word.last, marks.contains(l) { word.removeLast() }
        return word
    }

    /// A paired symbol from the symbol panel ("（）", "«»") goes in as a pair with the caret inside.
    public static func pairSplit(_ s: String) -> (open: String, close: String)? {
        let pairs: Set<String> = ["“”", "‘’", "（）", "【】", "《》", "〈〉", "「」", "『』", "〔〕", "〖〗",
                                  "[]", "{}", "«»", "„“", "⟨⟩", "⟦⟧", "〘〙", "〚〛", "⦅⦆", "❨❩", "❪❫",
                                  "❬❭", "❮❯", "❰❱", "❲❳", "﹁﹂", "﹃﹄", "︵︶", "︷︸", "︹︺", "︻︼", "︽︾", "︿﹀"]
        guard s.count == 2, pairs.contains(s) else { return nil }
        return (String(s.first!), String(s.last!))
    }
}
