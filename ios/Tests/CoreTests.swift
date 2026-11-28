import XCTest
@testable import KKCore

final class LayoutTests: XCTestCase {
    private var repo: LayoutRepo { LayoutRepo(bundle: Bundle(for: LayoutTests.self)) }

    /// The Android layouts read the same here: rows, flicks, long-press lists, function keys.
    func testChineseLayout() throws {
        let zh = try repo.keyboard("zh")
        XCTAssertEqual(zh.engine, "rime")
        XCTAssertEqual(zh.rows.count, 4)
        let q = zh.rows[0][0]
        XCTAssertEqual(q.main?.text, "q")
        XCTAssertEqual(q.flicks[.up]?.text, "1")
        XCTAssertTrue(zh.rows[1].first!.spacer)
        let bottom = zh.rows[3]
        XCTAssertEqual(bottom.first?.fn, "symbols")
        XCTAssertEqual(bottom.first?.label, "!#1")
        XCTAssertEqual(bottom.first?.width, 1.5)
        let comma = bottom.first { $0.main?.text == "，" }!
        XCTAssertEqual(comma.flicks[.up]?.text, "、")
        XCTAssertEqual(comma.style, .soft)
        XCTAssertTrue(comma.long.contains(.of("……")))
        XCTAssertEqual(zh.units, 10.5, accuracy: 0.01)
    }

    func testRussianLongPress() throws {
        let ru = try repo.keyboard("ru")
        XCTAssertEqual(ru.engine, "direct")
        let e = ru.rows.flatMap { $0 }.first { $0.main?.text == "е" }!
        XCTAssertEqual(e.long, [.of("ё")])
        XCTAssertEqual(e.alternates.first?.text, "Е")
        XCTAssertTrue(e.alternates.contains(.of("ё")))
        XCTAssertEqual(ru.units, 11, accuracy: 0.01)
    }

    func testEscapedPipeAndQuotes() {
        XCTAssertEqual(LayoutRepo.splitPipes("a|b"), ["a", "b"])
        XCTAssertEqual(LayoutRepo.splitPipes("\\||x"), ["|", "x"])
        XCTAssertEqual(LayoutRepo.splitPipes("q|1||3"), ["q", "1", "", "3"])
    }

    func testSymbols() throws {
        let cats = try repo.symbols()
        XCTAssertEqual(cats.first?.id, "zh")
        XCTAssertTrue(cats.first!.items.contains(.of("（）")))
        let ru = cats.first { $0.id == "ru" }!
        XCTAssertTrue(ru.items.contains(Output("⍽", "\u{00A0}")))
    }

    func testEveryLayoutLoads() throws {
        for id in ["zh", "en", "ru"] {
            let l = try repo.keyboard(id)
            XCTAssertFalse(l.rows.isEmpty, id)
            XCTAssertTrue(l.rows.joined().contains { $0.fn == "language" }, id)
            XCTAssertTrue(l.rows.joined().contains { $0.fn == "enter" }, id)
        }
    }
}

final class WordListTests: XCTestCase {
    private func list(_ lang: String) -> WordList {
        WordList(url: Bundle(for: WordListTests.self).url(forResource: "\(lang)_words", withExtension: "txt")!)!
    }

    func testCompletesRussian() {
        let ru = list("ru")
        XCTAssertGreaterThan(ru.count, 60_000)
        let c = ru.complete("прив")
        XCTAssertTrue(c.contains("привет"), "\(c)")
        XCTAssertTrue(ru.contains("привет"))
        XCTAssertFalse(ru.contains("рпивет"))
    }

    func testTypedWordFirstAndCase() {
        let en = list("en")
        XCTAssertEqual(en.complete("the").first, "the")
        XCTAssertEqual(en.complete("The").first, "The")
        XCTAssertEqual(en.complete("THE").first, "THE")
        XCTAssertTrue(en.complete("").isEmpty)
    }

    func testSmallTable() {
        let w = WordList(data: Data("a\t10\nab\t5\nabc\t200\nb\t1\n".utf8))
        XCTAssertEqual(w.complete("a"), ["a", "abc", "ab"])
        XCTAssertEqual(w.complete("b"), ["b"])
        XCTAssertEqual(w.complete("c"), [])
    }
}

final class TextRulesTests: XCTestCase {
    func testSentenceStart() {
        XCTAssertTrue(TextRules.sentenceStart(nil))
        XCTAssertTrue(TextRules.sentenceStart(""))
        XCTAssertTrue(TextRules.sentenceStart("Привет. "))
        XCTAssertTrue(TextRules.sentenceStart("line\n"))
        XCTAssertFalse(TextRules.sentenceStart("Привет "))
        XCTAssertFalse(TextRules.sentenceStart("end."))
    }

    func testDoubleSpace() {
        XCTAssertEqual(TextRules.doubleSpacePeriod("word "), 1)
        XCTAssertNil(TextRules.doubleSpacePeriod("word"))
        XCTAssertNil(TextRules.doubleSpacePeriod("word. "))
        XCTAssertNil(TextRules.doubleSpacePeriod("  "))
    }

    func testCurrentWord() {
        XCTAssertEqual(TextRules.currentWord("Привет, мир"), "мир")
        XCTAssertEqual(TextRules.currentWord("don't"), "don't")
        XCTAssertEqual(TextRules.currentWord("well-"), "well")
        XCTAssertEqual(TextRules.currentWord("end "), "")
    }

    func testPairs() {
        XCTAssertEqual(TextRules.pairSplit("（）")?.open, "（")
        XCTAssertEqual(TextRules.pairSplit("«»")?.close, "»")
        XCTAssertNil(TextRules.pairSplit("——"))
    }
}

final class SettingsTests: XCTestCase {
    func testLanguageCycle() {
        let d = UserDefaults(suiteName: "kk.tests.\(UUID().uuidString)")!
        let s = Settings(defaults: d)
        XCTAssertEqual(s.next(after: "zh"), "en")
        XCTAssertEqual(s.next(after: "ru"), "zh")
        s.languages = ["zh", "ru"]
        XCTAssertEqual(s.next(after: "zh"), "ru")
        XCTAssertEqual(s.next(after: "en"), "zh")
        XCTAssertTrue(s.autoCapitalize)
        s.autoCapitalize = false
        XCTAssertFalse(Settings(defaults: d).autoCapitalize)
    }
}
