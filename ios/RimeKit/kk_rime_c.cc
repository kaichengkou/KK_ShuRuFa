// C bridge for the iOS keyboard over kk_core (the same session code the Android build uses).

#include "kk_rime.h"

#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "kk_core.h"

namespace {

std::string s(const char* c) { return c ? std::string(c) : std::string(); }

char* dup(const std::string& v) {
  char* out = static_cast<char*>(std::malloc(v.size() + 1));
  if (!out) return nullptr;
  std::memcpy(out, v.data(), v.size());
  out[v.size()] = '\0';
  return out;
}

char* joined(const std::vector<std::string>& texts, const std::vector<std::string>* comments) {
  std::string r;
  for (size_t i = 0; i < texts.size(); i++) {
    if (i) r += '\x1e';
    r += texts[i];
    if (comments && i < comments->size() && !(*comments)[i].empty()) {
      r += '\x1f';
      r += (*comments)[i];
    }
  }
  return dup(r);
}

}  // namespace

extern "C" {

void kk_rime_startup(const char* shared_dir, const char* user_dir, bool full_check) {
  kk::startup(s(shared_dir), s(user_dir), full_check);
}
void kk_rime_startup_prebuilt(const char* shared_dir, const char* user_dir) {
  kk::startup(s(shared_dir), s(user_dir), false, false);
}
void kk_rime_join_maintenance(void) { kk::joinMaintenance(); }
void kk_rime_shutdown(void) { kk::shutdown(); }

bool kk_rime_process_key(int keycode, int mask) { return kk::processKey(keycode, mask); }
bool kk_rime_select_candidate(int index) { return kk::selectCandidate(index); }
bool kk_rime_commit_composition(void) { return kk::commitComposition(); }
void kk_rime_clear_composition(void) { kk::clearComposition(); }
void kk_rime_set_option(const char* name, bool value) { kk::setOption(s(name), value); }
bool kk_rime_get_option(const char* name) { return kk::getOption(s(name)); }
bool kk_rime_select_schema(const char* schema_id) { return kk::selectSchema(s(schema_id)); }
bool kk_rime_set_input(const char* input) { return kk::setInput(s(input)); }
int kk_rime_caret_pos(void) { return kk::caretPos(); }
void kk_rime_set_caret_pos(int pos) { kk::setCaretPos(pos); }
bool kk_rime_highlight_candidate(int index) { return kk::highlightCandidate(index); }
bool kk_rime_delete_candidate(int index) { return kk::deleteCandidate(index); }

char* kk_rime_candidates(int limit) {
  std::vector<std::string> t, c;
  kk::candidates(limit, &t, &c);
  return joined(t, &c);
}
char* kk_rime_query(const char* input, int limit) { return joined(kk::query(s(input), limit), nullptr); }
bool kk_rime_learn(const char* input, const char* text) { return kk::learn(s(input), s(text)); }
bool kk_rime_sync_user_data(void) { return kk::syncUserData(); }

int kk_rime_generation(void) { return kk::generation(); }
char* kk_rime_commit_text(void) { return dup(kk::commitText()); }
char* kk_rime_preedit(void) { return dup(kk::preedit()); }
char* kk_rime_input(void) { return dup(kk::input()); }
int kk_rime_cursor(void) { return kk::cursor(); }

char* kk_rime_t2s(const char* text, const char* config) { return dup(kk::t2s(s(text), s(config))); }

void kk_rime_free(char* p) { std::free(p); }

}  // extern "C"
