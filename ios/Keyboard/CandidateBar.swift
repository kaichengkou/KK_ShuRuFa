import UIKit

/// The strip above the keys: the pinyin being typed (small, on the left) over a row of candidates
/// that scrolls sideways. With nothing to offer it shows the keyboard's name and a button to put
/// the keyboard away.
final class CandidateBar: UIView {
    var onPick: ((Int) -> Void)?
    var onDismiss: (() -> Void)?

    var theme = Theme.light { didSet { restyle() } }

    private let preeditLabel = UILabel()
    private let scroll = UIScrollView()
    private let stack = UIStackView()
    private let idleLabel = UILabel()
    private let dismissButton = UIButton(type: .system)
    private var items: [String] = []

    override init(frame: CGRect) {
        super.init(frame: frame)
        preeditLabel.font = .systemFont(ofSize: 12)
        addSubview(preeditLabel)
        scroll.showsHorizontalScrollIndicator = false
        scroll.alwaysBounceHorizontal = true
        addSubview(scroll)
        stack.axis = .horizontal
        stack.spacing = 2
        stack.alignment = .fill
        scroll.addSubview(stack)
        idleLabel.font = .systemFont(ofSize: 14, weight: .medium)
        idleLabel.text = "KK输入法"
        addSubview(idleLabel)
        dismissButton.setImage(UIImage(systemName: "keyboard.chevron.compact.down"), for: .normal)
        dismissButton.addTarget(self, action: #selector(dismiss), for: .touchUpInside)
        addSubview(dismissButton)
        restyle()
    }
    required init?(coder: NSCoder) { fatalError() }

    @objc private func dismiss() { onDismiss?() }

    private func restyle() {
        backgroundColor = theme.bar
        preeditLabel.textColor = theme.hint
        idleLabel.textColor = theme.hint
        dismissButton.tintColor = theme.hint
        for case let b as UIButton in stack.arrangedSubviews { b.setTitleColor(theme.text, for: .normal) }
        if let first = stack.arrangedSubviews.first as? UIButton { first.setTitleColor(theme.accent, for: .normal) }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let hasPreedit = !(preeditLabel.text ?? "").isEmpty
        let top: CGFloat = hasPreedit ? 14 : 0
        preeditLabel.frame = CGRect(x: 10, y: 1, width: bounds.width - 20, height: 13)
        scroll.frame = CGRect(x: 0, y: top, width: bounds.width, height: bounds.height - top)
        let size = stack.systemLayoutSizeFitting(CGSize(width: UIView.layoutFittingCompressedSize.width, height: scroll.bounds.height))
        stack.frame = CGRect(x: 4, y: 0, width: size.width, height: scroll.bounds.height)
        scroll.contentSize = CGSize(width: size.width + 8, height: scroll.bounds.height)
        idleLabel.frame = CGRect(x: 12, y: 0, width: bounds.width - 60, height: bounds.height)
        dismissButton.frame = CGRect(x: bounds.width - 48, y: 0, width: 44, height: bounds.height)
    }

    /// Shows [candidates] (the first one highlighted) under [preedit]; both empty: the idle bar.
    func show(preedit: String, candidates: [String]) {
        preeditLabel.text = preedit
        let idle = preedit.isEmpty && candidates.isEmpty
        idleLabel.isHidden = !idle
        dismissButton.isHidden = !idle
        scroll.isHidden = idle
        if candidates != items {
            items = candidates
            stack.arrangedSubviews.forEach { $0.removeFromSuperview() }
            for (i, c) in candidates.enumerated() {
                let b = UIButton(type: .custom)
                b.setTitle(c, for: .normal)
                b.titleLabel?.font = .systemFont(ofSize: 19)
                b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
                b.tag = i
                b.addTarget(self, action: #selector(tapped(_:)), for: .touchUpInside)
                stack.addArrangedSubview(b)
            }
            scroll.contentOffset = .zero
            restyle()
        }
        setNeedsLayout()
    }

    @objc private func tapped(_ b: UIButton) { onPick?(b.tag) }
}
