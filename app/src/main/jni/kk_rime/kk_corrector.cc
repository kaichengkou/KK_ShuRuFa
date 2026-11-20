// KK输入法 typo correction for pinyin, replacing librime's built-in "corrector" component.
//
// Two mistakes matter most on a phone keyboard:
//  - hitting the key to the left or right of the intended one (handled by librime's
//    NearSearchCorrector, which substitutes horizontal neighbours);
//  - typing two letters in the wrong order (zhnog -> zhong), which librime does not handle.
// Corrections are only offered when they explain more of the input than the exact spelling
// does, so correct input like "nihao" gets no extra, unrelated candidates.

#include <rime/dict/corrector.h>
#include <rime/dict/prism.h>
#include <rime/registry.h>
#include <rime/ticket.h>

#include <algorithm>
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
    std::vector<Prism::Match> exact;
    p.CommonPrefixSearch(key, &exact);
    size_t exact_len = 0;
    for (auto& m : exact) exact_len = std::max(exact_len, m.length);

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
    for (auto& kv : found) {
      if (kv.second.length > exact_len) results->Alter(kv.first, kv.second);
    }
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
