-- 1.参数列表
-- 1.1 优惠券id
local voucherId = ARGV[1]
-- 1.2 用户id
local userId = ARGV[2]

-- 2.1 库存key
local stockKey = 'seckill:stock:' .. voucherId
-- 2.2 订单key
local orderKey = 'seckill:order:' .. voucherId

-- 3.1 判断库存是否充足
local stock = tonumber(redis.call('get', stockKey))
if (not stock) or (stock <= 0) then
    -- 库存不足，返回1
    return 1
end

-- -- 3.2 判断用户是否已经下单
-- if (tonumber(redis.call('SISMEMBER', orderKey, userId)) == 1) then
--     -- 重复下单，返回2
--     return 2
-- end

-- 3.3 扣库存
redis.call('INCRBY', stockKey, -1)

-- -- 3.4 下单,预减库存
-- redis.call('SADD', orderKey, userId)

return 0