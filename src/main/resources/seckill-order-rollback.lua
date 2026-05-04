-- seckill-order-rollback.lua
-- ARGV[1]: orderId
-- ARGV[2]: voucherId
-- ARGV[3]: userId
-- ARGV[4]: existsInDB ("1" or "0")
-- return: 1=已回退, 2=已标记成功, 0=无需处理

local orderId    = ARGV[1]
local voucherId  = ARGV[2]
local userId     = ARGV[3]
local existsInDB = ARGV[4]

local statusKey = 'seckill-v2:order:status:' .. orderId
local stockKey  = 'seckill-v2:stock:' .. voucherId
local userKey   = 'seckill-v2:user:' .. voucherId
local retryKey  = 'seckill-v2:order:retry:' .. orderId

local currentStatus = redis.call('GET', statusKey)
if not currentStatus then
    return 0
end

-- Case A: DB 中已有订单 → 标记已完成
if existsInDB == '1' then
    redis.call('SETEX', statusKey, 1800, '已完成')
    redis.call('DEL', retryKey)
    return 2
end

-- Case B: DB 中没有订单，且状态为 待处理 或 已失败 → 回退
if existsInDB == '0' and (currentStatus == '待处理' or currentStatus == '已失败') then
    redis.call('INCRBY', stockKey, 1)
    local count = tonumber(redis.call('HGET', userKey, userId)) or 0
    if count <= 1 then
        redis.call('HDEL', userKey, userId)
    else
        redis.call('HINCRBY', userKey, userId, -1)
    end
    redis.call('DEL', statusKey)
    redis.call('DEL', retryKey)
    return 1
end

return 0
