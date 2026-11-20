-- KK输入法: keep face emoji (yellow smileys) where rime-ice puts them, but move every other
-- emoji behind the first few word candidates so typing does not turn into a wall of pictures.
local KEEP_FIRST = 5

local function first_cp(text)
  local ok, cp = pcall(utf8.codepoint, text, 1)
  if ok then return cp end
  return nil
end

local function is_emoji(cp)
  return cp ~= nil and (cp >= 0x1F000 or (cp >= 0x2600 and cp <= 0x27BF) or (cp >= 0x2B00 and cp <= 0x2BFF))
end

local function is_face(cp)
  return (cp >= 0x1F600 and cp <= 0x1F64F)      -- emoticons
      or (cp >= 0x1F910 and cp <= 0x1F92F)      -- more faces
      or (cp >= 0x1F970 and cp <= 0x1F97A)
      or (cp >= 0x1FAE0 and cp <= 0x1FAE8)
      or cp == 0x1F970 or cp == 0x1F973 or cp == 0x1F974 or cp == 0x1F975 or cp == 0x1F976
      or cp == 0x263A or cp == 0x2639
end

local function filter(input, env)
  local held = {}
  local shown = 0
  for cand in input:iter() do
    local cp = first_cp(cand.text)
    if shown < KEEP_FIRST and is_emoji(cp) and not is_face(cp) then
      table.insert(held, cand)
    else
      yield(cand)
      shown = shown + 1
      if shown == KEEP_FIRST then
        for _, c in ipairs(held) do yield(c) end
        held = {}
      end
    end
  end
  for _, c in ipairs(held) do yield(c) end
end

return filter
