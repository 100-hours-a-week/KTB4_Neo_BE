-- 원자적 자동 저장을 위한 Lua Script
-- Redis에 소유자 정보가 있는 일반 경로는 RDB fallback 없이 처리하고,
-- 캐시가 없거나 ownerId가 없는 경우에만 status 5로 RDB fallback을 요청한다.

local draftKey = KEYS[1]
local dirtyKey = KEYS[2]

local draftId = ARGV[1]

local requestOwnerId = ARGV[2]
local requestTitle = ARGV[3]
local requestPostBody = ARGV[4]
local requestPostImage = ARGV[5]
local requestContentVersion = tonumber(ARGV[6])

local fallbackOwnerId = ARGV[7]
local hasFallback = fallbackOwnerId ~= nil and fallbackOwnerId ~= ""
local fallbackTitle = ARGV[8]
local fallbackPostBody = ARGV[9]
local fallbackPostImage = ARGV[10]
local fallbackContentVersion = hasFallback and tonumber(ARGV[11]) or nil
local fallbackUpdatedAt = ARGV[12]

local requestUpdatedAt = ARGV[13]
local ttlSeconds = tonumber(ARGV[14])
local dirtyScore = tonumber(ARGV[15])

local FIELD_DRAFT_ID = "draftId"
local FIELD_OWNER_ID = "ownerId"
local FIELD_TITLE = "title"
local FIELD_POST_BODY = "postBody"
local FIELD_POST_IMAGE = "postImage"
local FIELD_CONTENT_VERSION = "contentVersion"
local FIELD_UPDATED_AT = "updatedAt"

local function result(
    status,
    title,
    postBody,
    postImage,
    contentVersion,
    updatedAt
)
    return {
        tostring(status),
        title,
        postBody,
        postImage,
        tostring(contentVersion),
        updatedAt
    }
end

local function writeCache(
    ownerId,
    title,
    postBody,
    postImage,
    contentVersion,
    updatedAt
)
    redis.call(
        "HSET",
        draftKey,
        FIELD_DRAFT_ID,
        draftId,
        FIELD_OWNER_ID,
        ownerId,
        FIELD_TITLE,
        title,
        FIELD_POST_BODY,
        postBody,
        FIELD_POST_IMAGE,
        postImage,
        FIELD_CONTENT_VERSION,
        tostring(contentVersion),
        FIELD_UPDATED_AT,
        updatedAt
    )
end

local draftExists =
    redis.call("EXISTS", draftKey) == 1

local storedOwnerId
local storedTitle
local storedPostBody
local storedPostImage
local storedContentVersion
local storedUpdatedAt
local usingFallback = false
local ownerNeedsRepair = false

if draftExists then
    storedOwnerId = redis.call(
        "HGET",
        draftKey,
        FIELD_OWNER_ID
    )

    -- ownerId가 없는 기존 Hash는 RDB에서 소유권을 확인해야 한다.
    if not storedOwnerId then
        if not hasFallback then
            return result(
                5,
                requestTitle,
                requestPostBody,
                requestPostImage,
                requestContentVersion,
                requestUpdatedAt
            )
        end

        if fallbackOwnerId ~= requestOwnerId then
            return result(
                6,
                requestTitle,
                requestPostBody,
                requestPostImage,
                requestContentVersion,
                requestUpdatedAt
            )
        end

        ownerNeedsRepair = true
    elseif storedOwnerId ~= requestOwnerId then
        -- 다른 사용자의 Draft임을 알리지 않기 위해 실제 내용은 반환하지 않는다.
        return result(
            6,
            requestTitle,
            requestPostBody,
            requestPostImage,
            requestContentVersion,
            requestUpdatedAt
        )
    end
else
    if not hasFallback then
        return result(
            5,
            requestTitle,
            requestPostBody,
            requestPostImage,
            requestContentVersion,
            requestUpdatedAt
        )
    end

    if fallbackOwnerId ~= requestOwnerId then
        return result(
            6,
            requestTitle,
            requestPostBody,
            requestPostImage,
            requestContentVersion,
            requestUpdatedAt
        )
    end
end

if draftExists then
    storedTitle = redis.call(
        "HGET",
        draftKey,
        FIELD_TITLE
    )

    storedPostBody = redis.call(
        "HGET",
        draftKey,
        FIELD_POST_BODY
    )

    storedPostImage = redis.call(
        "HGET",
        draftKey,
        FIELD_POST_IMAGE
    )

    storedContentVersion = tonumber(
        redis.call(
            "HGET",
            draftKey,
            FIELD_CONTENT_VERSION
        )
    )

    storedUpdatedAt = redis.call(
        "HGET",
        draftKey,
        FIELD_UPDATED_AT
    )

    -- Redis가 존재하더라도 RDB fallback보다 버전이 낮으면
    -- RDB 데이터를 비교 기준으로 사용한다.
    if hasFallback
            and fallbackContentVersion > storedContentVersion then
        storedTitle = fallbackTitle
        storedPostBody = fallbackPostBody
        storedPostImage = fallbackPostImage
        storedContentVersion = fallbackContentVersion
        storedUpdatedAt = fallbackUpdatedAt
        usingFallback = true
    end
else
    storedTitle = fallbackTitle
    storedPostBody = fallbackPostBody
    storedPostImage = fallbackPostImage
    storedContentVersion = fallbackContentVersion
    storedUpdatedAt = fallbackUpdatedAt
end

local function repairOwner()
    if ownerNeedsRepair then
        redis.call(
            "HSET",
            draftKey,
            FIELD_OWNER_ID,
            requestOwnerId
        )

        redis.call(
            "EXPIRE",
            draftKey,
            ttlSeconds
        )
    end
end

if requestContentVersion
        < storedContentVersion then
    repairOwner()

    return result(
        3,  -- 요청 Draft가 저장된 Draft보다 낮은 버전인 경우, 내용은 수정하지 않는다.
        storedTitle,
        storedPostBody,
        storedPostImage,
        storedContentVersion,
        storedUpdatedAt
    )
end

-- 저장된 Draft와 요청 Draft 버전 비교
if requestContentVersion
        == storedContentVersion then

    local sameTitle =
        requestTitle == storedTitle

    local samePostBody =
        requestPostBody == storedPostBody

    local samePostImage =
        requestPostImage == storedPostImage

    -- 버전이 같고, Draft 내용까지 같은 경우
    if sameTitle
            and samePostBody
            and samePostImage then

        if not draftExists
                or usingFallback
                or ownerNeedsRepair then
            writeCache(
                requestOwnerId,
                storedTitle,
                storedPostBody,
                storedPostImage,
                storedContentVersion,
                storedUpdatedAt
            )
        end

        -- 같은 내용을 재전송해도 활성 Draft의 sliding TTL을 연장한다.
        redis.call(
            "EXPIRE",
            draftKey,
            ttlSeconds
        )

        return result(
            2,  -- 버전과 내용이 같음 -> 멱등
            storedTitle,
            storedPostBody,
            storedPostImage,
            storedContentVersion,
            storedUpdatedAt
        )
    end

    repairOwner()

    return result(
        4,  -- 버전이 같은데 내용이 다름 -> CONTENT CONFLICT
        storedTitle,
        storedPostBody,
        storedPostImage,
        storedContentVersion,
        storedUpdatedAt
    )
end

-- 앞의 두 조건을 통과한 경우, 요청 버전이 저장 버전보다 높은 경우
writeCache(
    requestOwnerId,
    requestTitle,
    requestPostBody,
    requestPostImage,
    requestContentVersion,
    requestUpdatedAt
)

redis.call(
    "EXPIRE",
    draftKey,
    ttlSeconds
)

redis.call(
    "ZADD",
    dirtyKey,
    dirtyScore,
    draftId
)

return result(
    1,  -- 성공적으로 원자적 자동 저장 완료
    requestTitle,
    requestPostBody,
    requestPostImage,
    requestContentVersion,
    requestUpdatedAt
)
