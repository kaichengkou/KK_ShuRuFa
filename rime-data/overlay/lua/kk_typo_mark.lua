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

local function filter(input, env)
  local text = env.engine.context.input
  for cand in input:iter() do
    local spell = cand.comment:match("^［(.-)］$")
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
        if rest and not abbreviation then cand:get_genuine().comment = "〔kk:" .. typed .. ">" .. want .. "〕" end
      elseif #typed == #want and typed ~= want then
        local differs = false
        for i = 1, #typed do
          if not same(typed:sub(i, i), want:sub(i, i)) then differs = true break end
        end
        if differs then cand:get_genuine().comment = "〔kk:" .. typed .. ">" .. want .. "〕" end
      end
    end
    yield(cand)
  end
end

return filter
