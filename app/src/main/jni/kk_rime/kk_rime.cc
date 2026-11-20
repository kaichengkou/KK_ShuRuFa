// JNI bridge between the Kotlin input method and librime.
// One Rime session is kept for the whole input method; the Kotlin side calls
// refresh() after every key and then reads the cached context through getters.

#include <jni.h>
#include <rime_api.h>

#include <mutex>
#include <string>
#include <vector>

extern void rime_require_module_lua();
extern void rime_require_module_octagram();

namespace {

RimeApi* api() {
  static RimeApi* a = rime_get_api();
  return a;
}

std::mutex g_mutex;
RimeSessionId g_session = 0;

struct Cached {
  std::string preedit;
  std::string input;
  int cursor = 0;
  std::vector<std::string> cands;
  std::vector<std::string> comments;
  int highlighted = 0;
  int page = 0;
  bool lastPage = true;
  std::string commit;
  std::string schemaId;
  std::string schemaName;
  bool asciiMode = false;
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

void refreshLocked() {
  RimeSessionId s = session();
  g.commit.clear();
  RIME_STRUCT(RimeCommit, commit);
  if (api()->get_commit(s, &commit)) {
    if (commit.text) g.commit = commit.text;
    api()->free_commit(&commit);
  }
  g.preedit.clear();
  g.input.clear();
  g.cands.clear();
  g.comments.clear();
  g.cursor = 0;
  g.highlighted = 0;
  g.page = 0;
  g.lastPage = true;
  RIME_STRUCT(RimeContext, ctx);
  if (api()->get_context(s, &ctx)) {
    if (ctx.composition.preedit) g.preedit = ctx.composition.preedit;
    g.cursor = ctx.composition.cursor_pos;
    for (int i = 0; i < ctx.menu.num_candidates; i++) {
      g.cands.emplace_back(ctx.menu.candidates[i].text ? ctx.menu.candidates[i].text : "");
      g.comments.emplace_back(ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
    }
    g.highlighted = ctx.menu.highlighted_candidate_index;
    g.page = ctx.menu.page_no;
    g.lastPage = ctx.menu.is_last_page;
    api()->free_context(&ctx);
  }
  const char* in = api()->get_input(s);
  if (in) g.input = in;
  RIME_STRUCT(RimeStatus, st);
  if (api()->get_status(s, &st)) {
    g.schemaId = st.schema_id ? st.schema_id : "";
    g.schemaName = st.schema_name ? st.schema_name : "";
    g.asciiMode = st.is_ascii_mode;
    api()->free_status(&st);
  }
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
  api()->start_maintenance(fullCheck);
}

FN(jboolean, isMaintaining)(JNIEnv*, jobject) { return api()->is_maintenance_mode(); }
FN(void, joinMaintenance)(JNIEnv*, jobject) { api()->join_maintenance_thread(); }

FN(void, shutdown)(JNIEnv*, jobject) {
  std::lock_guard<std::mutex> lock(g_mutex);
  if (g_session) api()->destroy_session(g_session);
  g_session = 0;
  api()->finalize();
}

FN(jboolean, processKey)(JNIEnv*, jobject, jint keycode, jint mask) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->process_key(session(), keycode, mask);
  refreshLocked();
  return r;
}

FN(jboolean, selectCandidate)(JNIEnv*, jobject, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->select_candidate_on_current_page(session(), index);
  refreshLocked();
  return r;
}

FN(jboolean, changePage)(JNIEnv*, jobject, jboolean backward) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->change_page(session(), backward);
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

FN(jboolean, getOption)(JNIEnv* env, jobject, jstring name) {
  std::lock_guard<std::mutex> lock(g_mutex);
  return api()->get_option(session(), str(env, name).c_str());
}

FN(jboolean, selectSchema)(JNIEnv* env, jobject, jstring id) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->select_schema(session(), str(env, id).c_str());
  refreshLocked();
  return r;
}

// Up to `limit` candidates from the start of the menu, for the expanded candidate view.
FN(jobjectArray, candidates)(JNIEnv* env, jobject, jint limit) {
  std::lock_guard<std::mutex> lock(g_mutex);
  std::vector<std::string> v;
  RimeCandidateListIterator it = {0};
  if (api()->candidate_list_begin(session(), &it)) {
    while (static_cast<int>(v.size()) < limit && api()->candidate_list_next(&it)) {
      v.emplace_back(it.candidate.text ? it.candidate.text : "");
    }
    api()->candidate_list_end(&it);
  }
  return jarr(env, v);
}

FN(jboolean, selectCandidateGlobal)(JNIEnv*, jobject, jint index) {
  std::lock_guard<std::mutex> lock(g_mutex);
  bool r = api()->select_candidate(session(), index);
  refreshLocked();
  return r;
}

FN(jstring, commitText)(JNIEnv* env, jobject) { return jstr(env, g.commit); }
FN(jstring, preedit)(JNIEnv* env, jobject) { return jstr(env, g.preedit); }
FN(jstring, input)(JNIEnv* env, jobject) { return jstr(env, g.input); }
FN(jint, cursor)(JNIEnv*, jobject) { return g.cursor; }
FN(jobjectArray, pageCandidates)(JNIEnv* env, jobject) { return jarr(env, g.cands); }
FN(jobjectArray, pageComments)(JNIEnv* env, jobject) { return jarr(env, g.comments); }
FN(jint, highlighted)(JNIEnv*, jobject) { return g.highlighted; }
FN(jboolean, isLastPage)(JNIEnv*, jobject) { return g.lastPage; }
FN(jint, pageNo)(JNIEnv*, jobject) { return g.page; }
FN(jstring, schemaId)(JNIEnv* env, jobject) { return jstr(env, g.schemaId); }
FN(jstring, schemaName)(JNIEnv* env, jobject) { return jstr(env, g.schemaName); }
