// The Rime session of KK输入法 (see kk_core.h). Platform bridges only convert strings around it.

#include "kk_core.h"

#include <rime_api.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <opencc/SimpleConverter.hpp>

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void kk_install_corrector();

namespace {

RimeApi* api() {
  static RimeApi* a = rime_get_api();
  return a;
}

std::mutex g_mutex;
RimeSessionId g_session = 0;
// A session for candidates of some other input (query) and one for teaching (learn), kept and
// reused: making a session builds a whole engine, its Lua filters' init included.
RimeSessionId g_scratch = 0;
RimeSessionId g_learn = 0;

struct Cached {
  std::string preedit;
  std::string input;
  int cursor = 0;
  std::string commit;
  // counts every change of the composition (see generation())
  int generation = 0;
} g;

RimeSessionId session() {
  if (g_session == 0 || !api()->find_session(g_session)) {
    g_session = api()->create_session();
  }
  return g_session;
}

// A session of our own, made once and made again only after Rime closed it.
RimeSessionId kept(RimeSessionId& id) {
  if (id == 0 || !api()->find_session(id)) id = api()->create_session();
  return id;
}

// The sessions are gone (Rime finalized, or its sync closed them): ids may be reused by new ones.
void forgetSessions() { g_session = 0; g_scratch = 0; g_learn = 0; }

// The first `limit` candidates of session [s]: their texts, and their comments when asked.
void listCandidates(RimeSessionId s, int limit, std::vector<std::string>* texts, std::vector<std::string>* comments) {
  RimeCandidateListIterator it = {0};
  if (!api()->candidate_list_begin(s, &it)) return;
  int n = 0;
  while (n < limit && api()->candidate_list_next(&it)) {
    if (texts) texts->emplace_back(it.candidate.text ? it.candidate.text : "");
    if (comments) comments->emplace_back(it.candidate.comment ? it.candidate.comment : "");
    n++;
  }
  api()->candidate_list_end(&it);
}

// After every change: only what the keyboard reads on each key (the candidates are fetched when
// they are asked for, as many as are asked for).
void refreshLocked() {
  RimeSessionId s = session();
  g.generation++;
  g.commit.clear();
  RIME_STRUCT(RimeCommit, commit);
  if (api()->get_commit(s, &commit)) {
    if (commit.text) g.commit = commit.text;
    api()->free_commit(&commit);
  }
  g.preedit.clear();
  g.input.clear();
  g.cursor = 0;
  RIME_STRUCT(RimeContext, ctx);
  if (api()->get_context(s, &ctx)) {
    if (ctx.composition.preedit) g.preedit = ctx.composition.preedit;
    g.cursor = ctx.composition.cursor_pos;
    api()->free_context(&ctx);
  }
  const char* in = api()->get_input(s);
  if (in) g.input = in;
}

}  // namespace

namespace kk {

void startup(const std::string& shared, const std::string& user, bool fullCheck, bool maintenance) {
  std::lock_guard<std::mutex> lock(g_mutex);
  static bool modulesDeclared = false;
  if (!modulesDeclared) {
    // librime is linked statically; reference the plugins so the linker keeps them.
    rime_require_module_lua();
    rime_require_module_octagram();
    modulesDeclared = true;
  }
  static std::string sharedDir, userDir;
  sharedDir = shared;
  userDir = user;
  RIME_STRUCT(RimeTraits, traits);
  traits.shared_data_dir = sharedDir.c_str();
  traits.user_data_dir = userDir.c_str();
  traits.log_dir = "";
  traits.app_name = "rime.kk";
  traits.distribution_name = "KK输入法";
  traits.distribution_code_name = "kk";
  traits.distribution_version = "0.1.0";
  static bool setup = false;
  if (!setup) {
    api()->setup(&traits);
    setup = true;
  }
  api()->initialize(&traits);
  kk_install_corrector();
  if (maintenance) api()->start_maintenance(fullCheck);
}

void joinMaintenance() { api()->join_maintenance_thread(); }

void shutdown() {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session) api()->destroy_session(g_session);
  forgetSessions();
  g.generation++;
  api()->finalize();
}

bool processKey(int keycode, int mask) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->process_key(session(), keycode, mask);
  refreshLocked();
  return r;
}

bool commitComposition() {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->commit_composition(session());
  refreshLocked();
  return r;
}

void clearComposition() {
  std::lock_guard<std::mutex> lock(g_mutex);
  api()->clear_composition(session());
  refreshLocked();
}

void setOption(const std::string& name, bool value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  api()->set_option(session(), name.c_str(), value);
  refreshLocked();
}

bool getOption(const std::string& name) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return api()->get_option(session(), name.c_str());
}

// Replaces the raw input of the composition (used to bring back the pinyin of a committed word).
bool setInput(const std::string& input) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->set_input(session(), input.c_str());
  refreshLocked();
  return r;
}

bool selectSchema(const std::string& id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  // the engine is built again (its Lua filters read their lists afresh): the other sessions too
  for (RimeSessionId* other : {&g_scratch, &g_learn}) {
    if (*other && api()->find_session(*other)) api()->destroy_session(*other);
    *other = 0;
  }
  bool r = api()->select_schema(session(), id.c_str());
  refreshLocked();
  return r;
}

// Up to `limit` candidates with their comments, in one pass over the menu. Every candidate
// fetched runs through the translators and the Lua filters, so the menu is walked once, not once
// for the texts and again for the comments.
void candidates(int limit, std::vector<std::string>* texts, std::vector<std::string>* comments) {
  std::lock_guard<std::mutex> lock(g_mutex);
  listCandidates(session(), limit, texts, comments);
}

// Changes with every change of the composition: candidates fetched at one generation are still
// the menu's while it stays the same.
int generation() { return g.generation; }

// Caret inside the raw input (in letters), for editing the pinyin in place.
int caretPos() {
  std::lock_guard<std::mutex> lock(g_mutex);
  return static_cast<int>(api()->get_caret_pos(session()));
}
void setCaretPos(int pos) {
  std::lock_guard<std::mutex> lock(g_mutex);
  api()->set_caret_pos(session(), pos);
  refreshLocked();
}

// Candidates for some input in a separate session, without touching what the user is typing.
std::vector<std::string> query(const std::string& input, int limit) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeSessionId s = kept(g_scratch);
  std::vector<std::string> v;
  api()->clear_composition(s);
  if (api()->set_input(s, input.c_str())) listCandidates(s, limit, &v, nullptr);
  api()->clear_composition(s);
  return v;
}

// Learning after the fact: the keyboard's session learns nothing (_kk_no_learning), and a commit
// that stayed (not deleted right away) is taught here: [input] typed again in a session of its
// own, with learning on, and candidates chosen until exactly [text] is committed. Nothing is
// learned when the text cannot be made again from the input. The session is kept from one
// teaching to the next (making one builds a whole engine), and the lock is held only for this.
bool learn(const std::string& input, const std::string& text) {
  if (input.empty() || text.empty()) return false;
  std::lock_guard<std::mutex> lock(g_mutex);
  const bool trad = api()->get_option(session(), "traditionalization");
  RimeSessionId s = kept(g_learn);
  if (!s) return false;
  api()->clear_composition(s);
  api()->set_option(s, "_kk_no_learning", False);
  api()->set_option(s, "traditionalization", trad ? True : False);
  api()->set_option(s, "emoji", False);
  bool ok = false;
  if (api()->set_input(s, input.c_str())) {
    std::string done;  // what has been chosen so far
    for (int step = 0; step < 8 && !ok; ++step) {
      const std::string rest = text.substr(std::min(done.size(), text.size()));
      int pick = -1; size_t best = 0;
      RimeCandidateListIterator it = {0};
      if (api()->candidate_list_begin(s, &it)) {
        for (int i = 0; i < 300 && api()->candidate_list_next(&it); ++i) {
          const std::string c = it.candidate.text ? it.candidate.text : "";
          if (c.empty() || rest.compare(0, c.size(), c) != 0) continue;
          if (c.size() > best) { best = c.size(); pick = it.index; }
          if (c.size() == rest.size()) break;   // the whole of what is left
        }
        api()->candidate_list_end(&it);
      }
      if (pick < 0) break;
      api()->select_candidate(s, pick);
      done += rest.substr(0, best);
      RIME_STRUCT(RimeCommit, commit);
      if (api()->get_commit(s, &commit)) {
        ok = commit.text && text == commit.text;
        api()->free_commit(&commit);
        break;
      }
    }
  }
  api()->clear_composition(s);
  return ok;
}

bool selectCandidate(int index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->select_candidate(session(), index);
  refreshLocked();
  return r;
}

// Rime's sync: writes a text snapshot of each user dictionary to <user>/sync/<installation>/ and
// merges every snapshot found in the other folders there. Closes the sessions; ours is made again
// on its next use. Blocking.
bool syncUserData() {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->sync_user_data();
  api()->join_maintenance_thread();
  forgetSessions();
  g.generation++;
  return r;
}

// Highlight a candidate (index in the whole menu) without choosing it, for keys that act on the
// highlighted one (以词定字's [ and ]).
bool highlightCandidate(int index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->highlight_candidate(session(), index);
  refreshLocked();
  return r;
}

// Forget a learned candidate (index in the whole menu); system words lose their learned weight.
bool deleteCandidate(int index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->delete_candidate(session(), index);
  refreshLocked();
  return r;
}

std::string commitText() { return g.commit; }
std::string preedit() { return g.preedit; }
std::string input() { return g.input; }
int cursor() { return g.cursor; }

// Traditional → Simplified with the OpenCC data Rime already ships (used for voice input,
// whose model sometimes answers in traditional characters).
std::string t2s(const std::string& text, const std::string& config) {
  static std::mutex m;
  static std::unique_ptr<opencc::SimpleConverter> conv;
  static std::string loaded;
  std::lock_guard<std::mutex> lock(m);
  try {
    if (!conv || loaded != config) { conv = std::make_unique<opencc::SimpleConverter>(config); loaded = config; }
    return conv->Convert(text);
  } catch (...) {
    return text;
  }
}

}  // namespace kk
