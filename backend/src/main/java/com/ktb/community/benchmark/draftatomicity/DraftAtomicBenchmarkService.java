package com.ktb.community.benchmark.draftatomicity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@Profile("perf")
public class DraftAtomicBenchmarkService {

    private final StringRedisTemplate redisTemplate;
    private final Duration ttl;
    private final DraftAtomicMetrics metrics;
    private final Map<DraftAtomicStrategy, DraftAtomicStore> stores =
            new EnumMap<>(DraftAtomicStrategy.class);

    public DraftAtomicBenchmarkService(
            StringRedisTemplate redisTemplate,
            @Value("${draft.redis-ttl}") Duration ttl,
            DraftAtomicMetrics metrics,
            List<DraftAtomicStore> stores
    ) {
        this.redisTemplate = redisTemplate;
        this.ttl = ttl;
        this.metrics = metrics;
        stores.forEach(store -> this.stores.put(
                store.strategy(),
                store
        ));
    }

    public DraftAtomicResult autosave(
            DraftAtomicStrategy strategy,
            Long draftId,
            DraftAtomicBenchmarkRequest request
    ) {
        DraftAtomicResult result = store(strategy).saveIfNewer(
                request.requestSnapshot(draftId),
                request.fallbackSnapshot(draftId),
                request.dirtyScore() == null
                        ? System.currentTimeMillis()
                        : request.dirtyScore()
        );
        metrics.record(strategy, result);
        return result;
    }

    public Map<String, Object> removeDirty(
            DraftAtomicStrategy strategy,
            Long draftId,
            long rdbContentVersion
    ) {
        long started = System.nanoTime();
        boolean removed = store(strategy).removeDirtyIfVersionMatches(
                draftId,
                rdbContentVersion
        );
        return Map.of(
                "strategy", strategy,
                "removed", removed,
                "operationNanos", System.nanoTime() - started
        );
    }

    public Map<String, Object> removeDirtyCycle(
            DraftAtomicStrategy strategy,
            Long draftId,
            long rdbContentVersion
    ) {
        long started = System.nanoTime();
        boolean removed = store(strategy).removeDirtyIfVersionMatches(
                draftId,
                rdbContentVersion
        );
        long operationNanos = System.nanoTime() - started;
        metrics.recordDirty(strategy, removed, operationNanos);
        redisTemplate.opsForZSet().add(
                AbstractDraftAtomicStore.DIRTY_KEY,
                draftId.toString(),
                System.currentTimeMillis()
        );
        return Map.of(
                "strategy", strategy,
                "removed", removed,
                "operationNanos", operationNanos
        );
    }

    public void initialize(DraftAtomicInitializeRequest request) {
        String key = AbstractDraftAtomicStore.draftKey(request.draftId());
        redisTemplate.opsForHash().putAll(
                key,
                AbstractDraftAtomicStore.toHash(request.snapshot())
        );
        redisTemplate.expire(key, ttl);
        if (request.dirty()) {
            redisTemplate.opsForZSet().add(
                    AbstractDraftAtomicStore.DIRTY_KEY,
                    request.draftId().toString(),
                    System.currentTimeMillis()
            );
        } else {
            redisTemplate.opsForZSet().remove(
                    AbstractDraftAtomicStore.DIRTY_KEY,
                    request.draftId().toString()
            );
        }
    }

    public DraftAtomicState state(Long draftId) {
        String key = AbstractDraftAtomicStore.draftKey(draftId);
        Map<Object, Object> hash = redisTemplate.opsForHash().entries(key);
        DraftAtomicSnapshot snapshot = hash.isEmpty()
                ? null
                : AbstractDraftAtomicStore.fromHash(draftId, hash);
        return new DraftAtomicState(
                draftId,
                !hash.isEmpty(),
                snapshot,
                redisTemplate.getExpire(key),
                Boolean.TRUE.equals(
                        redisTemplate.opsForZSet().score(
                                AbstractDraftAtomicStore.DIRTY_KEY,
                                draftId.toString()
                        ) != null
                ),
                redisTemplate.opsForZSet().score(
                        AbstractDraftAtomicStore.DIRTY_KEY,
                        draftId.toString()
                )
        );
    }

    public void createOrphanDirty(Long draftId) {
        redisTemplate.delete(AbstractDraftAtomicStore.draftKey(draftId));
        redisTemplate.opsForZSet().add(
                AbstractDraftAtomicStore.DIRTY_KEY,
                draftId.toString(),
                System.currentTimeMillis()
        );
    }

    public void reset() {
        Set<String> keys = redisTemplate.keys("perf:*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
        metrics.reset();
    }

    public Map<String, Object> metrics() {
        return metrics.snapshot();
    }

    private DraftAtomicStore store(DraftAtomicStrategy strategy) {
        DraftAtomicStore store = stores.get(strategy);
        if (store == null) {
            throw new IllegalArgumentException(
                    "Unsupported strategy: " + strategy
            );
        }
        return store;
    }
}
