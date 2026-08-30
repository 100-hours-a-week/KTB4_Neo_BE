package com.ktb.community.benchmark.draftatomicity;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

@Component
@Profile("perf")
public class LuaDraftAtomicStore extends AbstractDraftAtomicStore {

    private final DefaultRedisScript<List> autosaveScript;
    private final DefaultRedisScript<Long> removeDirtyScript;

    public LuaDraftAtomicStore(
            StringRedisTemplate redisTemplate,
            @Value("${draft.redis-ttl}") Duration ttl
    ) {
        super(redisTemplate, ttl);
        autosaveScript = new DefaultRedisScript<>();
        autosaveScript.setLocation(
                new ClassPathResource("redis/draft-autosave.lua")
        );
        autosaveScript.setResultType(List.class);

        removeDirtyScript = new DefaultRedisScript<>();
        removeDirtyScript.setLocation(
                new ClassPathResource("redis/draft-remove-dirty.lua")
        );
        removeDirtyScript.setResultType(Long.class);
    }

    @Override
    public DraftAtomicStrategy strategy() {
        return DraftAtomicStrategy.LUA;
    }

    @Override
    public DraftAtomicResult saveIfNewer(
            DraftAtomicSnapshot request,
            DraftAtomicSnapshot fallback,
            long dirtyScore
    ) {
        long started = System.nanoTime();
        List<?> values = redisTemplate.execute(
                autosaveScript,
                List.of(
                        draftKey(request.draftId()),
                        DRAFT_PENDING_SYNC_INDEX_KEY
                ),
                request.draftId().toString(),
                BENCHMARK_OWNER_ID,
                encode(request.title()),
                encode(request.postBody()),
                encode(request.postImage()),
                Long.toString(request.contentVersion()),
                BENCHMARK_OWNER_ID,
                encode(fallback.title()),
                encode(fallback.postBody()),
                encode(fallback.postImage()),
                Long.toString(fallback.contentVersion()),
                fallback.updatedAt().toString(),
                request.updatedAt().toString(),
                Long.toString(ttl.toSeconds()),
                Long.toString(dirtyScore)
        );
        if (values == null || values.size() != 6) {
            throw new IllegalStateException("Invalid Lua autosave result");
        }
        DraftAtomicStatus status = switch (values.get(0).toString()) {
            case "1" -> DraftAtomicStatus.SAVED;
            case "2" -> DraftAtomicStatus.IDEMPOTENT;
            case "3" -> DraftAtomicStatus.VERSION_CONFLICT;
            case "4" -> DraftAtomicStatus.CONTENT_CONFLICT;
            default -> throw new IllegalStateException(
                    "Unknown Lua status: " + values.get(0)
            );
        };
        DraftAtomicSnapshot snapshot = new DraftAtomicSnapshot(
                request.draftId(),
                values.get(1).toString(),
                values.get(2).toString(),
                values.get(3).toString(),
                Long.parseLong(values.get(4).toString()),
                LocalDateTime.parse(values.get(5).toString())
        );
        return new DraftAtomicResult(
                status,
                snapshot,
                1,
                0L,
                System.nanoTime() - started
        );
    }

    @Override
    public boolean removeDirtyIfVersionMatches(
            Long draftId,
            long rdbContentVersion
    ) {
        Long result = redisTemplate.execute(
                removeDirtyScript,
                List.of(
                        draftKey(draftId),
                        DRAFT_PENDING_SYNC_INDEX_KEY
                ),
                draftId.toString(),
                Long.toString(rdbContentVersion)
        );
        return result != null && result == 1L;
    }
}
