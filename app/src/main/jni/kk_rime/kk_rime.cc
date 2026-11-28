// JNI bridge between the Kotlin input method and librime. The session itself lives in kk_core.cc
// (shared with the iOS keyboard); this file only converts strings between Java and UTF-8.

#include <jni.h>

#include <cstdint>
#include <string>
#include <vector>

#include "kk_core.h"

namespace {

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

}  // namespace

#define FN(ret, name) extern "C" JNIEXPORT ret JNICALL Java_com_kk_shurufa_rime_RimeBridge_##name

FN(void, startup)(JNIEnv* env, jobject, jstring shared, jstring user, jboolean fullCheck) {
  kk::startup(str(env, shared), str(env, user), fullCheck);
}

FN(void, joinMaintenance)(JNIEnv*, jobject) { kk::joinMaintenance(); }

FN(void, shutdown)(JNIEnv*, jobject) { kk::shutdown(); }

FN(jboolean, processKey)(JNIEnv*, jobject, jint keycode, jint mask) { return kk::processKey(keycode, mask); }

FN(jboolean, commitComposition)(JNIEnv*, jobject) { return kk::commitComposition(); }

FN(void, clearComposition)(JNIEnv*, jobject) { kk::clearComposition(); }

FN(void, setOption)(JNIEnv* env, jobject, jstring name, jboolean value) { kk::setOption(str(env, name), value); }

FN(jboolean, setInput)(JNIEnv* env, jobject, jstring input) { return kk::setInput(str(env, input)); }

FN(jboolean, selectSchema)(JNIEnv* env, jobject, jstring id) { return kk::selectSchema(str(env, id)); }

// Up to `limit` candidates from the start of the menu, texts only.
FN(jobjectArray, candidates)(JNIEnv* env, jobject, jint limit) {
  std::vector<std::string> v;
  kk::candidates(limit, &v, nullptr);
  return jarr(env, v);
}

// Up to `limit` candidates with their comments, in one pass over the menu: the n texts, then
// their n comments.
FN(jobjectArray, candidatesWithComments)(JNIEnv* env, jobject, jint limit) {
  std::vector<std::string> v, c;
  kk::candidates(limit, &v, &c);
  v.insert(v.end(), c.begin(), c.end());
  return jarr(env, v);
}

FN(jint, generation)(JNIEnv*, jobject) { return kk::generation(); }

FN(jint, caretPos)(JNIEnv*, jobject) { return kk::caretPos(); }
FN(void, setCaretPos)(JNIEnv*, jobject, jint pos) { kk::setCaretPos(pos); }

FN(jobjectArray, query)(JNIEnv* env, jobject, jstring input, jint limit) {
  return jarr(env, kk::query(str(env, input), limit));
}

FN(jboolean, learn)(JNIEnv* env, jobject, jstring input, jstring text) {
  return kk::learn(str(env, input), str(env, text));
}

FN(jboolean, selectCandidateGlobal)(JNIEnv*, jobject, jint index) { return kk::selectCandidate(index); }

FN(jboolean, syncUserData)(JNIEnv*, jobject) { return kk::syncUserData(); }

FN(jboolean, highlightCandidate)(JNIEnv*, jobject, jint index) { return kk::highlightCandidate(index); }

FN(jboolean, deleteCandidate)(JNIEnv*, jobject, jint index) { return kk::deleteCandidate(index); }

FN(jstring, commitText)(JNIEnv* env, jobject) { return jstr(env, kk::commitText()); }
FN(jstring, preedit)(JNIEnv* env, jobject) { return jstr(env, kk::preedit()); }
FN(jstring, input)(JNIEnv* env, jobject) { return jstr(env, kk::input()); }
FN(jint, cursor)(JNIEnv*, jobject) { return kk::cursor(); }

// jstr, not NewStringUTF: emoji and the rarer characters are four UTF-8 bytes
FN(jstring, t2s)(JNIEnv* env, jclass, jstring text, jstring config) {
  return jstr(env, kk::t2s(str(env, text), str(env, config)));
}
