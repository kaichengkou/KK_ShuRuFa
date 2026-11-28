import UIKit

/// Colours of the keyboard, light and dark, after the Android app's default theme (misty blue accent).
struct Theme {
    let background: UIColor
    let key: UIColor
    let keyPressed: UIColor
    let fnKey: UIColor
    let action: UIColor
    let actionText: UIColor
    let text: UIColor
    let hint: UIColor
    let bar: UIColor
    let accent: UIColor
    let shadow: UIColor
    let popup: UIColor

    static func current(_ traits: UITraitCollection) -> Theme {
        traits.userInterfaceStyle == .dark ? .dark : .light
    }

    static let light = Theme(
        background: UIColor(red: 0.92, green: 0.93, blue: 0.95, alpha: 1),
        key: .white,
        keyPressed: UIColor(red: 0.80, green: 0.83, blue: 0.88, alpha: 1),
        fnKey: UIColor(red: 0.80, green: 0.83, blue: 0.87, alpha: 1),
        action: UIColor(red: 0.36, green: 0.52, blue: 0.70, alpha: 1),
        actionText: .white,
        text: UIColor(white: 0.10, alpha: 1),
        hint: UIColor(white: 0.45, alpha: 1),
        bar: UIColor(red: 0.92, green: 0.93, blue: 0.95, alpha: 1),
        accent: UIColor(red: 0.30, green: 0.47, blue: 0.66, alpha: 1),
        shadow: UIColor(white: 0, alpha: 0.28),
        popup: .white)

    static let dark = Theme(
        background: UIColor(red: 0.11, green: 0.12, blue: 0.14, alpha: 1),
        key: UIColor(red: 0.27, green: 0.28, blue: 0.31, alpha: 1),
        keyPressed: UIColor(red: 0.40, green: 0.42, blue: 0.46, alpha: 1),
        fnKey: UIColor(red: 0.19, green: 0.20, blue: 0.23, alpha: 1),
        action: UIColor(red: 0.42, green: 0.58, blue: 0.76, alpha: 1),
        actionText: .white,
        text: UIColor(white: 0.95, alpha: 1),
        hint: UIColor(white: 0.62, alpha: 1),
        bar: UIColor(red: 0.11, green: 0.12, blue: 0.14, alpha: 1),
        accent: UIColor(red: 0.55, green: 0.70, blue: 0.88, alpha: 1),
        shadow: UIColor(white: 0, alpha: 0.6),
        popup: UIColor(red: 0.33, green: 0.34, blue: 0.38, alpha: 1))
}
