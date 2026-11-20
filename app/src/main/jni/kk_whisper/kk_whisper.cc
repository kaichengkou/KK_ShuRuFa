// JNI bridge to whisper.cpp: load a model, transcribe 16 kHz mono float PCM, cancel.
#include <jni.h>
#include <atomic>
#include <string>
#include "whisper.h"

static std::atomic<bool> g_abort{false};

extern "C" JNIEXPORT jlong JNICALL
Java_com_kk_shurufa_voice_WhisperBridge_init(JNIEnv* env, jobject, jstring path) {
  const char* p = env->GetStringUTFChars(path, nullptr);
  whisper_context_params cp = whisper_context_default_params();
  cp.use_gpu = false;
  whisper_context* ctx = whisper_init_from_file_with_params(p, cp);
  env->ReleaseStringUTFChars(path, p);
  return reinterpret_cast<jlong>(ctx);
}

extern "C" JNIEXPORT void JNICALL
Java_com_kk_shurufa_voice_WhisperBridge_free(JNIEnv*, jobject, jlong ctx) {
  if (ctx) whisper_free(reinterpret_cast<whisper_context*>(ctx));
}

extern "C" JNIEXPORT void JNICALL
Java_com_kk_shurufa_voice_WhisperBridge_cancel(JNIEnv*, jobject) { g_abort = true; }

extern "C" JNIEXPORT jstring JNICALL
Java_com_kk_shurufa_voice_WhisperBridge_transcribe(JNIEnv* env, jobject, jlong handle, jfloatArray pcm,
                                                    jstring lang, jint threads, jstring prompt) {
  auto* ctx = reinterpret_cast<whisper_context*>(handle);
  if (!ctx) return env->NewStringUTF("");
  g_abort = false;
  const char* l = env->GetStringUTFChars(lang, nullptr);
  const char* pr = env->GetStringUTFChars(prompt, nullptr);
  std::string language(l), initial(pr);
  env->ReleaseStringUTFChars(lang, l);
  env->ReleaseStringUTFChars(prompt, pr);

  whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
  wp.print_progress = false;
  wp.print_realtime = false;
  wp.print_timestamps = false;
  wp.print_special = false;
  wp.translate = false;
  wp.no_context = true;
  wp.no_timestamps = true;
  wp.suppress_blank = true;
  wp.n_threads = threads > 0 ? threads : 4;
  wp.language = language.c_str();
  wp.detect_language = false;
  if (!initial.empty()) wp.initial_prompt = initial.c_str();
  wp.abort_callback = [](void*) { return g_abort.load(); };
  wp.abort_callback_user_data = nullptr;

  jsize n = env->GetArrayLength(pcm);
  jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
  int rc = whisper_full(ctx, wp, samples, n);
  env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
  if (rc != 0 || g_abort) return env->NewStringUTF("");

  std::string out;
  const int segs = whisper_full_n_segments(ctx);
  for (int i = 0; i < segs; ++i) out += whisper_full_get_segment_text(ctx, i);
  return env->NewStringUTF(out.c_str());
}

/** Language whisper detected in the last run ("zh", "en", "ru", ...). */
extern "C" JNIEXPORT jstring JNICALL
Java_com_kk_shurufa_voice_WhisperBridge_lastLanguage(JNIEnv* env, jobject, jlong handle) {
  auto* ctx = reinterpret_cast<whisper_context*>(handle);
  if (!ctx) return env->NewStringUTF("");
  int id = whisper_full_lang_id(ctx);
  const char* s = id >= 0 ? whisper_lang_str(id) : "";
  return env->NewStringUTF(s ? s : "");
}
