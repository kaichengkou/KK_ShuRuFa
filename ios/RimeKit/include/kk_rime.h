// C interface to KK输入法's Rime session (app/src/main/jni/kk_rime/kk_core.h), for Swift.
// Strings are UTF-8. Every `char*` returned is the caller's: release it with kk_rime_free.
// Lists come back as one string: entries separated by 0x1E, and inside an entry the candidate's
// text and comment separated by 0x1F.
#ifndef KK_RIME_H
#define KK_RIME_H

#include <stdbool.h>

#ifdef __cplusplus
extern "C" {
#endif

// Deploys (compiles what changed) in the background; kk_rime_join_maintenance waits for it.
void kk_rime_startup(const char* shared_dir, const char* user_dir, bool full_check);
// Starts on data compiled ahead of time (<shared_dir>/build), deploying nothing: the keyboard's way.
void kk_rime_startup_prebuilt(const char* shared_dir, const char* user_dir);
void kk_rime_join_maintenance(void);
void kk_rime_shutdown(void);

bool kk_rime_process_key(int keycode, int mask);
bool kk_rime_select_candidate(int index);
bool kk_rime_commit_composition(void);
void kk_rime_clear_composition(void);
void kk_rime_set_option(const char* name, bool value);
bool kk_rime_get_option(const char* name);
bool kk_rime_select_schema(const char* schema_id);
bool kk_rime_set_input(const char* input);
int kk_rime_caret_pos(void);
void kk_rime_set_caret_pos(int pos);
bool kk_rime_highlight_candidate(int index);
bool kk_rime_delete_candidate(int index);

char* kk_rime_candidates(int limit);
char* kk_rime_query(const char* input, int limit);
bool kk_rime_learn(const char* input, const char* text);
bool kk_rime_sync_user_data(void);

int kk_rime_generation(void);
char* kk_rime_commit_text(void);
char* kk_rime_preedit(void);
char* kk_rime_input(void);
int kk_rime_cursor(void);

char* kk_rime_t2s(const char* text, const char* config);

void kk_rime_free(char* s);

#ifdef __cplusplus
}
#endif

#endif
