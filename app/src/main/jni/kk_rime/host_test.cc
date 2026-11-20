// Host smoke test for the bundled Rime data: deploy, then type pinyin and print candidates.
// Usage: kk_rime_test <shared_dir> <user_dir> [keys...]
#include <rime_api.h>
#include <chrono>
#include <cstdio>
#include <cstring>

extern void rime_require_module_lua();
extern void rime_require_module_octagram();

static void show(RimeApi* api, RimeSessionId s, const char* label) {
  RIME_STRUCT(RimeCommit, commit);
  if (api->get_commit(s, &commit)) { printf("  commit: %s\n", commit.text); api->free_commit(&commit); }
  RIME_STRUCT(RimeContext, ctx);
  if (api->get_context(s, &ctx)) {
    printf("%s preedit=[%s] cands:", label, ctx.composition.preedit ? ctx.composition.preedit : "");
    for (int i = 0; i < ctx.menu.num_candidates && i < 8; i++)
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
    if (strcmp(seq, "+trad") == 0) { api->set_option(s, "traditionalization", True); continue; }
    if (strcmp(seq, "-trad") == 0) { api->set_option(s, "traditionalization", False); continue; }
    if (strcmp(seq, "SPACE") == 0) { api->process_key(s, 0x20, 0); show(api, s, "[space]"); continue; }
    for (const char* p = seq; *p; p++) api->process_key(s, *p, 0);
    show(api, s, seq);
    api->clear_composition(s);
  }
  api->destroy_session(s);
  api->finalize();
  return 0;
}
