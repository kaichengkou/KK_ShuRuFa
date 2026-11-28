import UIKit

/// Lies over the whole keyboard and shows, above a key, what it will type (the preview) or its
/// long-press choices. It never takes touches: the key area keeps tracking the finger.
final class PopupView: UIView {
    var theme = Theme.light

    private let bubble = UIView()
    private let previewLabel = UILabel()
    private let choicesStack = UIStackView()
    private var choiceLabels: [UILabel] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        backgroundColor = .clear
        bubble.layer.cornerRadius = 8
        bubble.layer.shadowOpacity = 0.25
        bubble.layer.shadowRadius = 3
        bubble.layer.shadowOffset = CGSize(width: 0, height: 1)
        bubble.isHidden = true
        addSubview(bubble)
        previewLabel.textAlignment = .center
        previewLabel.font = .systemFont(ofSize: 30)
        bubble.addSubview(previewLabel)
        choicesStack.axis = .horizontal
        choicesStack.distribution = .fillEqually
        bubble.addSubview(choicesStack)
    }
    required init?(coder: NSCoder) { fatalError() }

    private func style() {
        bubble.backgroundColor = theme.popup
        bubble.layer.shadowColor = theme.shadow.cgColor
        previewLabel.textColor = theme.text
    }

    /// The character a key will type, in a bubble over it.
    func preview(_ text: String, over key: CGRect) {
        guard !text.isEmpty else { return }
        style()
        choicesStack.isHidden = true
        previewLabel.isHidden = false
        previewLabel.text = text
        let w = max(key.width + 10, 46), h: CGFloat = 54
        var f = CGRect(x: key.midX - w / 2, y: key.minY - h + 4, width: w, height: h)
        f.origin.x = min(max(2, f.minX), bounds.width - w - 2)
        f.origin.y = max(0, f.minY)
        bubble.frame = f
        previewLabel.frame = bubble.bounds
        bubble.isHidden = false
    }

    /// A row of choices above a held key; the first is selected.
    func showChoices(_ items: [String], over key: CGRect, selected: Int) {
        style()
        previewLabel.isHidden = true
        choicesStack.isHidden = false
        choiceLabels.forEach { $0.removeFromSuperview() }
        choiceLabels = items.map { t in
            let l = UILabel()
            l.text = t
            l.textAlignment = .center
            l.font = .systemFont(ofSize: 22)
            l.layer.cornerRadius = 6
            l.layer.masksToBounds = true
            choicesStack.addArrangedSubview(l)
            return l
        }
        let cell: CGFloat = 34, h: CGFloat = 48
        let w = cell * CGFloat(items.count) + 8
        var f = CGRect(x: key.minX, y: key.minY - h + 2, width: w, height: h)
        f.origin.x = min(max(2, f.minX), bounds.width - w - 2)
        f.origin.y = max(0, f.minY)
        bubble.frame = f
        choicesStack.frame = bubble.bounds.insetBy(dx: 4, dy: 4)
        bubble.isHidden = false
        select(selected)
    }

    func select(_ index: Int) {
        for (i, l) in choiceLabels.enumerated() {
            l.backgroundColor = i == index ? theme.accent : .clear
            l.textColor = i == index ? .white : theme.text
        }
    }

    func hide() { bubble.isHidden = true }
}
