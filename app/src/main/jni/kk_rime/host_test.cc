// Host smoke test for the bundled Rime data: deploy, then type pinyin and print candidates.
// Usage: kk_rime_test <shared_dir> <user_dir> [keys...]
#include <rime_api.h>
#include <chrono>
#include <cstdio>
#include <cstring>
#include <string>
#include <cstdlib>

extern void rime_require_module_lua();
extern void rime_require_module_octagram();
extern void kk_install_corrector();

static void show(RimeApi* api, RimeSessionId s, const char* label) {
  RIME_STRUCT(RimeCommit, commit);
  if (api->get_commit(s, &commit)) { printf("  commit: %s\n", commit.text); api->free_commit(&commit); }
  RIME_STRUCT(RimeContext, ctx);
  if (api->get_context(s, &ctx)) {
    printf("%s preedit=[%s] cands:", label, ctx.composition.preedit ? ctx.composition.preedit : "");
    for (int i = 0; i < ctx.menu.num_candidates && i < 10; i++)
      printf(" %d.%s%s%s", i + 1, ctx.menu.candidates[i].text,
             ctx.menu.candidates[i].comment ? "/" : "", ctx.menu.candidates[i].comment ? ctx.menu.candidates[i].comment : "");
    printf("\n");
    api->free_context(&ctx);
  }
}

int main(int argc, char** argv) {
  rime_require_module_lua();
  rime_require_module_octagram();
  RimeApi* api = rime_get_api();
  RIME_STRUCT(RimeTraits, t);
  t.shared_data_dir = argv[1];
  t.user_data_dir = argv[2];
  t.app_name = "rime.kk.test";
  t.log_dir = "";
  api->setup(&t);
  api->initialize(&t);
  kk_install_corrector();
  auto t0 = std::chrono::steady_clock::now();
  api->start_maintenance(True);
  api->join_maintenance_thread();
  auto ms = std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - t0).count();
  printf("deploy took %lld ms\n", (long long)ms);
  RimeSessionId s = api->create_session();
  printf("select_schema: %d\n", api->select_schema(s, "rime_ice"));
  RIME_STRUCT(RimeStatus, st);
  if (api->get_status(s, &st)) { printf("schema: %s (%s)\n", st.schema_id, st.schema_name); api->free_status(&st); }
  for (int i = 3; i < argc; i++) {
    const char* seq = argv[i];
    if (strcmp(seq, "+emoji") == 0) { api->set_option(s, "emoji", True); continue; }
    if (strcmp(seq, "-emoji") == 0) { api->set_option(s, "emoji", False); continue; }
    if (strcmp(seq, "+nolearn") == 0) { api->set_option(s, "_kk_no_learning", True); continue; }
    if (strcmp(seq, "-nolearn") == 0) { api->set_option(s, "_kk_no_learning", False); continue; }
    if (strcmp(seq, "+trad") == 0) { api->set_option(s, "traditionalization", True); continue; }
    if (strcmp(seq, "-trad") == 0) { api->set_option(s, "traditionalization", False); continue; }
    if (strncmp(seq, "@caret=", 7) == 0) {  // move the caret inside the current input, then show
      api->set_caret_pos(s, atoi(seq + 7));
      printf("  caret -> %zu\n", api->get_caret_pos(s));
      continue;
    }
    if (strncmp(seq, "+keep:", 6) == 0) {  // type without clearing afterwards
      for (const char* p = seq + 6; *p; p++) api->process_key(s, *p, 0);
      show(api, s, seq);
      continue;
    }
    if (strncmp(seq, "?", 1) == 0) {  // query in a scratch session while the main one keeps its input
      RimeSessionId q = api->create_session();
      api->set_input(q, seq + 1);
      show(api, q, seq);
      api->destroy_session(q);
      show(api, s, "  main still");
      continue;
    }
    if (strncmp(seq, "@learn=", 7) == 0) {  // @learn=shi:十 — teach a commit through a session of its own
      std::string arg(seq + 7); size_t c = arg.find(':');
      auto lt0 = std::chrono::steady_clock::now();
      RimeSessionId t = api->create_session();
      api->set_option(t, "_kk_no_learning", False);
      api->set_input(t, arg.substr(0, c).c_str());
      std::string want = arg.substr(c + 1);
      RimeCandidateListIterator it = {0};
      int pick = -1;
      if (api->candidate_list_begin(t, &it)) {
        while (api->candidate_list_next(&it)) if (it.candidate.text && want == it.candidate.text) { pick = it.index; break; }
        api->candidate_list_end(&it);
      }
      if (pick >= 0) api->select_candidate(t, pick);
      RIME_STRUCT(RimeCommit, cm);
      bool ok = api->get_commit(t, &cm); if (ok) api->free_commit(&cm);
      api->destroy_session(t);
      printf("  learn %s -> %d (%lld us with its session)\n", arg.c_str(), ok,
             (long long)std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - lt0).count());
      continue;
    }
    if (strncmp(seq, "@many=", 6) == 0) {  // @many=300:zhongguorenmin — time typing it and listing that many candidates
      std::string arg(seq + 6); size_t c = arg.find(':');
      int n = atoi(arg.substr(0, c).c_str());
      auto t0 = std::chrono::steady_clock::now();
      for (const char* p = arg.c_str() + c + 1; *p; p++) api->process_key(s, *p, 0);
      auto t1 = std::chrono::steady_clock::now();
      int got = 0;
      RimeCandidateListIterator it = {0};
      if (api->candidate_list_begin(s, &it)) {
        while (got < n && api->candidate_list_next(&it)) got++;
        api->candidate_list_end(&it);
      }
      auto t2 = std::chrono::steady_clock::now();
      auto us = [](auto a, auto b) { return (long long)std::chrono::duration_cast<std::chrono::microseconds>(b - a).count(); };
      printf("  %s: typing %lld us, %d candidates %lld us\n", arg.c_str(), us(t0, t1), got, us(t1, t2));
      api->clear_composition(s);
      continue;
    }
    if (strcmp(seq, "@sync") == 0) {  // Rime's sync: write snapshots, merge the others
      printf("  sync -> %d\n", api->sync_user_data());
      api->join_maintenance_thread();
      s = api->create_session();
      api->select_schema(s, "rime_ice");
      continue;
    }
    if (strncmp(seq, "@sel=", 5) == 0) {  // choose a candidate of the current input (0-based)
      api->select_candidate(s, atoi(seq + 5));
      show(api, s, seq);
      api->clear_composition(s);
      continue;
    }
    if (strncmp(seq, "@hl=", 4) == 0) {  // highlight a candidate of the current input (0-based)
      printf("  highlight %d -> %d\n", atoi(seq + 4), api->highlight_candidate(s, atoi(seq + 4)));
      show(api, s, seq);
      continue;
    }
    if (strncmp(seq, "@key=", 5) == 0) {  // a key by its code (0x5b is [), the input kept
      api->process_key(s, (int)strtol(seq + 5, nullptr, 0), 0);
      show(api, s, seq);
      continue;
    }
    if (strcmp(seq, "SPACE") == 0) { api->process_key(s, 0x20, 0); show(api, s, "[space]"); continue; }
    for (const char* p = seq; *p; p++) api->process_key(s, *p, 0);
    show(api, s, seq);
    api->clear_composition(s);
  }
  api->destroy_session(s);
  api->finalize();
  return 0;
}
