-- One existing concurrency authority; legacy integer state is never migrated or deleted here.
-- Returns acquire: 0 admitted, 1/2/3 rate/quota, 4 capacity; release: 0 absent, 1 removed.
-- -1 means indeterminate/invalid coordination. Known argument/schema errors precede every write.
local MAX_INTEGER = 9007199254740991
local MAX_LEASES = 4096

local function integer(value, minimum, maximum)
  local number = tonumber(value)
  if not number or number < minimum or number > maximum or number ~= math.floor(number) then return nil end
  return number
end

local function decimal(value, maximum)
  if type(value) ~= 'string' or #value > 16 then return nil end
  if value ~= '0' and not string.match(value, '^[1-9]%d*$') then return nil end
  return integer(value, 0, maximum)
end

local function token_valid(token)
  if type(token) ~= 'string' or #token ~= 36 then return false end
  local compact = string.gsub(token, '%-', '')
  return #compact == 32 and string.match(compact, '^[0-9a-f]+$') and
    string.sub(token, 9, 9) == '-' and string.sub(token, 14, 14) == '-' and
    string.sub(token, 19, 19) == '-' and string.sub(token, 24, 24) == '-' and
    string.sub(token, 15, 15) == '4' and string.match(string.sub(token, 20, 20), '^[89ab]$')
end

local acquiring = ARGV[1] == 'acquire'
if not acquiring and ARGV[1] ~= 'release' then return -1 end
if #KEYS ~= (acquiring and 4 or 1) or #ARGV ~= (acquiring and 7 or 3) then return -1 end
local token = ARGV[2]
local capacity = decimal(ARGV[acquiring and 6 or 3], MAX_LEASES)
if not token_valid(token) or not capacity or capacity < 1 then return -1 end

local key = KEYS[acquiring and 4 or 1]
local kind = redis.call('TYPE', key).ok
if kind ~= 'none' and kind ~= 'zset' then return -1 end
local maximum = 0
if kind == 'zset' then
  if redis.call('ZCARD', key) > capacity then return -1 end
  local members = redis.call('ZRANGE', key, 0, capacity - 1, 'WITHSCORES')
  for i = 1, #members, 2 do
    local expiry = integer(members[i + 1], 1, MAX_INTEGER)
    if not token_valid(members[i]) or not expiry then return -1 end
    -- NX alone must not let an existing (even expired) token be reused as a new permit.
    if acquiring and members[i] == token then return -1 end
    maximum = math.max(maximum, expiry)
  end
  local retained_until = integer(redis.call('PEXPIRETIME', key), 1, MAX_INTEGER)
  if not retained_until or retained_until < maximum then return -1 end
end

if not acquiring then
  -- ZREM deletes an empty set itself. Never prune peers, change their scores/TTL, or touch rate keys.
  return redis.call('ZREM', key, token)
end

local lease = decimal(ARGV[7], MAX_INTEGER)
local time = redis.call('TIME')
local seconds = decimal(time[1], math.floor(MAX_INTEGER / 1000))
local micros = decimal(time[2], 999999)
if not lease or lease < 1 or not seconds or not micros then return -1 end
local now = seconds * 1000 + math.floor(micros / 1000)
if now > MAX_INTEGER or lease > MAX_INTEGER - now then return -1 end
local deadline = now + lease

local limits = {}
for i = 1, 3 do
  limits[i] = decimal(ARGV[i + 2], 2147483647)
  if not limits[i] then return -1 end
  for j = i + 1, 4 do
    if KEYS[i] == KEYS[j] then return -1 end
  end
  if limits[i] > 0 then
    local rate_kind = redis.call('TYPE', KEYS[i]).ok
    if rate_kind ~= 'none' and rate_kind ~= 'string' then return -1 end
    if rate_kind == 'string' then
      if redis.call('STRLEN', KEYS[i]) > 16 then return -1 end
      if not decimal(redis.call('GET', KEYS[i]), MAX_INTEGER - 1) then return -1 end
    end
  end
end

-- Preserve the existing fixed windows, rejection order and earlier-counter accounting (Backend14).
local ttl = {60000, 60000, 86400000}
for i = 1, 3 do
  if limits[i] > 0 then
    local count = redis.call('INCR', KEYS[i])
    if count == 1 then redis.call('PEXPIRE', KEYS[i], ttl[i]) end
    if count > limits[i] then redis.call('DECR', KEYS[i]); return i end
  end
end
redis.call('ZREMRANGEBYSCORE', key, '-inf', string.format('%.0f', now))
if redis.call('ZCARD', key) >= capacity then return 4 end
if redis.call('ZADD', key, 'NX', string.format('%.0f', deadline), token) ~= 1 then return -1 end
-- A shorter lease on another instance cannot shorten a still-live peer's retention.
if redis.call('PEXPIREAT', key, string.format('%.0f', math.max(maximum, deadline))) ~= 1 then return -1 end
return 0
