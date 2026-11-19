-- Fetch two norns scripts from their authors' GitHub repositories into ~/.afield/norns/code/.
-- Neither repository states a licence, so Field does not ship them; this box clones them for you.
field.budget = 120
local code = os.getenv("HOME") .. "/.afield/norns/code/"
os.execute('mkdir -p "' .. code .. '"')
for _, repo in ipairs { "SPIKE-the-Percussionist/noiz_virus", "om-i-god/anima" } do
  local name = repo:match("/(.+)$")
  local dir = code .. name
  local f = io.open(dir .. "/" .. name .. ".lua")
  if f then f:close(); print(name, "already in", dir)
  else
    local ok = os.execute('git clone -q --depth 1 https://github.com/' .. repo .. ' "' .. dir .. '"')
    print(name, (ok == 0 or ok == true) and "cloned into" or "could not be cloned into", dir)
  end
end
