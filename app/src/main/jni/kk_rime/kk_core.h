// The Rime session of KK输入法, in plain C++: what the Android JNI bridge (kk_rime.cc) and the
// iOS C bridge (ios/RimeKit/kk_rime_c.cc) both wrap, so the two platforms type the same way.
//
// One Rime session is kept for the whole input method. Every call that changes it refreshes a
// small cache (commit, preedit, input, cursor) that the getters read, and counts a generation,
// so the caller can keep the candidates it fetched until the composition really changes.
#pragma once

#include <string>
#include <vector>

namespace kk {

// Without [maintenance] nothing is deployed: Rime reads what was compiled ahead of time, from
// <shared>/build (the iOS keyboard ships it so, and never compiles on the phone).
void startup(const std::string& shared, const std::string& user, bool fullCheck, bool maintenance = true);
void joinMaintenance();
void shutdown();

bool processKey(int keycode, int mask);
bool selectCandidate(int index);
bool commitComposition();
void clearComposition();
void setOption(const std::string& name, bool value);
bool getOption(const std::string& name);
bool selectSchema(const std::string& id);
bool setInput(const std::string& input);
int caretPos();
void setCaretPos(int pos);
bool highlightCandidate(int index);
bool deleteCandidate(int index);

// The first `limit` candidates of the menu; their comments too when `comments` is given.
void candidates(int limit, std::vector<std::string>* texts, std::vector<std::string>* comments);
// Candidates for some other input, from a session of its own (the composition stays as it is).
std::vector<std::string> query(const std::string& input, int limit);
// Teaches Rime that `input` was meant as `text` (see kk_core.cc).
bool learn(const std::string& input, const std::string& text);
bool syncUserData();

int generation();
std::string commitText();
std::string preedit();
std::string input();
int cursor();

// Traditional → Simplified with an OpenCC config (a t2s.json path); the text as it was on failure.
std::string t2s(const std::string& text, const std::string& config);

}  // namespace kk
