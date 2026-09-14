-- KEYS: index, then explicit (metadata, attempt-set) pairs for targets, then <=64 candidates.
-- ARGV: mode, token, identity/IP thresholds, initial/max block, failure window, lease,
-- registration cap/window, global capacity, then each candidate's pre-read index score.
-- No database-derived key is accessed: every key below came from KEYS. Preflight does no writes.
local mode = ARGV[1]
local completing = mode == 'success' or mode == 'failure'
local registering = mode == 'register'
if mode ~= 'begin' and not completing and not registering then return -1 end
local targetCount = registering and 1 or 2
local candidateCount = (#KEYS - 1) / 2 - targetCount
if candidateCount < 0 or candidateCount > 64 or candidateCount ~= math.floor(candidateCount) then return -1 end
if completing and candidateCount ~= 0 then return -1 end
local token = ARGV[2]
local thresholds = { tonumber(ARGV[3]), tonumber(ARGV[4]) }
local initialBlock = tonumber(ARGV[5])
local maxBlock = tonumber(ARGV[6])
local window = tonumber(ARGV[7])
local lease = tonumber(ARGV[8])
local registrationCap = tonumber(ARGV[9])
local registrationWindow = tonumber(ARGV[10])
local maxEntries = tonumber(ARGV[11])
local t = redis.call('TIME')
local now = tonumber(t[1]) * 1000 + math.floor(tonumber(t[2]) / 1000)
local maxInteger = 9007199254740991
local function decimal(n) return string.format('%.0f', n) end
local nowText = decimal(now)
local function integer(value)
    if value == nil or value == false then return nil end
    local n = tonumber(value)
    if not n or n < 0 or n >= maxInteger or n ~= math.floor(n) then return nil end
    return n
end
for i = 3, 11 do
    if not integer(ARGV[i]) or tonumber(ARGV[i]) < 1 then return -1 end
end
if maxEntries < 2 or maxBlock < initialBlock or lease > 300000 then return -1 end
if redis.call('TYPE', KEYS[1]).ok ~= 'none' and redis.call('TYPE', KEYS[1]).ok ~= 'zset' then return -1 end

local function readBucket(pair, expectedKind)
    local meta = KEYS[2 + (pair - 1) * 2]
    local attempts = KEYS[3 + (pair - 1) * 2]
    local metaType = redis.call('TYPE', meta).ok
    local attemptType = redis.call('TYPE', attempts).ok
    if (metaType ~= 'none' and metaType ~= 'hash') or (attemptType ~= 'none' and attemptType ~= 'zset') then return nil end
    local score = redis.call('ZSCORE', KEYS[1], meta)
    local live = redis.call('ZCOUNT', attempts, '(' .. nowText, '+inf')
    local latest = redis.call('ZREVRANGE', attempts, 0, 0, 'WITHSCORES')
    local lastDeadline = 0
    if latest[2] then
        lastDeadline = integer(latest[2])
        if not lastDeadline then return nil end
    end
    local b = {
        meta = meta, attempts = attempts, indexed = score ~= false, score = score and tonumber(score),
        exists = metaType ~= 'none', live = live, latest = lastDeadline,
        kind = expectedKind, failures = 0, lastFailure = 0, nextBlock = initialBlock, blockedUntil = 0,
        count = 0, windowStart = now, lastUpdate = now, expiresAt = 0
    }
    if not b.exists then
        if live > 0 then return nil end -- Lost metadata must never authorize an orphaned live attempt.
        return b
    end
    if not b.indexed then return nil end
    local fields = redis.call('HMGET', meta, 'kind', 'lastUpdate', 'expiresAt')
    b.kind = fields[1]
    b.lastUpdate = integer(fields[2])
    b.expiresAt = integer(fields[3])
    if not b.lastUpdate or not b.expiresAt or (expectedKind and expectedKind ~= b.kind) then return nil end
    if b.kind == 'login' then
        fields = redis.call('HMGET', meta, 'failures', 'lastFailure', 'nextBlock', 'blockedUntil')
        b.failures, b.lastFailure, b.nextBlock, b.blockedUntil = integer(fields[1]), integer(fields[2]), integer(fields[3]), integer(fields[4])
        if not b.failures or not b.lastFailure or not b.nextBlock or b.nextBlock < 1 or not b.blockedUntil then return nil end
        -- History ages since the LAST FAILURE, not admission, success, token activity or LRU updates.
        if b.lastFailure + window <= now and b.blockedUntil <= now then
            b.failures, b.lastFailure, b.nextBlock, b.blockedUntil = 0, 0, initialBlock, 0
        end
    elseif b.kind == 'registration' then
        fields = redis.call('HMGET', meta, 'count', 'windowStart')
        b.count, b.windowStart = integer(fields[1]), integer(fields[2])
        if not b.count or not b.windowStart or live > 0 then return nil end
        if b.windowStart + registrationWindow <= now then b.count, b.windowStart = 0, now end
    else
        return nil
    end
    return b
end

local targets = {}
local excluded = {}
for i = 1, targetCount do
    targets[i] = readBucket(i, registering and 'registration' or 'login')
    if not targets[i] then return -1 end
    excluded[targets[i].meta] = true
end

if completing then
    -- Fence BOTH dimensions before consuming either token or changing any completed history.
    local first = integer(redis.call('ZSCORE', targets[1].attempts, token))
    local second = integer(redis.call('ZSCORE', targets[2].attempts, token))
    if not targets[1].exists or not targets[2].exists or not first or first ~= second or first <= now then return 0 end
else
    local retry = 0
    for i, b in ipairs(targets) do
        if not registering and redis.call('ZSCORE', b.attempts, token) then return -1 end
        if registering then
            if b.count >= registrationCap then retry = math.max(retry, b.windowStart + registrationWindow - now) end
        elseif b.blockedUntil > now then
            retry = math.max(retry, b.blockedUntil - now)
        elseif b.failures + b.live >= thresholds[i] then
            local earliest = redis.call('ZRANGEBYSCORE', b.attempts, '(' .. nowText, '+inf', 'WITHSCORES', 'LIMIT', 0, 1)
            retry = math.max(retry, earliest[2] and tonumber(earliest[2]) - now or 5000)
        end
    end
    if retry > 0 then return retry end
end

local victims = {}
if not completing then
    local missing = 0
    for _, b in ipairs(targets) do if not b.indexed then missing = missing + 1 end end
    local needed = redis.call('ZCARD', KEYS[1]) + missing - maxEntries
    -- The bounded pre-read is not eviction authority. Revalidate membership, exact score and protection.
    local candidates = {}
    for i = 1, candidateCount do
        local b = readBucket(targetCount + i)
        if not b then return -1 end
        local expectedScore = integer(ARGV[11 + i])
        if not expectedScore then return -1 end
        local protected = b.live > 0 or b.blockedUntil > now or
            (b.kind == 'registration' and b.count >= registrationCap and b.windowStart + registrationWindow > now)
        if not excluded[b.meta] and b.indexed and b.score == expectedScore and not protected then
            candidates[#candidates + 1] = b
            excluded[b.meta] = true
        end
    end
    -- Pre-read ordering is score, then lexical metadata key. Expired candidates take precedence.
    for _, deadFirst in ipairs({ true, false }) do
        for _, b in ipairs(candidates) do
            if (not b.exists or b.expiresAt <= now) == deadFirst and #victims < needed then victims[#victims + 1] = b end
        end
    end
    if #victims < needed then return 5000 end
end

-- Compute and validate all outcome fields/deadlines before the first write (Lua errors do not roll back).
for i, b in ipairs(targets) do
    b.lastUpdate = now
    if registering then
        b.count = b.count + 1
        b.expiresAt = b.windowStart + registrationWindow
    else
        if mode == 'begin' then b.latest = math.max(b.latest, now + lease) end
        if mode == 'success' and i == 1 then b.failures, b.lastFailure, b.nextBlock, b.blockedUntil = 0, 0, initialBlock, 0 end
        if mode == 'failure' then
            b.failures = b.failures + 1
            b.lastFailure = now
            if b.failures >= thresholds[i] then
                b.blockedUntil = now + b.nextBlock
                b.failures = 0
                b.nextBlock = math.min(b.nextBlock * 2, maxBlock)
            end
        end
        b.expiresAt = math.max(now + window, b.lastFailure + window, b.blockedUntil, b.latest)
    end
    if not integer(b.expiresAt) or not integer(b.latest) or not integer(b.blockedUntil) or not integer(b.nextBlock) then return -1 end
end

for _, b in ipairs(victims) do
    redis.call('DEL', b.meta, b.attempts)
    redis.call('ZREM', KEYS[1], b.meta)
end
for _, b in ipairs(targets) do
    redis.call('ZREMRANGEBYSCORE', b.attempts, '-inf', nowText)
    if registering then
        redis.call('HSET', b.meta, 'kind', b.kind, 'count', b.count, 'windowStart', decimal(b.windowStart),
            'lastUpdate', nowText, 'expiresAt', decimal(b.expiresAt))
    else
        if completing then redis.call('ZREM', b.attempts, token) else redis.call('ZADD', b.attempts, decimal(now + lease), token) end
        redis.call('HSET', b.meta, 'kind', b.kind, 'failures', b.failures, 'lastFailure', decimal(b.lastFailure),
            'nextBlock', decimal(b.nextBlock), 'blockedUntil', decimal(b.blockedUntil), 'lastUpdate', nowText, 'expiresAt', decimal(b.expiresAt))
        if b.latest > now then redis.call('PEXPIREAT', b.attempts, decimal(b.latest)) end
    end
    redis.call('PEXPIREAT', b.meta, decimal(b.expiresAt))
    redis.call('ZADD', KEYS[1], nowText, b.meta)
    -- Never shorten the global index horizon when a different bucket has shorter retention.
    if redis.call('PEXPIRETIME', KEYS[1]) < b.expiresAt then redis.call('PEXPIREAT', KEYS[1], decimal(b.expiresAt)) end
end
return completing and 1 or 0
