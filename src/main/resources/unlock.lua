-- KEYS[1] 是锁的 key
-- ARGV[1] 是预期的 value（uuid + 线程标识）

if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end


