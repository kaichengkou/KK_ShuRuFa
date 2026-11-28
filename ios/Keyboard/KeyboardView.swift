import UIKit
import KKCore

/// What the keys ask of the keyboard.
protocol KeyboardViewDelegate: AnyObject {
    /// A character key gave [output] (its main character, a flick, or a long-press choice).
    func keyboardView(_ view: KeyboardView, typed output: Output, from key: Key)
    /// A function key (backspace, enter, shift, space, symbols, language, globe) was used.
    func keyboardView(_ view: KeyboardView, function fn: String, key: Key)
    /// The space bar was dragged sideways: move the caret by [offset] characters.
    func keyboardView(_ view: KeyboardView, moveCaret offset: Int)
    /// A finger went down on a key (for the click and the vibration).
    func keyboardViewFeedback(_ view: KeyboardView)
}

enum ShiftState { case off, once, locked }

/// The key area, drawn by hand like the Android keyboard (KeyboardView.kt): rows of rounded keys
/// with their flick character in the corner. A tap types the key; a flick (up, down, left, right)
/// types the character for that direction; holding a key opens its long-press choices; holding
/// backspace repeats; dragging the space bar moves the caret.
final class KeyboardView: UIView {
    weak var delegate: KeyboardViewDelegate?

    var layout: KeyboardLayout? { didSet { setNeedsLayout(); setNeedsDisplay() } }
    var shift = ShiftState.off { didSet { if shift != oldValue { setNeedsDisplay() } } }
    var theme = Theme.light { didSet { backgroundColor = theme.background; setNeedsDisplay() } }
    /// Labels of function keys that depend on the text field (enter: 发送, 搜索 …) or the language.
    var fnLabels: [String: String] = [:] { didSet { setNeedsDisplay() } }

    /// The overlay that shows the key preview and the long-press choices above the keys.
    weak var popupHost: PopupView?

    private struct Placed { let key: Key; let frame: CGRect; let row: Int }
    private var placed: [Placed] = []

    private final class Track {
        let index: Int
        let start: CGPoint
        let began: TimeInterval
        var point: CGPoint
        var longTimer: Timer?
        var repeatTimer: Timer?
        var choices: [Output]?
        var choice = 0
        /// Characters the caret was moved by a space drag so far.
        var moved = 0
        var dragging = false
        init(index: Int, start: CGPoint) {
            self.index = index; self.start = start; self.point = start; self.began = CACurrentMediaTime()
        }
    }
    private var tracks: [ObjectIdentifier: Track] = [:]
    private var pressed: Set<Int> { Set(tracks.values.map(\.index)) }

    static let flickDistance: CGFloat = 22
    static let gap: CGFloat = 6

    override init(frame: CGRect) {
        super.init(frame: frame)
        isMultipleTouchEnabled = true
        contentMode = .redraw
        isOpaque = true
    }
    required init?(coder: NSCoder) { fatalError() }

    override func layoutSubviews() {
        super.layoutSubviews()
        placeKeys()
    }

    private func placeKeys() {
        placed.removeAll()
        guard let layout, bounds.width > 0 else { return }
        let rows = layout.rows.count
        let rowH = bounds.height / CGFloat(max(rows, 1))
        // each row fills the width, as on Android (KeyboardView.kt): its keys share it by their widths
        for (r, row) in layout.rows.enumerated() {
            let total = max(row.reduce(0) { $0 + CGFloat($1.width) }, 1)
            let unit = bounds.width / total
            var x: CGFloat = 0
            for key in row {
                let w = CGFloat(key.width) * unit
                if !key.spacer {
                    placed.append(Placed(key: key, frame: CGRect(x: x, y: CGFloat(r) * rowH, width: w, height: rowH), row: r))
                }
                x += w
            }
        }
        // keys at the ends of a row reach the edge, so a tap beside the keyboard still lands
        for i in placed.indices {
            var f = placed[i].frame
            if f.minX < 1 { f.size.width += f.minX; f.origin.x = 0 }
            if bounds.width - f.maxX < 1 { f.size.width = bounds.width - f.minX }
            placed[i] = Placed(key: placed[i].key, frame: f, row: placed[i].row)
        }
        setNeedsDisplay()
    }

    /// The key under [p], or the nearest one in its row (the gaps between keys belong to a key).
    private func keyIndex(at p: CGPoint) -> Int? {
        if let i = placed.firstIndex(where: { $0.frame.contains(p) }) { return i }
        return placed.indices.min(by: { dist(placed[$0].frame, p) < dist(placed[$1].frame, p) })
    }
    private func dist(_ r: CGRect, _ p: CGPoint) -> CGFloat {
        let dx = max(r.minX - p.x, 0, p.x - r.maxX), dy = max(r.minY - p.y, 0, p.y - r.maxY)
        return dx * dx + dy * dy
    }

    // MARK: - drawing

    private func label(for key: Key) -> String {
        if let fn = key.fn {
            if let l = fnLabels[fn] { return l }
            switch fn {
            case "backspace": return "⌫"
            case "shift": return shift == .locked ? "⇪" : "⇧"
            case "enter": return "换行"
            case "space": return ""
            case "globe": return "🌐"
            default: return key.label ?? fn
            }
        }
        let t = key.label ?? key.main?.label ?? ""
        return shift != .off && key.isLetter ? t.uppercased() : t
    }

    override func draw(_ rect: CGRect) {
        guard let ctx = UIGraphicsGetCurrentContext() else { return }
        theme.background.setFill()
        ctx.fill(bounds)
        let down = pressed
        for (i, p) in placed.enumerated() {
            let key = p.key
            let r = p.frame.insetBy(dx: KeyboardView.gap / 2, dy: KeyboardView.gap / 2 + 1)
            let isDown = down.contains(i)
            let fill: UIColor
            var textColor = theme.text
            switch key.style {
            case .action: fill = theme.action; textColor = theme.actionText
            case .fn, .soft, .tint: fill = isDown ? theme.key : theme.fnKey
            case .normal: fill = isDown ? theme.keyPressed : theme.key
            }
            let lit = key.fn == "shift" && shift != .off
            // a soft shadow under each key, as on the system keyboard
            let path = UIBezierPath(roundedRect: r, cornerRadius: 6)
            theme.shadow.setFill()
            UIBezierPath(roundedRect: r.offsetBy(dx: 0, dy: 1), cornerRadius: 6).fill()
            (lit ? theme.key : fill).setFill()
            path.fill()
            if key.style == .action && isDown {
                UIColor(white: 0, alpha: 0.15).setFill(); path.fill()
            }

            let text = label(for: key)
            let big = key.isLetter || (key.main?.text.count ?? 0) == 1 && key.fn == nil
            let size: CGFloat = key.fn == nil ? (big ? 23 : 18) : (text.count > 2 ? 14 : 17)
            let font = UIFont.systemFont(ofSize: size, weight: key.fn == nil ? .regular : .medium)
            draw(text, in: r, font: font, color: lit ? theme.accent : textColor)

            if key.fn == "space", let name = fnLabels["space"] {
                draw(name, in: r, font: .systemFont(ofSize: 13), color: theme.hint)
            }
            // the up-flick character, small in the top right corner
            if let up = key.flicks[.up]?.label {
                let f = UIFont.systemFont(ofSize: 10)
                let s = NSAttributedString(string: up, attributes: [.font: f, .foregroundColor: theme.hint])
                let sz = s.size()
                s.draw(at: CGPoint(x: r.maxX - sz.width - 4, y: r.minY + 2))
            }
        }
    }

    private func draw(_ text: String, in r: CGRect, font: UIFont, color: UIColor) {
        guard !text.isEmpty else { return }
        let s = NSAttributedString(string: text, attributes: [.font: font, .foregroundColor: color])
        let sz = s.size()
        s.draw(at: CGPoint(x: r.midX - sz.width / 2, y: r.midY - sz.height / 2))
    }

    // MARK: - touches

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            let p = t.location(in: self)
            guard let i = keyIndex(at: p) else { continue }
            let tr = Track(index: i, start: p)
            tracks[ObjectIdentifier(t)] = tr
            let key = placed[i].key
            delegate?.keyboardViewFeedback(self)
            if key.fn == "backspace" {
                delegate?.keyboardView(self, function: "backspace", key: key)
                tr.longTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self, weak tr] _ in
                    guard let self, let tr else { return }
                    tr.repeatTimer = Timer.scheduledTimer(withTimeInterval: 0.07, repeats: true) { [weak self] _ in
                        guard let self else { return }
                        self.delegate?.keyboardView(self, function: "backspace", key: key)
                    }
                }
            } else if key.fn == nil || !key.long.isEmpty {
                if key.fn == nil { popupHost?.preview(label(for: key), over: convert(placed[i].frame, to: popupHost)) }
                let choices = key.fn == nil ? alternates(key) : key.long
                if !choices.isEmpty {
                    tr.longTimer = Timer.scheduledTimer(withTimeInterval: 0.35, repeats: false) { [weak self, weak tr] _ in
                        guard let self, let tr else { return }
                        tr.choices = choices
                        tr.choice = 0
                        self.popupHost?.showChoices(choices.map(\.label), over: self.convert(self.placed[tr.index].frame, to: self.popupHost), selected: 0)
                    }
                }
            }
        }
        setNeedsDisplay()
    }

    private func alternates(_ key: Key) -> [Output] {
        guard shift != .off, key.isLetter, let m = key.main?.text else { return key.alternates }
        // with shift on, the capital is what a tap types: the long press offers the small letter
        return [.of(m)] + key.alternates.filter { $0.text != m.uppercased() }
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard let tr = tracks[ObjectIdentifier(t)] else { continue }
            tr.point = t.location(in: self)
            let key = placed[tr.index].key
            let dx = tr.point.x - tr.start.x
            if let choices = tr.choices {
                // sliding along the long-press choices
                let step: CGFloat = 34
                let c = max(0, min(choices.count - 1, Int((dx + step / 2) / step)))
                if c != tr.choice { tr.choice = c; popupHost?.select(c) }
            } else if key.fn == "space" {
                // the space bar as a trackpad: one character per 9 points
                if abs(dx) > 16 { tr.dragging = true }
                if tr.dragging {
                    let want = Int(dx / 9)
                    if want != tr.moved { delegate?.keyboardView(self, moveCaret: want - tr.moved); tr.moved = want }
                }
            } else if hypot(dx, tr.point.y - tr.start.y) > KeyboardView.flickDistance {
                // a flick: no long press any more, and the preview shows what it will type
                tr.longTimer?.invalidate()
                if let f = flick(of: tr, key) { popupHost?.preview(f.label, over: convert(placed[tr.index].frame, to: popupHost)) }
            }
        }
    }

    private func flick(of tr: Track, _ key: Key) -> Output? {
        let dx = tr.point.x - tr.start.x, dy = tr.point.y - tr.start.y
        guard hypot(dx, dy) > KeyboardView.flickDistance else { return nil }
        let dir: Dir = abs(dx) > abs(dy) ? (dx > 0 ? .right : .left) : (dy > 0 ? .down : .up)
        return key.flicks[dir]
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard let tr = tracks.removeValue(forKey: ObjectIdentifier(t)) else { continue }
            tr.longTimer?.invalidate()
            tr.repeatTimer?.invalidate()
            tr.point = t.location(in: self)
            let key = placed[tr.index].key
            popupHost?.hide()
            if let choices = tr.choices {
                delegate?.keyboardView(self, typed: choices[tr.choice], from: key)
            } else if let fn = key.fn {
                if fn == "backspace" { continue }
                if fn == "space" && tr.dragging { continue }
                delegate?.keyboardView(self, function: fn, key: key)
            } else if let f = flick(of: tr, key) {
                delegate?.keyboardView(self, typed: f, from: key)
            } else if hypot(tr.point.x - tr.start.x, tr.point.y - tr.start.y) <= KeyboardView.flickDistance, let main = key.main {
                let out = shift != .off && key.isLetter ? Output.of(main.text.uppercased()) : main
                delegate?.keyboardView(self, typed: out, from: key)
            }
        }
        setNeedsDisplay()
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        for t in touches {
            guard let tr = tracks.removeValue(forKey: ObjectIdentifier(t)) else { continue }
            tr.longTimer?.invalidate()
            tr.repeatTimer?.invalidate()
        }
        popupHost?.hide()
        setNeedsDisplay()
    }
}
