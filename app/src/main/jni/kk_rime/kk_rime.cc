// JNI bridge between the Kotlin input method and librime.
// One Rime session is kept for the whole input method. Every call that changes it refreshes a
// small cache (commit, preedit, input, cursor) that the getters read, and counts a generation,
// so the Kotlin side can keep the candidates it fetched until the composition really changes.

#include <jni.h>
#include <rime_api.h>

#include <mutex>
#include <memory>
#include <opencc/SimpleConverter.hpp>
#include <string>
#include <vector>

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

std::string str(JNIEnv* env, jstring s) {
  if (!s) return {};
  const char* c = env->GetStringUTFChars(s, nullptr);
  std::string r(c);
  env->ReleaseStringUTFChars(s, c);
  return r;
}

// librime returns UTF-8, which may contain supplementary characters (emoji).
// NewStringUTF expects modified UTF-8, so build the string from UTF-16.
jstring jstr(JNIEnv* env, const std::string& s) {
  std::u16string out;
  out.reserve(s.size());
  for (size_t i = 0; i < s.size();) {
    unsigned char c = s[i];
    uint32_t cp;
    int n;
    if (c < 0x80) { cp = c; n = 1; }
    else if ((c >> 5) == 0x6) { cp = c & 0x1f; n = 2; }
    else if ((c >> 4) == 0xe) { cp = c & 0x0f; n = 3; }
    else if ((c >> 3) == 0x1e) { cp = c & 0x07; n = 4; }
    else { i++; continue; }
    if (i + n > s.size()) break;
    for (int k = 1; k < n; k++) cp = (cp << 6) | (s[i + k] & 0x3f);
    i += n;
    if (cp >= 0x10000) {
      cp -= 0x10000;
      out.push_back(static_cast<char16_t>(0xd800 + (cp >> 10)));
      out.push_back(static_cast<char16_t>(0xdc00 + (cp & 0x3ff)));
    } else {
      out.push_back(static_cast<char16_t>(cp));
    }
  }
  return env->NewString(reinterpret_cast<const jchar*>(out.data()), out.size());
}

jobjectArray jarr(JNIEnv* env, const std::vector<std::string>& v) {
  jclass cls = env->FindClass("java/lang/String");
  jobjectArray arr = env->NewObjectArray(v.size(), cls, nullptr);
  for (size_t i = 0; i < v.size(); i++) {
    jstring s = jstr(env, v[i]);
    env->SetObjectArrayElement(arr, i, s);
    env->DeleteLocalRef(s);
  }
  return arr;
}

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

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_kk_shurufa_rime_RimeBridge_##name

FN(void, startup)(JNIEnv* env, jobject, jstring shared, jstring user, jboolean fullCheck) {
  std::lock_guard<std::mutex> lock(g_mutex);
  static bool modulesDeclared = false;
  if (!modulesDeclared) {
    // librime is linked statically; reference the plugins so the linker keeps them.
    rime_require_module_lua();
    rime_require_module_octagram();
    modulesDeclared = true;
  }
  static std::string sharedDir, userDir;
  sharedDir = str(env, shared);
  userDir = str(env, user);
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
  api()->start_maintenance(fullCheck);
}

FN(void, joinMaintenance)(JNIEnv*, jobject) { api()->join_maintenance_thread(); }

FN(void, shutdown)(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session) api()->destroy_session(g_session);
  forgetSessions();
  g.generation++;
  api()->finalize();
}

FN(jboolean, processKey)(JNIEnv*, jobject, jint keycode, jint mask) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->process_key(session(), keycode, mask);
  refreshLocked();
  return r;
}

FN(jboolean, commitComposition)(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->commit_composition(session());
  refreshLocked();
  return r;
}

FN(void, clearComposition)(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  api()->clear_composition(session());
  refreshLocked();
}

FN(void, setOption)(JNIEnv* env, jobject, jstring name, jboolean value) {
  std::lock_guard<std::mutex> lock(g_mutex);
  api()->set_option(session(), str(env, name).c_str(), value);
  refreshLocked();
}

// Replaces the raw input of the composition (used to bring back the pinyin of a committed word).
FN(jboolean, setInput)(JNIEnv* env, jobject, jstring input) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->set_input(session(), str(env, input).c_str());
  refreshLocked();
  return r;
}

FN(jboolean, selectSchema)(JNIEnv* env, jobject, jstring id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  // the engine is built again (its Lua filters read their lists afresh): the other sessions too
  for (RimeSessionId* other : {&g_scratch, &g_learn}) {
    if (*other && api()->find_session(*other)) api()->destroy_session(*other);
    *other = 0;
  }
  bool r = api()->select_schema(session(), str(env, id).c_str());
  refreshLocked();
  return r;
}

// Up to `limit` candidates from the start of the menu, texts only.
FN(jobjectArray, candidates)(JNIEnv* env, jobject, jint limit) {
  std::lock_guard<std::mutex> lock(g_mutex);
  std::vector<std::string> v;
  listCandidates(session(), limit, &v, nullptr);
  return jarr(env, v);
}

// Up to `limit` candidates with their comments, in one pass over the menu: the n texts, then
// their n comments. Every candidate fetched runs through the translators and the Lua filters,
// so the menu is walked once, not once for the texts and again for the comments.
FN(jobjectArray, candidatesWithComments)(JNIEnv* env, jobject, jint limit) {
  std::lock_guard<std::mutex> lock(g_mutex);
  std::vector<std::string> v, c;
  listCandidates(session(), limit, &v, &c);
  v.insert(v.end(), c.begin(), c.end());
  return jarr(env, v);
}

// Changes with every change of the composition: candidates fetched at one generation are still
// the menu's while it stays the same.
FN(jint, generation)(JNIEnv*, jobject) { return g.generation; }

// Caret inside the raw input (in letters), for editing the pinyin in place.
FN(jint, caretPos)(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return static_cast<jint>(api()->get_caret_pos(session()));
}
FN(void, setCaretPos)(JNIEnv*, jobject, jint pos) {
  std::lock_guard<std::mutex> lock(g_mutex);
  api()->set_caret_pos(session(), pos);
  refreshLocked();
}

// Candidates for some input in a separate session, without touching what the user is typing.
FN(jobjectArray, query)(JNIEnv* env, jobject, jstring input, jint limit) {
  std::lock_guard<std::mutex> lock(g_mutex);
  RimeSessionId s = kept(g_scratch);
  std::vector<std::string> v;
  api()->clear_composition(s);
  if (api()->set_input(s, str(env, input).c_str())) listCandidates(s, limit, &v, nullptr);
  api()->clear_composition(s);
  return jarr(env, v);
}

// Learning after the fact: the keyboard's session learns nothing (_kk_no_learning), and a commit
// that stayed (not deleted right away) is taught here: [input] typed again in a session of its
// own, with learning on, and candidates chosen until exactly [text] is committed. Nothing is
// learned when the text cannot be made again from the input. The session is kept from one
// teaching to the next (making one builds a whole engine), and the lock is held only for this.
FN(jboolean, learn)(JNIEnv* env, jobject, jstring jinput, jstring jtext) {
  const std::string input = str(env, jinput), text = str(env, jtext);
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

FN(jboolean, selectCandidateGlobal)(JNIEnv*, jobject, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->select_candidate(session(), index);
  refreshLocked();
  return r;
}

// Rime's sync: writes a text snapshot of each user dictionary to <user>/sync/<installation>/ and
// merges every snapshot found in the other folders there. Closes the sessions; ours is made again
// on its next use. Blocking.
FN(jboolean, syncUserData)(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->sync_user_data();
  api()->join_maintenance_thread();
  forgetSessions();
  g.generation++;
  return r;
}

// Highlight a candidate (index in the whole menu) without choosing it, for keys that act on the
// highlighted one (以词定字's [ and ]).
FN(jboolean, highlightCandidate)(JNIEnv*, jobject, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->highlight_candidate(session(), index);
  refreshLocked();
  return r;
}

// Forget a learned candidate (index in the whole menu); system words lose their learned weight.
FN(jboolean, deleteCandidate)(JNIEnv*, jobject, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->delete_candidate(session(), index);
  refreshLocked();
  return r;
}

FN(jstring, commitText)(JNIEnv* env, jobject) { return jstr(env, g.commit); }
FN(jstring, preedit)(JNIEnv* env, jobject) { return jstr(env, g.preedit); }
FN(jstring, input)(JNIEnv* env, jobject) { return jstr(env, g.input); }
FN(jint, cursor)(JNIEnv*, jobject) { return g.cursor; }

// Traditional → Simplified with the OpenCC data Rime already ships (used for voice input,
// whose model sometimes answers in traditional characters).
FN(jstring, t2s)(JNIEnv* env, jclass, jstring text, jstring config) {
  static std::mutex m;
  static std::unique_ptr<opencc::SimpleConverter> conv;
  static std::string loaded;
  std::string t = str(env, text), c = str(env, config);
  std::lock_guard<std::mutex> g(m);
  try {
    if (!conv || loaded != c) { conv = std::make_unique<opencc::SimpleConverter>(c); loaded = c; }
    // jstr, not NewStringUTF: emoji and the rarer characters are four UTF-8 bytes
    return jstr(env, conv->Convert(t));
  } catch (...) {
    return text;
  }
}
