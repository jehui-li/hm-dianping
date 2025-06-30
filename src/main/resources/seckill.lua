-- 参数说明
-- KEYS[1]：库存 Key（如 stock:1001）
-- KEYS[2]：订单 Key（如 order:1001）
-- ARGV[1]：用户 ID

-- 1. 判断库存是否大于 0
local stock = tonumber(redis.call("GET", KEYS[1]))
if not stock or stock <= 0 then
    return 0  -- 库存不足
end

-- 2. 判断是否已下过订单
local isOrdered = redis.call("SISMEMBER", KEYS[2], ARGV[1])
if isOrdered == 1 then
    return 1  -- 已下单
end

-- 3. 扣减库存
redis.call("DECR", KEYS[1])

-- 4. 添加用户到订单集合
redis.call("SADD", KEYS[2], ARGV[1])

return 2  -- 下单成功
