import UIKit
import KKCore

/// 符号面板: the categories of symbols.yaml (shared with Android) as tabs, their symbols in a grid,
/// and a bottom row to go back, type a space, delete and enter.
final class SymbolPanel: UIView, UICollectionViewDataSource, UICollectionViewDelegateFlowLayout {
    var onSymbol: ((String) -> Void)?
    var onBack: (() -> Void)?
    var onDelete: (() -> Void)?
    var onSpace: (() -> Void)?
    var onEnter: (() -> Void)?

    var theme = Theme.light { didSet { restyle() } }
    private var categories: [SymbolCategory] = []
    private var current = 0

    private let tabs = UIScrollView()
    private let tabStack = UIStackView()
    private let grid: UICollectionView
    private let bottom = UIView()

    override init(frame: CGRect) {
        let flow = UICollectionViewFlowLayout()
        flow.minimumInteritemSpacing = 0
        flow.minimumLineSpacing = 0
        grid = UICollectionView(frame: .zero, collectionViewLayout: flow)
        super.init(frame: frame)
        tabs.showsHorizontalScrollIndicator = false
        tabStack.axis = .horizontal
        tabStack.spacing = 4
        tabs.addSubview(tabStack)
        addSubview(tabs)
        grid.register(Cell.self, forCellWithReuseIdentifier: "c")
        grid.dataSource = self
        grid.delegate = self
        grid.backgroundColor = .clear
        addSubview(grid)
        addSubview(bottom)
        for (title, sel, weight) in [("返回", #selector(back), 1.5), ("空格", #selector(space), 4.0),
                                     ("⌫", #selector(del), 1.5), ("换行", #selector(enter), 1.5)] {
            let b = UIButton(type: .custom)
            b.setTitle(title, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
            b.layer.cornerRadius = 6
            b.addTarget(self, action: sel, for: .touchUpInside)
            b.tag = Int(weight * 10)
            bottom.addSubview(b)
        }
        restyle()
    }
    required init?(coder: NSCoder) { fatalError() }

    func load(_ cats: [SymbolCategory], first: String) {
        categories = cats
        current = cats.firstIndex(where: { $0.id == first }) ?? 0
        tabStack.arrangedSubviews.forEach { $0.removeFromSuperview() }
        for (i, c) in cats.enumerated() {
            let b = UIButton(type: .custom)
            b.setTitle(c.name, for: .normal)
            b.titleLabel?.font = .systemFont(ofSize: 14)
            b.contentEdgeInsets = UIEdgeInsets(top: 0, left: 10, bottom: 0, right: 10)
            b.layer.cornerRadius = 12
            b.tag = i
            b.addTarget(self, action: #selector(tab(_:)), for: .touchUpInside)
            tabStack.addArrangedSubview(b)
        }
        restyle()
        grid.reloadData()
        setNeedsLayout()
    }

    func setEnterLabel(_ s: String) { (bottom.subviews.last as? UIButton)?.setTitle(s, for: .normal) }

    private func restyle() {
        backgroundColor = theme.background
        for case let b as UIButton in tabStack.arrangedSubviews {
            let on = b.tag == current
            b.backgroundColor = on ? theme.accent : .clear
            b.setTitleColor(on ? .white : theme.text, for: .normal)
        }
        for case let b as UIButton in bottom.subviews {
            b.backgroundColor = theme.fnKey
            b.setTitleColor(theme.text, for: .normal)
        }
        grid.reloadData()
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        let tabH: CGFloat = 34, bottomH: CGFloat = 46
        tabs.frame = CGRect(x: 0, y: 0, width: bounds.width, height: tabH)
        let s = tabStack.systemLayoutSizeFitting(CGSize(width: UIView.layoutFittingCompressedSize.width, height: tabH - 6))
        tabStack.frame = CGRect(x: 6, y: 3, width: s.width, height: tabH - 6)
        tabs.contentSize = CGSize(width: s.width + 12, height: tabH)
        grid.frame = CGRect(x: 0, y: tabH, width: bounds.width, height: bounds.height - tabH - bottomH)
        let unit = (bounds.width - 6 * 5) / 8.5
        bottom.frame = CGRect(x: 0, y: bounds.height - bottomH, width: bounds.width, height: bottomH)
        var x: CGFloat = 6
        for v in bottom.subviews {
            let w = unit * CGFloat(v.tag) / 10
            v.frame = CGRect(x: x, y: 4, width: w, height: bottomH - 8)
            x += w + 6
        }
        (grid.collectionViewLayout as? UICollectionViewFlowLayout)?.invalidateLayout()
    }

    @objc private func tab(_ b: UIButton) { current = b.tag; restyle(); grid.setContentOffset(.zero, animated: false) }
    @objc private func back() { onBack?() }
    @objc private func space() { onSpace?() }
    @objc private func del() { onDelete?() }
    @objc private func enter() { onEnter?() }

    func collectionView(_ cv: UICollectionView, numberOfItemsInSection section: Int) -> Int {
        categories.isEmpty ? 0 : categories[current].items.count
    }

    func collectionView(_ cv: UICollectionView, cellForItemAt ip: IndexPath) -> UICollectionViewCell {
        let c = cv.dequeueReusableCell(withReuseIdentifier: "c", for: ip) as! Cell
        c.label.text = categories[current].items[ip.item].label
        c.label.textColor = theme.text
        return c
    }

    func collectionView(_ cv: UICollectionView, layout: UICollectionViewLayout, sizeForItemAt ip: IndexPath) -> CGSize {
        CGSize(width: floor(cv.bounds.width / 8), height: 44)
    }

    func collectionView(_ cv: UICollectionView, didSelectItemAt ip: IndexPath) {
        onSymbol?(categories[current].items[ip.item].text)
    }

    private final class Cell: UICollectionViewCell {
        let label = UILabel()
        override init(frame: CGRect) {
            super.init(frame: frame)
            label.textAlignment = .center
            label.font = .systemFont(ofSize: 20)
            label.adjustsFontSizeToFitWidth = true
            label.minimumScaleFactor = 0.5
            contentView.addSubview(label)
        }
        required init?(coder: NSCoder) { fatalError() }
        override func layoutSubviews() { super.layoutSubviews(); label.frame = contentView.bounds.insetBy(dx: 2, dy: 2) }
        override var isHighlighted: Bool { didSet { contentView.backgroundColor = isHighlighted ? UIColor.gray.withAlphaComponent(0.25) : .clear } }
    }
}
