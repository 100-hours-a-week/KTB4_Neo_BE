package com.ktb.community.domain.draft.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"unchecked", "rawtypes"})
class DraftRedisRepositoryTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    private DraftRedisRepository draftRedisRepository;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);
        draftRedisRepository = new DraftRedisRepository(
                redisTemplate,
                Duration.ofDays(3)
        );
    }

    @Test
    void saveInitialStoresDraftFieldsAndTtlInOneScript() {
        LocalDateTime updatedAt = LocalDateTime.parse("2026-08-17T18:00:00");
        DraftCache cache = new DraftCache(
                11L,
                7L,
                "title",
                "body",
                null,
                1L,
                updatedAt
        );
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(java.util.List.of("draft:11")),
                any(Object[].class)
        )).thenReturn(1L);

        draftRedisRepository.saveInitial(cache);

        ArgumentCaptor<Object[]> argumentsCaptor =
                ArgumentCaptor.forClass(Object[].class);
        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(java.util.List.of("draft:11")),
                argumentsCaptor.capture()
        );

        assertThat(argumentsCaptor.getValue())
                .containsExactly(
                        "11",
                        "7",
                        "title",
                        "body",
                        "",
                        "1",
                        Long.toString(toEpochMillis(updatedAt)),
                        "259200"
                );
    }

    @Test
    void saveInitialUsesOneRedisScriptForHashAndTtl() {
        DraftCache cache = new DraftCache(
                11L,
                7L,
                "title",
                "body",
                null,
                1L,
                LocalDateTime.parse("2026-08-17T18:00:00")
        );
        when(redisTemplate.execute(
                any(DefaultRedisScript.class),
                eq(java.util.List.of("draft:11")),
                any(Object[].class)
        )).thenReturn(1L);

        draftRedisRepository.saveInitial(cache);

        verify(redisTemplate).execute(
                any(DefaultRedisScript.class),
                eq(java.util.List.of("draft:11")),
                any(Object[].class)
        );
        verify(hashOperations, never()).putAll(
                eq("draft:11"),
                any(Map.class)
        );
        verify(redisTemplate, never()).expire(
                eq("draft:11"),
                eq(Duration.ofDays(3))
        );
    }

    @Test
    void findByIdReadsOwnerIdFromTheDraftHash() {
        LocalDateTime updatedAt = LocalDateTime.parse("2026-08-17T18:00:00");
        when(hashOperations.entries("draft:11"))
                .thenReturn(Map.of(
                        "draftId", "11",
                        "ownerId", "7",
                        "title", "title",
                        "postBody", "body",
                        "postImage", "",
                        "contentVersion", "1",
                        "updatedAt", Long.toString(toEpochMillis(updatedAt))
                ));

        DraftCache cache = draftRedisRepository
                .findById(11L)
                .orElseThrow();

        assertThat(cache.ownerId()).isEqualTo(7L);
        assertThat(cache.updatedAt()).isEqualTo(updatedAt);
    }

    private long toEpochMillis(LocalDateTime value) {
        return value.atZone(ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli();
    }
}
