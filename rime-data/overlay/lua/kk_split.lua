-- KK输入法 离合词: a word with a piece put inside it, the way people say it — 安排 → 安不安排,
-- 解决 → 解不解决, 做饭 → 做了饭, 报到 → 报了到, 见面 → 见过面. When the whole pinyin is such a
-- word with 不 (A bu A B), 了 (A le B) or 过 (A guo B) inside, the word put together is offered
-- second, behind what Rime puts first; never first, never when Rime already has it, and only
-- when the word is Rime's own first reading of the pinyin without the piece (so it is a real
-- word, and the syllable before the piece really is its first character).
local M = {}

function M.init(env)
  env.tran = Component.ScriptTranslator(env.engine, "translator", "script_translator")
end

local function len(s) return utf8.len(s) or 0 end
local function first(s) return s:sub(1, utf8.offset(s, 2) - 1) end
local function rest(s) return s:sub(utf8.offset(s, 2)) end

-- Rime's candidates for [code] that read all of it, the first [n].
local function whole(env, code, seg, n)
  local out = {}
  local tr = env.tran:query(code, seg)
  if not tr then return out end
  for c in tr:iter() do
    if c._end - c.start == #code then out[#out + 1] = c.text end
    if #out >= n then break end
  end
  return out
end

local function made(env, input, seg)
  if #input < 5 or #input > 18 or input:find("[^a-z]") then return nil end
  -- A 不 A B: the syllable said twice around bu
  for i = 2, 6 do
    local a = input:sub(1, i)
    if input:sub(i + 1, i + 2) == "bu" and input:sub(i + 3, 2 * i + 2) == a then
      local b = input:sub(2 * i + 3)
      if #b >= 1 then
        local w = whole(env, a .. b, seg, 1)[1]
        if w and len(w) == 2 then return first(w) .. "不" .. w end
      end
    end
  end
  -- A 了/过 B: a two-character word with 了 or 过 inside
  for _, piece in ipairs({ { "le", "了" }, { "guo", "过" } }) do
    local k, ch = piece[1], piece[2]
    for i = 2, 6 do
      local a = input:sub(1, i)
      if input:sub(i + 1, i + #k) == k then
        local b = input:sub(i + #k + 1)
        if #b >= 2 then
          local w = whole(env, a .. b, seg, 1)[1]
          if w and len(w) == 2 then
            -- the letters before the piece spell the word's first character on their own
            for _, c in ipairs(whole(env, a, seg, 12)) do
              if c == first(w) then return first(w) .. ch .. rest(w) end
            end
          end
        end
      end
    end
  end
  return nil
end

function M.func(translation, env)
  local ctx = env.engine.context
  local seg = ctx.composition:back()
  local extra = nil
  if seg and seg.start == 0 and seg._end == #ctx.input then
    local ok, r = pcall(made, env, ctx.input, seg)
    if ok then extra = r end
  end
  local n = 0
  local held = {}
  for cand in translation:iter() do
    n = n + 1
    if extra and cand.text == extra then extra = nil end
    if n <= 3 then held[#held + 1] = cand else
      if #held > 0 then
        for j, h in ipairs(held) do
          yield(h)
          if j == 1 and extra then yield(Candidate("split", seg.start, seg._end, extra, "")); extra = nil end
        end
        held = {}
      end
      yield(cand)
    end
  end
  for j, h in ipairs(held) do
    yield(h)
    if j == 1 and extra then yield(Candidate("split", seg.start, seg._end, extra, "")); extra = nil end
  end
end

return M
