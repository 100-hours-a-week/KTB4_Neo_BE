package com.ktb.community.benchmark.draftatomicity;

import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Component
@Profile("perf")
public class RLockDraftAtomicStore extends AbstractDraftAtomicStore {

    private static final String LOCK_PREFIX = "perf:lock:draft:";

    private final RedissonClient redissonClient;
    private final Duration lockWait;

    public RLockDraftAtomicStore(
            StringRedisTemplate redisTemplate,
            RedissonClient redissonClient,
            @Value("${draft.redis-ttl}") Duration ttl,
            @Value("${benchmark.draft-atomicity.lock-wait}")
            Duration lockWait
    ) {
        super(redisTemplate, ttl);
        this.redissonClient = redissonClient;
        this.lockWait = lockWait;
    }

    @Override
    public DraftAtomicStrategy strategy() {
        return DraftAtomicStrategy.RLOCK;
    }

    @Override
    public DraftAtomicResult saveIfNewer(
            DraftAtomicSnapshot request,
            DraftAtomicSnapshot fallback,
            long dirtyScore
    ) {
        long operationStarted = System.nanoTime();
        RLock lock = redissonClient.getLock(
                LOCK_PREFIX + request.draftId()
        );
        long waitStarted = System.nanoTime();
        boolean acquired;
        try {
            acquired = lock.tryLock(
                    lockWait.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        long waited = System.nanoTime() - waitStarted;
        if (!acquired) {
            return new DraftAtomicResult(
                    DraftAtomicStatus.LOCK_TIMEOUT,
                    fallback,
                    1,
                    waited,
                    System.nanoTime() - operationStarted
            );
        }

        try {
            Map<Object, Object> hash = redisTemplate.opsForHash()
                    .entries(draftKey(request.draftId()));
            DraftAtomicSnapshot stored = hash.isEmpty()
                    ? null
                    : fromHash(request.draftId(), hash);
            DraftAtomicSnapshot baseline = baseline(stored, fallback);
            DraftAtomicStatus status = compare(request, baseline);

            if (status == DraftAtomicStatus.SAVED) {
                redisTemplate.opsForHash().putAll(
                        draftKey(request.draftId()),
                        toHash(request)
                );
                redisTemplate.expire(draftKey(request.draftId()), ttl);
                redisTemplate.opsForZSet().add(
                        DRAFT_PENDING_SYNC_INDEX_KEY,
                        request.draftId().toString(),
                        dirtyScore
                );
                baseline = request;
            } else if (status == DraftAtomicStatus.IDEMPOTENT
                    && (stored == null
                    || fallback.contentVersion() > stored.contentVersion())) {
                redisTemplate.opsForHash().putAll(
                        draftKey(request.draftId()),
                        toHash(baseline)
                );
                redisTemplate.expire(draftKey(request.draftId()), ttl);
            }

            return new DraftAtomicResult(
                    status,
                    baseline,
                    1,
                    waited,
                    System.nanoTime() - operationStarted
            );
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public boolean removeDirtyIfVersionMatches(
            Long draftId,
            long rdbContentVersion
    ) {
        RLock lock = redissonClient.getLock(LOCK_PREFIX + draftId);
        boolean acquired;
        try {
            acquired = lock.tryLock(
                    lockWait.toMillis(),
                    TimeUnit.MILLISECONDS
            );
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        if (!acquired) {
            return false;
        }
        try {
            String key = draftKey(draftId);
            Object versionValue = redisTemplate.opsForHash()
                    .get(key, FIELD_CONTENT_VERSION);
            if (versionValue == null) {
                redisTemplate.opsForZSet().remove(
                        DRAFT_PENDING_SYNC_INDEX_KEY,
                        draftId.toString()
                );
                return true;
            }
            long redisVersion = Long.parseLong(versionValue.toString());
            if (redisVersion == rdbContentVersion) {
                redisTemplate.opsForZSet().remove(
                        DRAFT_PENDING_SYNC_INDEX_KEY,
                        draftId.toString()
                );
                return true;
            }
            if (redisVersion < rdbContentVersion) {
                redisTemplate.delete(key);
                redisTemplate.opsForZSet().remove(
                        DRAFT_PENDING_SYNC_INDEX_KEY,
                        draftId.toString()
                );
                return true;
            }
            return false;
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
