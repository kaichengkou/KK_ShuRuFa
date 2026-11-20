-- KK输入法: marks candidates that came from typo correction.
-- Runs before rime-ice's corrector.lua, while the comment still holds the candidate's spelling
-- ("［ni hao］"). When that spelling differs from what was typed only by swapped or replaced
-- letters, the comment becomes "〔kk:typed>correct〕"; the keyboard draws a small proofreading
-- mark from it. corrector.lua leaves comments in this format alone.
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
      if #typed == #want and typed ~= want then
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
