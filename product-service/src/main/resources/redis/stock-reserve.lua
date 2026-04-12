-- KEYS[1..N] = product available stock keys
-- KEYS[N + 1] = reservation key
-- ARGV[1] = reservation ttl seconds
-- ARGV[2] = product count
-- ARGV[3...] = productId, quantity pairs
--
-- return codes
--  1 = reserved
--  2 = already reserved
--  0 = out of stock
-- -1 = stock key missing
-- -2 = invalid argument

-- 문자열 입력값을 Lua 변수로 맵핑
local reservationKey = KEYS[#KEYS]
local ttlSeconds = tonumber(ARGV[1])
local productCount = tonumber(ARGV[2])

-- 입력값을 검증하는 블록
if productCount == nil or productCount <= 0 or (#KEYS - 1) ~= productCount then
  return -2
end

-- 주문 정보가 유효한 지, Redis에 상품이 존재하는 지, Redis 상품과 주문 상품을 뺀 값이 양수인 지 전체적인 검증을 수행하는 블록
if redis.call('EXISTS', reservationKey) == 1 then
  return 2
end

for i = 1, productCount do
  local productIdArgIndex = 3 + ((i - 1) * 2) -- 3번 인덱스부터 시작 (1, 2번은 메타 정보)
  local quantityArgIndex = productIdArgIndex + 1
  local quantity = tonumber(ARGV[quantityArgIndex])

  if quantity == nil or quantity <= 0 then
    return -2
  end

  local available = redis.call('GET', KEYS[i])
  if available == false then
    return -1
  end

  if tonumber(available) < quantity then
    return 0
  end
end

-- 재고 차감
for i = 1, productCount do
  local productIdArgIndex = 3 + ((i - 1) * 2) -- 3번 인덱스부터 시작 (1, 2번은 메타 정보)
  local quantityArgIndex = productIdArgIndex + 1
  local productId = ARGV[productIdArgIndex]
  local quantity = tonumber(ARGV[quantityArgIndex])

  redis.call('DECRBY', KEYS[i], quantity)
  redis.call('HSET', reservationKey, productId, quantity)
end

redis.call('HSET', reservationKey, '__state', 'RESERVED')

if ttlSeconds ~= nil and ttlSeconds > 0 then
  redis.call('EXPIRE', reservationKey, ttlSeconds)
end

return 1