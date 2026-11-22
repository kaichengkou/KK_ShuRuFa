-- KK输入法 不再出现: candidates the person asked never to see again (the words in kk_muted.txt in
-- the user folder, one per line) are left out. The list is read when the engine starts; the
-- keyboard restarts its engine after the list changes.
local M = {}

function M.init(env)
  env.muted = {}
  local f = io.open(rime_api.get_user_data_dir() .. "/kk_muted.txt", "r")
  if f then
    for line in f:lines() do
      if #line > 0 then env.muted[line] = true end
    end
    f:close()
  end
  env.any = next(env.muted) ~= nil
end

function M.func(input, env)
  for cand in input:iter() do
    if not (env.any and env.muted[cand.text]) then yield(cand) end
  end
end

return M
