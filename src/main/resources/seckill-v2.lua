-- seckill-v2.lua
-- ARGV[1]: voucherId
-- ARGV[2]: userId
-- ARGV[3]: orderId (由 Java 端 SnowflakeIdWorker 预生成)
-- ARGV[4]: maxOrders (每人每券最大购买数量)
-- return: 0=success, 1=库存不足, 2=已达购买上限

local voucherId  = ARGV[1]
local userId     = ARGV[2]
local orderId    = ARGV[3]
local maxOrders  = tonumber(ARGV[4])

local stockKey   = 'seckill-v2:stock:' .. voucherId
local userKey    = 'seckill-v2:user:' .. voucherId
local statusPrefix = 'seckill-v2:order:status:'
local retryPrefix  = 'seckill-v2:order:retry:'

-- 1. 判断库存是否充足
local stock = tonumber(redis.call('GET', stockKey))
if (not stock) or (stock <= 0) then
    return 1
end

-- 2. 判断用户是否已达购买上限（Hash: HGET 查已购数量）
local count = tonumber(redis.call('HGET', userKey, userId)) or 0
if count >= maxOrders then
    return 2
end

-- 3. 预扣库存
redis.call('DECRBY', stockKey, 1)

-- 4. 标记用户已下单（Hash 计数+1）
redis.call('HINCRBY', userKey, userId, 1)

-- 5. 写入订单状态占位 待处理，TTL 30分钟
redis.call('SETEX', statusPrefix .. orderId, 1800, '待处理')

-- 6. 初始化重试计数器
redis.call('SETEX', retryPrefix .. orderId, 1800, '0')

return 0
