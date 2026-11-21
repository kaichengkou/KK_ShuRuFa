-- KK输入法: marks candidates that came from typo correction.
-- Runs before rime-ice's corrector.lua, while the comment still holds the candidate's spelling
-- ("［ni hao］"). When that spelling differs from what was typed by swapped or replaced letters,
-- one letter too many, or one letter missing, the comment becomes "〔kk:typed>correct〕"; the
-- keyboard draws a small proofreading mark from it. corrector.lua leaves comments in this format
-- alone. A missing letter after a consonant, before another or at the end, is not marked: that
-- is how abbreviations look (nhao for ni hao, jingtain for jing tai na), not a typo.
local function norm(s)
  return (s:gsub("[%s']", ""):gsub("ü", "v"))
end

local function same(a, b)
  return a == b or (a == "u" and b == "v") or (a == "v" and b == "u")
end

-- A correction is kept only when it is one slip (one letter changed, added, dropped, or two
-- neighbours swapped) and reaches the end of what was typed: a corrected fragment (挤 out of
-- jiicuo) or a word several letters away (会错, 后厝 out of jiicuo) only gets in the way.
local COMMON = -12.0

local function filter(input, env)
  local text = env.engine.context.input
  local state, sentence, held = "start", nil, {}
  local function flush()
    -- a common word (log weight above COMMON) reading the whole corrected input goes first; a
    -- rare one (拟好 against 你好, 我么 against 我们) leaves Rime's own sentence where it is
    local word
    for i, c in ipairs(held) do
      if c.type ~= "sentence" and c.start == sentence.start and c._end == sentence._end
        and c.comment:find("〔kk:", 1, true) then
        local ph = c:get_genuine():to_phrase()
        if ph and ph.weight > COMMON then word = i end
        break
      end
    end
    if word then yield(held[word]) end
    yield(sentence)
    for i, c in ipairs(held) do if i ~= word then yield(c) end end
    held = {}
  end
  for cand in input:iter() do
    local keep = true
    local spell = cand.comment:match("^［(.-)］")
    if spell then
      local typed = norm(text:sub(cand.start + 1, cand._end))
      local want = norm(spell)
      if #typed + 1 == #want or #typed == #want + 1 then
        -- one letter more or less: the rest must match around it
        local long, short = want, typed
        if #typed > #want then long, short = typed, want end
        local i = 1
        while i <= #short and same(long:sub(i, i), short:sub(i, i)) do i = i + 1 end
        local rest = true
        for j = i, #short do
          if not same(long:sub(j + 1, j + 1), short:sub(j, j)) then rest = false break end
        end
        local cons = "[bcdfghjklmnpqrstwxyz]"
        -- a letter left out after a consonant, before another consonant or at the end
        local after = typed:sub(i, i)
        local abbreviation = #typed < #want and typed:sub(i - 1, i - 1):match(cons) and (after == "" or after:match(cons))
        if rest and not abbreviation then
          cand:get_genuine().comment = "〔kk:" .. typed .. ">" .. want .. "〕"
          if cand._end < #text then keep = false end
        elseif not rest and #typed > #want then
          -- typed longer and not one letter too many: more than one slip
          keep = false
        end
      elseif #typed > #want + 1 then
        -- several letters more than the spelling: not an abbreviation, not one slip
        keep = false
      elseif #typed == #want and typed ~= want then
        local diffs = {}
        for i = 1, #typed do
          if not same(typed:sub(i, i), want:sub(i, i)) then diffs[#diffs + 1] = i end
        end
        if #diffs > 0 then
          local swap = #diffs == 2 and diffs[2] == diffs[1] + 1
            and same(typed:sub(diffs[1], diffs[1]), want:sub(diffs[2], diffs[2]))
            and same(typed:sub(diffs[2], diffs[2]), want:sub(diffs[1], diffs[1]))
          if #diffs == 1 or swap then
            cand:get_genuine().comment = "〔kk:" .. typed .. ">" .. want .. "〕"
            if cand._end < #text then keep = false end
          else
            keep = false
          end
        end
      end
    end
    if keep then
      -- a word before a sentence pieced together from the same corrected syllables: 上海, not 上还.
      -- The sentence can also be the right reading itself (你好 out of nihoa, with 拟好 next).
      if state == "start" then
        if cand.type == "sentence" and cand.comment:find("〔kk:", 1, true) then sentence = cand; state = "looking"
        else yield(cand); state = "done" end
      elseif state == "looking" then
        held[#held + 1] = cand
        if #held >= 8 then flush(); state = "done" end
      else
        yield(cand)
      end
    end
  end
  if state == "looking" then flush() end
end

return filter
