import SwiftUI
import KKCore

@main
struct KKApp: App {
    var body: some Scene {
        WindowGroup { ContentView() }
    }
}

/// The app around the keyboard: how to turn it on, a field to try it, and its settings.
struct ContentView: View {
    @StateObject private var model = SettingsModel()
    @State private var trial = ""

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    Step(n: 1, text: "打开「设置」› 通用 › 键盘 › 键盘 › 添加新键盘…，选择「KK输入法」")
                    Step(n: 2, text: "在键盘列表里点「KK输入法」，打开「允许完全访问」（可选：振动、和本应用共享设置需要它；输入的内容不会离开手机）")
                    Step(n: 3, text: "在任意输入框长按 🌐，切换到 KK输入法")
                    Button("打开设置") {
                        if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) }
                    }
                } header: { Text("启用键盘") }

                Section {
                    TextField("在这里试试：nihao、Привет、hello", text: $trial, axis: .vertical)
                        .lineLimit(3...6)
                } header: { Text("试一试") }

                Section {
                    ForEach(Settings.allLanguages, id: \.self) { id in
                        Toggle(SettingsModel.names[id] ?? id, isOn: model.binding(language: id))
                    }
                } header: { Text("语言键依次切换") } footer: { Text("至少保留一种。") }

                Section {
                    Toggle("繁体输出", isOn: $model.traditional)
                } header: { Text("中文") }

                Section {
                    Toggle("句首自动大写", isOn: $model.autoCapitalize)
                    Toggle("双击空格输入句号", isOn: $model.doubleSpacePeriod)
                    Toggle("单词补全", isOn: $model.wordCompletion)
                } header: { Text("俄语与英语") }

                Section {
                    Toggle("按键振动", isOn: $model.haptics)
                    Toggle("按键音", isOn: $model.sound)
                } header: { Text("手感") } footer: {
                    Text("振动需要「允许完全访问」；按键音还需打开系统的「声音与触感 › 键盘反馈 › 声音」。")
                }

                Section {
                    Text("中文基于 Rime 与雾凇拼音，所有输入都在手机本地处理。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
            }
            .navigationTitle("KK输入法")
        }
    }
}

private struct Step: View {
    let n: Int
    let text: String
    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            Text("\(n)").font(.headline).foregroundStyle(.tint).frame(width: 18)
            Text(text).font(.subheadline)
        }
    }
}

/// The shared settings (App Group), as SwiftUI state.
final class SettingsModel: ObservableObject {
    static let names = ["zh": "中文", "en": "English", "ru": "Русский"]
    private let s = Settings()

    @Published var traditional: Bool { didSet { s.traditional = traditional } }
    @Published var autoCapitalize: Bool { didSet { s.autoCapitalize = autoCapitalize } }
    @Published var doubleSpacePeriod: Bool { didSet { s.doubleSpacePeriod = doubleSpacePeriod } }
    @Published var wordCompletion: Bool { didSet { s.wordCompletion = wordCompletion } }
    @Published var haptics: Bool { didSet { s.haptics = haptics } }
    @Published var sound: Bool { didSet { s.sound = sound } }
    @Published var languages: [String] { didSet { s.languages = languages } }

    init() {
        traditional = s.traditional
        autoCapitalize = s.autoCapitalize
        doubleSpacePeriod = s.doubleSpacePeriod
        wordCompletion = s.wordCompletion
        haptics = s.haptics
        sound = s.sound
        languages = s.languages
    }

    func binding(language id: String) -> Binding<Bool> {
        Binding(
            get: { self.languages.contains(id) },
            set: { on in
                var l = self.languages
                if on, !l.contains(id) { l.append(id) }
                if !on, l.count > 1 { l.removeAll { $0 == id } }
                // keep the order of Settings.allLanguages
                self.languages = Settings.allLanguages.filter(l.contains)
            })
    }
}
