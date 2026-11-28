// Build-time tool (runs on the Mac that builds the app, or any host): deploys the bundled Rime
// data once, so the keyboard ships the compiled dictionaries and never compiles them on the
// phone, where a keyboard has a few dozen MB of memory. Then types a few inputs as a check.
// Usage: kk_rime_deploy <shared_dir> <user_dir> [pinyin...]
// With KK_PREBUILT=1 it deploys nothing and starts the way the keyboard does (from <shared>/build).
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "kk_rime.h"

static void show(const char* label) {
  char* p = kk_rime_preedit();
  char* c = kk_rime_candidates(5);
  std::string list(c ? c : "");
  for (char& ch : list) if (ch == '\x1e') ch = ' '; else if (ch == '\x1f') ch = '/';
  std::printf("%s [%s] %s\n", label, p ? p : "", list.c_str());
  kk_rime_free(p);
  kk_rime_free(c);
}

int main(int argc, char** argv) {
  if (argc < 3) {
    std::fprintf(stderr, "usage: %s <shared_dir> <user_dir> [pinyin...]\n", argv[0]);
    return 2;
  }
  auto t0 = std::chrono::steady_clock::now();
  const char* pre = std::getenv("KK_PREBUILT");
  if (pre && *pre == '1') {
    kk_rime_startup_prebuilt(argv[1], argv[2]);
  } else {
    kk_rime_startup(argv[1], argv[2], true);
    kk_rime_join_maintenance();
  }
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
  std::printf("deploy: %lld ms\n", (long long)ms);
  if (!kk_rime_select_schema("rime_ice")) {
    std::fprintf(stderr, "rime_ice could not be selected\n");
    return 1;
  }
  int failed = 0;
  for (int i = 3; i < argc; i++) {
    for (const char* k = argv[i]; *k; k++) kk_rime_process_key(*k, 0);
    char* c = kk_rime_candidates(1);
    if (!c || !*c) failed++;
    kk_rime_free(c);
    show(argv[i]);
    kk_rime_clear_composition();
  }
  kk_rime_shutdown();
  return failed ? 1 : 0;
}
