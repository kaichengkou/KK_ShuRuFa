import Foundation
import RimeKit

/// The Chinese engine: librime with 雾凇拼音, the same C++ session as on Android (kk_core.cc).
///
/// The dictionaries were compiled when the app was built (scripts/build_rime_data.sh) and ship in
/// the keyboard's bundle (RimeShared/build); the keyboard only reads them, so it never runs into
/// the memory a keyboard is allowed while compiling. What it learns goes to its own container.
final class Rime {
    static let shared = Rime()

    enum State { case idle, starting, ready, failed }
    private(set) var state = State.idle
    private let queue = DispatchQueue(label: "kk.rime.start", qos: .userInitiated)
    private var waiting: [() -> Void] = []

    // X11 keysyms that librime takes
    static let backspace: Int32 = 0xff08
    static let enter: Int32 = 0xff0d
    static let space: Int32 = 0x20

    /// Starts Rime in the background; [then] runs on the main queue once it is ready.
    func start(then: (() -> Void)? = nil) {
        if let then { if state == .ready { then(); return } else { waiting.append(then) } }
        guard state == .idle || state == .failed else { return }
        guard let shared = Bundle.main.url(forResource: "RimeShared", withExtension: nil) else {
            state = .failed
            return
        }
        state = .starting
        let user = Rime.userDir()
        queue.async {
            Rime.prepare(user: user, shared: shared)
            kk_rime_startup_prebuilt(shared.path, user.path)
            let ok = kk_rime_select_schema("rime_ice")
            DispatchQueue.main.async {
                self.state = ok ? .ready : .failed
                let w = self.waiting
                self.waiting.removeAll()
                if ok { w.forEach { $0() } }
            }
        }
    }

    /// Where Rime keeps what it learns: the keyboard's own Library (writable without 完全访问).
    static func userDir() -> URL {
        let lib = FileManager.default.urls(for: .libraryDirectory, in: .userDomainMask)[0]
        let dir = lib.appendingPathComponent("Rime", isDirectory: true)
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    /// 自定义短语 is read from the user folder (Rime's stabledb), as on Android: copied there once.
    private static func prepare(user: URL, shared: URL) {
        let phrases = user.appendingPathComponent("custom_phrase.txt")
        if !FileManager.default.fileExists(atPath: phrases.path) {
            try? FileManager.default.copyItem(at: shared.appendingPathComponent("custom_phrase.txt"), to: phrases)
        }
    }

    var ready: Bool { state == .ready }

    // MARK: - the composition

    @discardableResult func key(_ code: Int32) -> Bool { kk_rime_process_key(code, 0) }
    @discardableResult func select(_ index: Int) -> Bool { kk_rime_select_candidate(Int32(index)) }
    func clear() { kk_rime_clear_composition() }
    func setOption(_ name: String, _ on: Bool) { kk_rime_set_option(name, on) }

    var generation: Int { Int(kk_rime_generation()) }
    var input: String { take(kk_rime_input()) }
    var preedit: String { take(kk_rime_preedit()) }
    /// What the last change committed (empty when it committed nothing).
    var commit: String { take(kk_rime_commit_text()) }
    var composing: Bool { !input.isEmpty }

    struct Candidate { let text: String; let comment: String }

    private var cache: (generation: Int, list: [Candidate], asked: Int)?

    /// The first [n] candidates, fetched once per change of the composition.
    func candidates(_ n: Int) -> [Candidate] {
        let gen = generation
        if let c = cache, c.generation == gen, c.asked >= n || c.list.count < c.asked { return Array(c.list.prefix(n)) }
        let raw = take(kk_rime_candidates(Int32(n)))
        let list: [Candidate] = raw.isEmpty ? [] : raw.split(separator: "\u{1e}", omittingEmptySubsequences: false).map {
            let parts = $0.split(separator: "\u{1f}", maxSplits: 1, omittingEmptySubsequences: false)
            return Candidate(text: String(parts[0]), comment: parts.count > 1 ? String(parts[1]) : "")
        }
        cache = (gen, list, n)
        return list
    }

    private func take(_ p: UnsafeMutablePointer<CChar>?) -> String {
        guard let p else { return "" }
        defer { kk_rime_free(p) }
        return String(cString: p)
    }
}
