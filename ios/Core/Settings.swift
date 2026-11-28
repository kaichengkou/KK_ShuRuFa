import Foundation

/// Settings shared by the app (where they are changed) and the keyboard (which reads them), through
/// the App Group. A keyboard without 完全访问 may not reach the group: it then types with the
/// defaults, which are the Android app's defaults.
public final class Settings {
    public static let appGroup = "group.com.kk.shurufa"

    public let defaults: UserDefaults

    public init(defaults: UserDefaults? = nil) {
        self.defaults = defaults ?? UserDefaults(suiteName: Settings.appGroup) ?? .standard
    }

    private func bool(_ key: String, _ fallback: Bool) -> Bool {
        defaults.object(forKey: key) == nil ? fallback : defaults.bool(forKey: key)
    }

    /// The keyboards the language key goes through, in order.
    public var languages: [String] {
        get { (defaults.stringArray(forKey: "languages") ?? ["zh", "en", "ru"]).filter { Settings.allLanguages.contains($0) } }
        set { defaults.set(newValue, forKey: "languages") }
    }
    public static let allLanguages = ["zh", "en", "ru"]

    /// 繁体输出 (Rime's traditionalization switch).
    public var traditional: Bool {
        get { bool("traditional", false) }
        set { defaults.set(newValue, forKey: "traditional") }
    }
    /// 句首自动大写 on the Russian and English keyboards.
    public var autoCapitalize: Bool {
        get { bool("autoCapitalize", true) }
        set { defaults.set(newValue, forKey: "autoCapitalize") }
    }
    /// 双击空格输入句号.
    public var doubleSpacePeriod: Bool {
        get { bool("doubleSpacePeriod", true) }
        set { defaults.set(newValue, forKey: "doubleSpacePeriod") }
    }
    /// Word completion on the Russian and English keyboards.
    public var wordCompletion: Bool {
        get { bool("wordCompletion", true) }
        set { defaults.set(newValue, forKey: "wordCompletion") }
    }
    /// Vibration on each key (iOS lets a keyboard vibrate only with 完全访问).
    public var haptics: Bool {
        get { bool("haptics", true) }
        set { defaults.set(newValue, forKey: "haptics") }
    }
    /// Key clicks (also needs the system's 键盘反馈 › 声音 on).
    public var sound: Bool {
        get { bool("sound", false) }
        set { defaults.set(newValue, forKey: "sound") }
    }
    /// The keyboard that was in use last, to come back on it.
    public var lastLanguage: String? {
        get { defaults.string(forKey: "lastLanguage") }
        set { defaults.set(newValue, forKey: "lastLanguage") }
    }

    /// Which language follows [current] on the language key.
    public func next(after current: String) -> String {
        let l = languages.isEmpty ? ["zh"] : languages
        guard let i = l.firstIndex(of: current) else { return l[0] }
        return l[(i + 1) % l.count]
    }
}
