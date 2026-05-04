-- seckill-order-claim.lua
-- ARGV[1]: orderId
-- return: 1=已认领(继续处理), 0=跳过(ACK)

local orderId   = ARGV[1]
local statusKey = 'seckill-v2:order:status:' .. orderId

local currentStatus = redis.call('GET', statusKey)
if not currentStatus then
    return 0
end

-- 仅 "待处理" 或 "重试中" 可认领
if currentStatus == '待处理' or currentStatus == '重试中' then
    redis.call('SETEX', statusKey, 1800, '处理中')
    return 1
end

return 0
