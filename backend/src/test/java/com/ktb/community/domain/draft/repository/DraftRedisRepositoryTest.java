package com.ktb.community.domain.draft.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
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
    void saveInitialStoresOwnerIdInTheDraftHash() {
        DraftCache cache = new DraftCache(
                11L,
                7L,
                "title",
                "body",
                null,
                1L,
                LocalDateTime.parse("2026-08-17T18:00:00")
        );
        when(redisTemplate.expire(eq("draft:11"), eq(Duration.ofDays(3))))
                .thenReturn(true);

        draftRedisRepository.saveInitial(cache);

        ArgumentCaptor<Map<Object, Object>> valuesCaptor =
                ArgumentCaptor.forClass(Map.class);
        verify(hashOperations).putAll(
                eq("draft:11"),
                valuesCaptor.capture()
        );

        assertThat(valuesCaptor.getValue())
                .containsEntry("ownerId", "7");
    }

    @Test
    void findByIdReadsOwnerIdFromTheDraftHash() {
        when(hashOperations.entries("draft:11"))
                .thenReturn(Map.of(
                        "draftId", "11",
                        "ownerId", "7",
                        "title", "title",
                        "postBody", "body",
                        "postImage", "",
                        "contentVersion", "1",
                        "updatedAt", "2026-08-17T18:00:00"
                ));

        DraftCache cache = draftRedisRepository
                .findById(11L)
                .orElseThrow();

        assertThat(cache.ownerId()).isEqualTo(7L);
    }
}
