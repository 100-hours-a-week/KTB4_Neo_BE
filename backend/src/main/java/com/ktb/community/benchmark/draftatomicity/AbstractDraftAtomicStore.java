package com.ktb.community.benchmark.draftatomicity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

abstract class AbstractDraftAtomicStore implements DraftAtomicStore {

    static final String KEY_PREFIX = "perf:draft:";
    static final String DIRTY_KEY = "perf:draft:dirty";
    static final String FIELD_DRAFT_ID = "draftId";
    static final String FIELD_TITLE = "title";
    static final String FIELD_POST_BODY = "postBody";
    static final String FIELD_POST_IMAGE = "postImage";
    static final String FIELD_CONTENT_VERSION = "contentVersion";
    static final String FIELD_UPDATED_AT = "updatedAt";

    final StringRedisTemplate redisTemplate;
    final Duration ttl;

    AbstractDraftAtomicStore(
            StringRedisTemplate redisTemplate,
            @Value("${draft.redis-ttl}") Duration ttl
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
    }

    static String draftKey(Long draftId) {
        return KEY_PREFIX + draftId;
    }

    static String encode(String value) {
        return value == null ? "" : value;
    }

    static Map<String, String> toHash(DraftAtomicSnapshot snapshot) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put(FIELD_DRAFT_ID, snapshot.draftId().toString());
        values.put(FIELD_TITLE, encode(snapshot.title()));
        values.put(FIELD_POST_BODY, encode(snapshot.postBody()));
        values.put(FIELD_POST_IMAGE, encode(snapshot.postImage()));
        values.put(FIELD_CONTENT_VERSION, Long.toString(snapshot.contentVersion()));
        values.put(FIELD_UPDATED_AT, snapshot.updatedAt().toString());
        return values;
    }

    static DraftAtomicSnapshot fromHash(Long draftId, Map<Object, Object> hash) {
        return new DraftAtomicSnapshot(
                draftId,
                value(hash, FIELD_TITLE),
                value(hash, FIELD_POST_BODY),
                value(hash, FIELD_POST_IMAGE),
                Long.parseLong(value(hash, FIELD_CONTENT_VERSION)),
                LocalDateTime.parse(value(hash, FIELD_UPDATED_AT))
        );
    }

    private static String value(Map<Object, Object> hash, String key) {
        Object value = hash.get(key);
        if (value == null) {
            throw new IllegalStateException("Missing Redis field: " + key);
        }
        return value.toString();
    }

    static DraftAtomicSnapshot baseline(
            DraftAtomicSnapshot stored,
            DraftAtomicSnapshot fallback
    ) {
        return stored == null
                || fallback.contentVersion() > stored.contentVersion()
                ? fallback
                : stored;
    }

    static DraftAtomicStatus compare(
            DraftAtomicSnapshot request,
            DraftAtomicSnapshot baseline
    ) {
        if (request.contentVersion() < baseline.contentVersion()) {
            return DraftAtomicStatus.VERSION_CONFLICT;
        }
        if (request.contentVersion() == baseline.contentVersion()) {
            return sameContent(request, baseline)
                    ? DraftAtomicStatus.IDEMPOTENT
                    : DraftAtomicStatus.CONTENT_CONFLICT;
        }
        return DraftAtomicStatus.SAVED;
    }

    static boolean sameContent(
            DraftAtomicSnapshot left,
            DraftAtomicSnapshot right
    ) {
        return encode(left.title()).equals(encode(right.title()))
                && encode(left.postBody()).equals(encode(right.postBody()))
                && encode(left.postImage()).equals(encode(right.postImage()));
    }
}
