-- KEYS[1..N] = product available stock keys
-- KEYS[N + 1] = reservation key
-- ARGV[1] = product count
-- ARGV[2...] = productId list matching KEYS[1..N]
--
-- return codes
--  1 = released
--  2 = reservation missing
-- -2 = invalid argument

local reservationKey = KEYS[#KEYS]
local productCount = tonumber(ARGV[1])

if productCount == nil or productCount <= 0 or (#KEYS - 1) ~= productCount then
  return -2
end

if redis.call('EXISTS', reservationKey) == 0 then
  return 2
end

local state = redis.call('HGET', reservationKey, '__state')
if state ~= 'RESERVED' then
  redis.call('DEL', reservationKey)
  return 2
end

for i = 1, productCount do
  local productId = ARGV[1 + i]
  local quantity = tonumber(redis.call('HGET', reservationKey, productId))

  if quantity ~= nil and quantity > 0 then
    redis.call('INCRBY', KEYS[i], quantity)
  end
end

redis.call('DEL', reservationKey)

return 1