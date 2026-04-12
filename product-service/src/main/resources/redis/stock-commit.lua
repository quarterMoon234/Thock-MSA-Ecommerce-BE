-- KEYS[1] = reservation key
--
-- return codes
--  1 = committed
--  2 = reservation missing
-- -2 = invalid argument

if #KEYS ~= 1 then
  return -2
end

local reservationKey = KEYS[1]

if reservationKey == nil or reservationKey == '' then
  return -2
end

if redis.call('EXISTS', reservationKey) == 0 then
  return 2
end

redis.call('DEL', reservationKey)
return 1