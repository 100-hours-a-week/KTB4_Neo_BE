-- 초기 Draft hash 저장과 TTL 설정을 하나의 원자적 동작으로 처리

local draftKey = KEYS[1]

local draftId = ARGV[1]
local ownerId = ARGV[2]
local title = ARGV[3]
local postBody = ARGV[4]
local postImage = ARGV[5]
local contentVersion = ARGV[6]
local updatedAt = ARGV[7]
local ttlSeconds = tonumber(ARGV[8])

redis.call(
    "HSET",
    draftKey,
    "draftId", draftId,
    "ownerId", ownerId,
    "title", title,
    "postBody", postBody,
    "postImage", postImage,
    "contentVersion", contentVersion,
    "updatedAt", updatedAt
)

redis.call(
    "EXPIRE",
    draftKey,
    ttlSeconds
)

return 1
