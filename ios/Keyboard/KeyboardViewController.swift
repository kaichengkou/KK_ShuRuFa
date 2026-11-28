import UIKit
import KKCore

/// The input view: plays the key clicks the system lets a keyboard play.
final class KKInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

/// KK输入法 on iOS: 中文 through Rime (雾凇拼音), Русский and English typed directly with word
/// completion, and the symbol panel, on the layouts the Android app uses.
final class KeyboardViewController: UIInputViewController, KeyboardViewDelegate {
    private let settings = Settings()
    private lazy var repo = LayoutRepo(bundle: .main)
    private var layouts: [String: KeyboardLayout] = [:]
    /// Whether the layouts above were made with a globe key (the system tells only once the view is up).
    private var layoutsHaveGlobe: Bool?
    private var language = "zh"

    private let bar = CandidateBar()
    private let keys = KeyboardView()
    private let symbols = SymbolPanel()
    private let popup = PopupView()
    private var heightConstraint: NSLayoutConstraint?

    private var words: [String: WordList] = [:]
    /// What the candidate bar offers on the Russian and English keyboards (completions of the word).
    private var completions: [String] = []
    private var lastShiftTap: TimeInterval = 0
    private let haptic = UIImpactFeedbackGenerator(style: .light)
    /// Letters typed while Rime was still starting, typed into it once it is up.
    private var pendingPinyin: [Int32] = []

    private static let barHeight: CGFloat = 46
    private static let rowHeight: CGFloat = 54

    // MARK: - life cycle

    override func loadView() {
        let v = KKInputView(frame: .zero, inputViewStyle: .keyboard)
        v.allowsSelfSizing = true
        view = v
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        for v in [bar, keys, symbols, popup] as [UIView] {
            v.translatesAutoresizingMaskIntoConstraints = false
            view.addSubview(v)
        }
        let h = view.heightAnchor.constraint(equalToConstant: Self.barHeight + 4 * Self.rowHeight)
        h.priority = .init(999)
        heightConstraint = h
        NSLayoutConstraint.activate([
            h,
            bar.topAnchor.constraint(equalTo: view.topAnchor),
            bar.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            bar.heightAnchor.constraint(equalToConstant: Self.barHeight),
            keys.topAnchor.constraint(equalTo: bar.bottomAnchor),
            keys.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            keys.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            keys.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            symbols.topAnchor.constraint(equalTo: view.topAnchor),
            symbols.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            symbols.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            symbols.bottomAnchor.constraint(equalTo: view.bottomAnchor),
            popup.topAnchor.constraint(equalTo: view.topAnchor),
            popup.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            popup.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            popup.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        symbols.isHidden = true
        keys.delegate = self
        keys.popupHost = popup
        bar.onPick = { [weak self] i in self?.pick(i) }
        bar.onDismiss = { [weak self] in self?.dismissKeyboard() }
        symbols.onSymbol = { [weak self] s in self?.typeSymbol(s) }
        symbols.onBack = { [weak self] in self?.showSymbols(false) }
        symbols.onDelete = { [weak self] in self?.textDocumentProxy.deleteBackward() }
        symbols.onSpace = { [weak self] in self?.textDocumentProxy.insertText(" ") }
        symbols.onEnter = { [weak self] in self?.textDocumentProxy.insertText("\n") }
        if let cats = try? repo.symbols() { symbols.load(cats, first: "zh") }

        let saved = UserDefaults.standard.string(forKey: "language")
        let langs = settings.languages
        switchTo(saved.flatMap { langs.contains($0) ? $0 : nil } ?? langs.first ?? "zh")
        applyTheme()
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if layoutsHaveGlobe != needsInputModeSwitchKey {
            layouts.removeAll()
            layoutsHaveGlobe = needsInputModeSwitchKey
            keys.layout = layout(language)
        }
        if language == "zh" { Rime.shared.start() }
        refreshFieldLabels()
        updateAutoShift()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        // what was typed but not chosen goes away with the keyboard, as on Android
        if Rime.shared.ready { Rime.shared.clear() }
        showBar()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        applyTheme()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        refreshFieldLabels()
        updateAutoShift()
        if language != "zh" { updateCompletions() }
    }

    private func applyTheme() {
        let t = Theme.current(traitCollection)
        keys.theme = t
        bar.theme = t
        symbols.theme = t
        popup.theme = t
        view.backgroundColor = t.background
    }

    /// The enter key names what it does in this field; the space bar names the keyboard.
    private func refreshFieldLabels() {
        let enter: String
        switch textDocumentProxy.returnKeyType ?? .default {
        case .send: enter = language == "zh" ? "发送" : (language == "ru" ? "Отпр." : "send")
        case .search, .google, .yahoo: enter = language == "zh" ? "搜索" : (language == "ru" ? "Найти" : "search")
        case .go, .route: enter = language == "zh" ? "前往" : "go"
        case .done: enter = language == "zh" ? "完成" : "done"
        case .next: enter = language == "zh" ? "下一项" : "next"
        case .join: enter = language == "zh" ? "加入" : "join"
        default: enter = language == "zh" ? "换行" : "⏎"
        }
        var labels: [String: String] = ["enter": enter, "space": layouts[language]?.label ?? ""]
        labels["language"] = ["zh": "中", "en": "En", "ru": "Ру"][language] ?? language
        keys.fnLabels = labels
        symbols.setEnterLabel(enter)
    }

    // MARK: - layouts

    private func layout(_ id: String) -> KeyboardLayout? {
        if let l = layouts[id] { return l }
        guard var l = try? repo.keyboard(id) else { return nil }
        // iOS asks for a key to the next keyboard where the system shows none (no globe key below)
        if needsInputModeSwitchKey, let last = l.rows.indices.last,
           let i = l.rows[last].firstIndex(where: { $0.fn == "language" }) {
            l.rows[last].insert(Key(fn: "globe"), at: i)
            if let s = l.rows[last].firstIndex(where: { $0.fn == "space" }) { l.rows[last][s].width -= 1 }
        }
        layouts[id] = l
        return l
    }

    private func switchTo(_ id: String) {
        guard let l = layout(id) else { return }
        commitComposition()
        language = id
        UserDefaults.standard.set(id, forKey: "language")
        keys.layout = l
        keys.shift = .off
        completions = []
        if l.engine == "rime" {
            Rime.shared.start { [weak self] in self?.rimeReady() }
            Rime.shared.setOptionIfReady("traditionalization", settings.traditional)
        } else if settings.wordCompletion, words[id] == nil,
                  let url = Bundle.main.url(forResource: "\(id)_words", withExtension: "txt") {
            words[id] = WordList(url: url)
        }
        refreshFieldLabels()
        updateAutoShift()
        showBar()
    }

    private func rimeReady() {
        Rime.shared.setOption("traditionalization", settings.traditional)
        let queued = pendingPinyin
        pendingPinyin.removeAll()
        queued.forEach { Rime.shared.key($0) }
        afterRime()
    }

    // MARK: - KeyboardViewDelegate

    func keyboardViewFeedback(_ view: KeyboardView) {
        if settings.sound { UIDevice.current.playInputClick() }
        if settings.haptics && hasFullAccess { haptic.impactOccurred() }
    }

    func keyboardView(_ view: KeyboardView, typed output: Output, from key: Key) {
        let text = output.text
        if language == "zh" {
            if let code = pinyinCode(text, key: key) {
                if Rime.shared.ready { Rime.shared.key(code); afterRime() } else { pendingPinyin.append(code); Rime.shared.start() }
                return
            }
            commitComposition()
            textDocumentProxy.insertText(text)
            return
        }
        textDocumentProxy.insertText(text)
        if keys.shift == .once { keys.shift = .off }
        updateAutoShift()
        updateCompletions()
    }

    /// A small Latin letter (or the syllable mark ' while typing) goes to Rime; everything else is typed as it is.
    private func pinyinCode(_ text: String, key: Key) -> Int32? {
        guard text.unicodeScalars.count == 1, let u = text.unicodeScalars.first else { return nil }
        if ("a"..."z").contains(text) { return Int32(u.value) }
        if text == "'" && (Rime.shared.ready && Rime.shared.composing || !pendingPinyin.isEmpty) { return Int32(u.value) }
        return nil
    }

    func keyboardView(_ view: KeyboardView, function fn: String, key: Key) {
        switch fn {
        case "backspace": backspace()
        case "space": space()
        case "enter": enter()
        case "shift": shiftTapped()
        case "symbols": showSymbols(true)
        case "language": switchTo(settings.next(after: language))
        case "globe": advanceToNextInputMode()
        default: break
        }
    }

    func keyboardView(_ view: KeyboardView, moveCaret offset: Int) {
        if composing { return }
        textDocumentProxy.adjustTextPosition(byCharacterOffset: offset)
    }

    // MARK: - keys

    private var composing: Bool { language == "zh" && (Rime.shared.ready && Rime.shared.composing || !pendingPinyin.isEmpty) }

    private func backspace() {
        if language == "zh" && !pendingPinyin.isEmpty { pendingPinyin.removeLast(); showBar(); return }
        if composing { Rime.shared.key(Rime.backspace); afterRime(); return }
        textDocumentProxy.deleteBackward()
        if language != "zh" { updateAutoShift(); updateCompletions() }
    }

    private func space() {
        if composing { Rime.shared.key(Rime.space); afterRime(); return }
        if language != "zh", settings.doubleSpacePeriod,
           let n = TextRules.doubleSpacePeriod(textDocumentProxy.documentContextBeforeInput) {
            for _ in 0..<n { textDocumentProxy.deleteBackward() }
            textDocumentProxy.insertText(". ")
        } else {
            textDocumentProxy.insertText(" ")
        }
        updateAutoShift()
        if language != "zh" { completions = []; showBar() }
    }

    /// Enter while typing pinyin puts the letters themselves; otherwise a new line (or the field's action).
    private func enter() {
        if composing {
            let raw = Rime.shared.input
            Rime.shared.clear()
            textDocumentProxy.insertText(raw)
            showBar()
            return
        }
        textDocumentProxy.insertText("\n")
        updateAutoShift()
    }

    private func shiftTapped() {
        let now = CACurrentMediaTime()
        switch keys.shift {
        case .off: keys.shift = .once
        case .once: keys.shift = now - lastShiftTap < 0.35 ? .locked : .off
        case .locked: keys.shift = .off
        }
        lastShiftTap = now
    }

    private func updateAutoShift() {
        guard language != "zh", keys.shift != .locked else { return }
        let type = textDocumentProxy.autocapitalizationType ?? .sentences
        let on = settings.autoCapitalize && type != UITextAutocapitalizationType.none
            && TextRules.sentenceStart(textDocumentProxy.documentContextBeforeInput)
        keys.shift = on || type == .allCharacters ? .once : .off
    }

    private func showSymbols(_ on: Bool) {
        if on { commitComposition() }
        symbols.isHidden = !on
        keys.isHidden = on
        bar.isHidden = on
    }

    private func typeSymbol(_ s: String) {
        if let pair = TextRules.pairSplit(s) {
            textDocumentProxy.insertText(pair.open + pair.close)
            textDocumentProxy.adjustTextPosition(byCharacterOffset: -1)
        } else {
            textDocumentProxy.insertText(s)
        }
    }

    // MARK: - candidates

    /// After every key Rime took: type what it committed and show what it offers.
    private func afterRime() {
        let c = Rime.shared.commit
        if !c.isEmpty { textDocumentProxy.insertText(c) }
        showBar()
    }

    /// Chooses the first candidate of what is being typed (before a symbol, or another keyboard).
    private func commitComposition() {
        guard language == "zh", Rime.shared.ready, Rime.shared.composing else { return }
        if Rime.shared.candidates(1).isEmpty {
            let raw = Rime.shared.input
            Rime.shared.clear()
            textDocumentProxy.insertText(raw)
        } else {
            Rime.shared.select(0)
            let c = Rime.shared.commit
            if !c.isEmpty { textDocumentProxy.insertText(c) }
            // a choice that covered only part of the pinyin leaves the rest: it goes in as letters
            if Rime.shared.composing {
                let rest = Rime.shared.input
                Rime.shared.clear()
                textDocumentProxy.insertText(rest)
            }
        }
        showBar()
    }

    private func pick(_ index: Int) {
        if language == "zh" {
            guard Rime.shared.ready else { return }
            Rime.shared.select(index)
            afterRime()
            return
        }
        guard index < completions.count else { return }
        let word = TextRules.currentWord(textDocumentProxy.documentContextBeforeInput)
        for _ in 0..<word.count { textDocumentProxy.deleteBackward() }
        textDocumentProxy.insertText(completions[index] + " ")
        completions = []
        updateAutoShift()
        showBar()
    }

    private func updateCompletions() {
        guard language != "zh", settings.wordCompletion, let list = words[language] else { completions = []; showBar(); return }
        let word = TextRules.currentWord(textDocumentProxy.documentContextBeforeInput)
        completions = word.isEmpty ? [] : list.complete(word)
        showBar()
    }

    private func showBar() {
        if language == "zh" {
            if !pendingPinyin.isEmpty {
                let typed = String(String.UnicodeScalarView(pendingPinyin.compactMap { UnicodeScalar(UInt32($0)) }))
                bar.show(preedit: typed + "  词库加载中…", candidates: [])
            } else if Rime.shared.ready && Rime.shared.composing {
                bar.show(preedit: Rime.shared.preedit, candidates: Rime.shared.candidates(60).map(\.text))
            } else {
                bar.show(preedit: "", candidates: [])
            }
        } else {
            bar.show(preedit: "", candidates: completions)
        }
    }
}

private extension Rime {
    func setOptionIfReady(_ name: String, _ on: Bool) { if ready { setOption(name, on) } }
}
