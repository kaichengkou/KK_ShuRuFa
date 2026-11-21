// KK输入法 typo correction for pinyin, replacing librime's built-in "corrector" component.
//
// The mistakes a phone keyboard makes:
//  - hitting the key to the left or right of the intended one (handled by librime's
//    NearSearchCorrector, which substitutes horizontal neighbours);
//  - typing two letters in the wrong order (zhnog -> zhong);
//  - a letter that did not register (zhng -> zhong, jintan -> jintian);
//  - a letter typed twice or brushed on the way (nihhao -> nihao, zhonmg -> zhong).
// The last three librime does not handle. A correction is only offered when it covers input the
// exact spellings cannot: it has to end where no chain of real syllables, as typed, ends. So
// correct input gets nothing extra ("nihao" is ni+hao, so 鸟 from "niao" is not offered), and
// bare initials do not count as syllables here, or every typo would pass for an abbreviation.

#include <rime/dict/corrector.h>
#include <rime/dict/prism.h>
#include <rime/registry.h>
#include <rime/ticket.h>

#include <algorithm>
#include <cmath>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

namespace {

using rime::Prism;
using rime::corrector::Correction;
using rime::corrector::Corrections;

class KkCorrector : public rime::Corrector {
 public:
  void ToleranceSearch(const Prism& prism, const std::string& key, Corrections* results, size_t tolerance) override {
    auto& p = const_cast<Prism&>(prism);
    // where chains of real syllables, spelled as typed, can end
    const size_t n = std::min(key.size(), static_cast<size_t>(32));
    std::vector<bool> reach(n + 1, false);
    reach[0] = true;
    for (size_t pos = 0; pos < n; ++pos) {
      if (!reach[pos]) continue;
      std::vector<Prism::Match> exact;
      p.CommonPrefixSearch(key.substr(pos, n - pos), &exact);
      for (auto& m : exact) {
        if (HasVowel(key, pos, m.length)) reach[pos + m.length] = true;
      }
    }

    Corrections found;
    // neighbouring keys on the same row
    near_.ToleranceSearch(prism, key, &found, tolerance);
    // two adjacent letters swapped, within the next syllable
    for (size_t i = 0; i + 1 < key.size() && i < 6; ++i) {
      if (key[i] == key[i + 1]) continue;
      std::string swapped = key;
      std::swap(swapped[i], swapped[i + 1]);
      std::vector<Prism::Match> matches;
      p.CommonPrefixSearch(swapped, &matches);
      for (auto& m : matches) {
        // the swap has to be inside the matched spelling to count as a correction
        if (m.length > i + 1) found.Alter(m.value, Correction{1, static_cast<rime::SyllableId>(m.value), m.length});
      }
    }
    // a letter too many: drop it, and the spelling that matches covers one more typed letter.
    // Only a letter that repeats its neighbour or sits beside it on the keyboard: a key tapped
    // twice or brushed on the way, not any letter that happens to make a word when removed.
    // A letter tapped twice is offered even where the letters also split into real syllables
    // (zhengzaai is za+ai as typed, but 正在 is what was meant): it is the commonest slip of all.
    Corrections doubled;
    for (size_t i = 1; i < key.size() && i < 9; ++i) {
      if (!Stray(key, i)) continue;
      std::string shorter = key.substr(0, i) + key.substr(i + 1);
      std::vector<Prism::Match> matches;
      p.CommonPrefixSearch(shorter, &matches);
      for (auto& m : matches) {
        if (m.length < i) continue;
        Correction c{1, static_cast<rime::SyllableId>(m.value), m.length + 1};
        if (key[i] == key[i - 1]) doubled.Alter(m.value, c); else found.Alter(m.value, c);
      }
    }
    // a letter missing: put each letter back in turn; the match covers one typed letter less
    for (size_t i = 1; i <= key.size() && i < 6; ++i) {
      for (char c = 'a'; c <= 'z'; ++c) {
        if (c == 'v') continue;
        std::string longer = key.substr(0, i) + c + key.substr(i);
        std::vector<Prism::Match> matches;
        p.CommonPrefixSearch(longer, &matches);
        for (auto& m : matches) {
          if (m.length > i + 1) found.Alter(m.value, Correction{1, static_cast<rime::SyllableId>(m.value), m.length - 1});
        }
      }
    }
    for (auto& kv : found) {
      size_t len = kv.second.length;
      if (len <= n && !reach[len]) results->Alter(kv.first, kv.second);
    }
    for (auto& kv : doubled) results->Alter(kv.first, kv.second);
  }

  // can key[i] be a stray tap: the same as, or next to, the letter before or after it?
  static bool Stray(const std::string& key, size_t i) {
    char c = key[i];
    for (size_t j : {i - 1, i + 1}) {
      if (j >= key.size()) continue;
      if (key[j] == c || Adjacent(key[j], c)) return true;
    }
    return false;
  }

  // neighbours on a QWERTY keyboard, rows offset as on the phone
  static bool Adjacent(char a, char b) {
    static const char* rows[] = {"qwertyuiop", "asdfghjkl", "zxcvbnm"};
    static const float shift[] = {0.f, 0.5f, 1.5f};
    int ra = -1, rb = -1; float xa = 0, xb = 0;
    for (int r = 0; r < 3; ++r) {
      const char* p = strchr(rows[r], a); if (p && a) { ra = r; xa = (p - rows[r]) + shift[r]; }
      const char* q = strchr(rows[r], b); if (q && b) { rb = r; xb = (q - rows[r]) + shift[r]; }
    }
    if (ra < 0 || rb < 0 || a == b) return false;
    return std::abs(ra - rb) <= 1 && std::fabs(xa - xb) <= 1.0f;
  }

  // a syllable rather than a bare initial (zh, h, j…), which only abbreviations are
  static bool HasVowel(const std::string& key, size_t pos, size_t len) {
    for (size_t i = pos; i < pos + len && i < key.size(); ++i) {
      char c = key[i];
      if (c == 'a' || c == 'e' || c == 'i' || c == 'o' || c == 'u' || c == 'v') return true;
    }
    return false;
  }

 private:
  rime::NearSearchCorrector near_;
};

class KkCorrectorComponent : public rime::Corrector::Component {
 public:
  rime::Corrector* Create(const rime::Ticket&) noexcept override { return new KkCorrector; }
};

}  // namespace

void kk_install_corrector() {
  rime::Registry::instance().Register("corrector", new KkCorrectorComponent);
}
