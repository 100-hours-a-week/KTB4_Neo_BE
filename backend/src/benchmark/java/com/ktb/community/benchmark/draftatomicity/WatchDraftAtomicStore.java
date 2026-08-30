package com.ktb.community.benchmark.draftatomicity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Component
@Profile("perf")
public class WatchDraftAtomicStore extends AbstractDraftAtomicStore {

    private final int maxAttempts;

    public WatchDraftAtomicStore(
            StringRedisTemplate redisTemplate,
            @Value("${draft.redis-ttl}") Duration ttl,
            @Value("${benchmark.draft-atomicity.watch-max-attempts}")
            int maxAttempts
    ) {
        super(redisTemplate, ttl);
        if (maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "watch-max-attempts must be positive"
            );
        }
        this.maxAttempts = maxAttempts;
    }

    @Override
    public DraftAtomicStrategy strategy() {
        return DraftAtomicStrategy.WATCH;
    }

    @Override
    public DraftAtomicResult saveIfNewer(
            DraftAtomicSnapshot request,
            DraftAtomicSnapshot fallback,
            long dirtyScore
    ) {
        long started = System.nanoTime();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            DraftAtomicResult result = redisTemplate.execute(
                    new SessionCallback<>() {
                        @Override
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        public DraftAtomicResult execute(
                                RedisOperations operations
                        ) {
                            String key = draftKey(request.draftId());
                            operations.watch(key);
                            Map<Object, Object> hash = operations.opsForHash()
                                    .entries(key);
                            DraftAtomicSnapshot stored = hash.isEmpty()
                                    ? null
                                    : fromHash(request.draftId(), hash);
                            DraftAtomicSnapshot selected = baseline(
                                    stored,
                                    fallback
                            );
                            DraftAtomicStatus status = compare(
                                    request,
                                    selected
                            );

                            boolean restore = status
                                    == DraftAtomicStatus.IDEMPOTENT
                                    && (stored == null
                                    || fallback.contentVersion()
                                    > stored.contentVersion());

                            if (status != DraftAtomicStatus.SAVED
                                    && !restore) {
                                operations.unwatch();
                                return new DraftAtomicResult(
                                        status,
                                        selected,
                                        0,
                                        0L,
                                        0L
                                );
                            }

                            DraftAtomicSnapshot written = status
                                    == DraftAtomicStatus.SAVED
                                    ? request
                                    : selected;
                            operations.multi();
                            operations.opsForHash().putAll(
                                    key,
                                    toHash(written)
                            );
                            operations.expire(key, ttl);
                            if (status == DraftAtomicStatus.SAVED) {
                                operations.opsForZSet().add(
                                        DRAFT_PENDING_SYNC_INDEX_KEY,
                                        request.draftId().toString(),
                                        dirtyScore
                                );
                            }
                            List<Object> exec = operations.exec();
                            if (exec == null || exec.isEmpty()) {
                                return null;
                            }
                            return new DraftAtomicResult(
                                    status,
                                    written,
                                    0,
                                    0L,
                                    0L
                            );
                        }
                    }
            );
            if (result != null) {
                return new DraftAtomicResult(
                        result.status(),
                        result.snapshot(),
                        attempt,
                        0L,
                        System.nanoTime() - started
                );
            }
        }
        return new DraftAtomicResult(
                DraftAtomicStatus.WATCH_RETRY_EXHAUSTED,
                fallback,
                maxAttempts,
                0L,
                System.nanoTime() - started
        );
    }

    @Override
    public boolean removeDirtyIfVersionMatches(
            Long draftId,
            long rdbContentVersion
    ) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Boolean result = redisTemplate.execute(
                    new SessionCallback<>() {
                        @Override
                        @SuppressWarnings({"rawtypes", "unchecked"})
                        public Boolean execute(RedisOperations operations) {
                            String key = draftKey(draftId);
                            operations.watch(key);
                            Object versionValue = operations.opsForHash()
                                    .get(key, FIELD_CONTENT_VERSION);
                            if (versionValue == null) {
                                operations.multi();
                                operations.opsForZSet().remove(
                                        DRAFT_PENDING_SYNC_INDEX_KEY,
                                        draftId.toString()
                                );
                            } else {
                                long redisVersion = Long.parseLong(
                                        versionValue.toString()
                                );
                                if (redisVersion > rdbContentVersion) {
                                    operations.unwatch();
                                    return false;
                                }
                                operations.multi();
                                if (redisVersion < rdbContentVersion) {
                                    operations.delete(key);
                                }
                                operations.opsForZSet().remove(
                                        DRAFT_PENDING_SYNC_INDEX_KEY,
                                        draftId.toString()
                                );
                            }
                            List<Object> exec = operations.exec();
                            return exec == null || exec.isEmpty()
                                    ? null
                                    : true;
                        }
                    }
            );
            if (result != null) {
                return result;
            }
        }
        return false;
    }
}
