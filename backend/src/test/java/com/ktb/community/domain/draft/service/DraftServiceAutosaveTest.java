package com.ktb.community.domain.draft.service;

import com.ktb.community.domain.draft.dto.DraftAutosaveResponseDto;
import com.ktb.community.domain.draft.dto.DraftPublishRequestDto;
import com.ktb.community.domain.draft.dto.DraftPublishResponseDto;
import com.ktb.community.domain.draft.dto.DraftRequestDto;
import com.ktb.community.domain.draft.repository.DraftCache;
import com.ktb.community.domain.draft.repository.DraftRedisRepository;
import com.ktb.community.domain.draft.repository.DraftRedisSaveResult;
import com.ktb.community.domain.draft.repository.DraftRedisSaveStatus;
import com.ktb.community.domain.draft.repository.DraftRepository;
import com.ktb.community.domain.draft.entity.Draft;
import com.ktb.community.domain.draft.entity.DraftStatus;
import com.ktb.community.domain.draft.dto.DraftResponseDto;
import com.ktb.community.domain.post.repository.PostRepository;
import com.ktb.community.domain.post.entity.Post;
import com.ktb.community.domain.user.entity.User;
import com.ktb.community.domain.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;

@ExtendWith(MockitoExtension.class)
class DraftServiceAutosaveTest {

    @Mock
    private DraftRepository draftRepository;

    @Mock
    private DraftRedisRepository draftRedisRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private PostRepository postRepository;

    private DraftService draftService;

    @BeforeEach
    void setUp() {
        draftService = new DraftService(
                draftRepository,
                draftRedisRepository,
                userRepository,
                postRepository
        );
    }

    @Test
    void autosaveDoesNotReadRdbWhenRedisCacheCanAuthorizeTheRequest() {
        Long userId = 7L;
        Long draftId = 11L;
        DraftRequestDto request = new DraftRequestDto(
                "title",
                "body",
                null,
                2L
        );
        DraftCache cachedDraft = mock(DraftCache.class);
        DraftRedisSaveResult saveResult = new DraftRedisSaveResult(
                DraftRedisSaveStatus.IDEMPOTENT,
                cachedDraft
        );

        when(cachedDraft.draftId()).thenReturn(draftId);
        when(cachedDraft.contentVersion()).thenReturn(2L);
        when(cachedDraft.updatedAt()).thenReturn(LocalDateTime.now());
        when(draftRedisRepository.saveIfNewer(any(DraftCache.class)))
                .thenReturn(saveResult);

        DraftAutosaveResponseDto response = draftService.autosaveDraft(
                userId,
                draftId,
                request
        );

        assertThat(response.getDraftId()).isEqualTo(draftId);
        assertThat(response.getContentVersion()).isEqualTo(2L);
        verify(draftRedisRepository).saveIfNewer(any(DraftCache.class));
        verify(draftRepository, never()).findByDraftIdAndUserId(draftId, userId);
    }

    @Test
    void autosaveReadsRdbOnceAndRetriesWithFallbackWhenRedisKeyIsMissing() {
        Long userId = 7L;
        Long draftId = 11L;
        DraftRequestDto request = new DraftRequestDto(
                "title",
                "body",
                null,
                2L
        );
        DraftCache requestCache = mock(DraftCache.class);
        Draft draft = mock(Draft.class);
        DraftRedisSaveResult fallbackRequired = new DraftRedisSaveResult(
                DraftRedisSaveStatus.FALLBACK_REQUIRED,
                requestCache
        );
        DraftRedisSaveResult saved = new DraftRedisSaveResult(
                DraftRedisSaveStatus.SAVED,
                requestCache
        );

        when(requestCache.draftId()).thenReturn(draftId);
        when(requestCache.contentVersion()).thenReturn(2L);
        when(requestCache.updatedAt()).thenReturn(LocalDateTime.now());
        when(draft.getDraftId()).thenReturn(draftId);
        when(draft.getActiveOwnerId()).thenReturn(userId);
        when(draft.getTitle()).thenReturn("old title");
        when(draft.getPostBody()).thenReturn("old body");
        when(draft.getPostImage()).thenReturn(null);
        when(draft.getContentVersion()).thenReturn(1L);
        when(draft.getRdbSavedAt()).thenReturn(LocalDateTime.now());
        when(draft.isActive()).thenReturn(true);
        when(draftRedisRepository.saveIfNewer(any(DraftCache.class)))
                .thenReturn(fallbackRequired);
        when(draftRedisRepository.saveIfNewer(any(DraftCache.class), any(DraftCache.class)))
                .thenReturn(saved);
        when(draftRepository.findByDraftIdAndUserId(draftId, userId))
                .thenReturn(Optional.of(draft));

        DraftAutosaveResponseDto response = draftService.autosaveDraft(
                userId,
                draftId,
                request
        );

        assertThat(response.getDraftId()).isEqualTo(draftId);
        verify(draftRepository).findByDraftIdAndUserId(draftId, userId);
        verify(draftRedisRepository).saveIfNewer(any(DraftCache.class));
        verify(draftRedisRepository).saveIfNewer(
                any(DraftCache.class),
                any(DraftCache.class)
        );
    }

    @Test
    void activeDraftKeepsNewerLegacyRedisContentWhileAddingOwnerId() {
        Long userId = 7L;
        Long draftId = 11L;
        LocalDateTime rdbSavedAt = LocalDateTime.parse("2026-08-17T17:00:00");
        LocalDateTime redisUpdatedAt = LocalDateTime.parse("2026-08-17T18:00:00");
        Draft draft = mock(Draft.class);
        DraftCache legacyRedisCache = new DraftCache(
                draftId,
                null,
                "redis title",
                "redis body",
                null,
                2L,
                redisUpdatedAt
        );

        when(draft.getDraftId()).thenReturn(draftId);
        when(draft.getActiveOwnerId()).thenReturn(userId);
        when(draft.getTitle()).thenReturn("rdb title");
        when(draft.getPostBody()).thenReturn("rdb body");
        when(draft.getPostImage()).thenReturn(null);
        when(draft.getContentVersion()).thenReturn(1L);
        when(draft.getRdbSavedAt()).thenReturn(rdbSavedAt);
        when(draft.getStatus()).thenReturn(DraftStatus.ACTIVE);
        when(draft.isActive()).thenReturn(true);
        when(draftRepository.findByActiveOwnerId(userId))
                .thenReturn(Optional.of(draft));
        when(draftRedisRepository.findById(draftId))
                .thenReturn(Optional.of(legacyRedisCache));

        DraftResponseDto response = draftService
                .getActiveDraft(userId)
                .orElseThrow();

        assertThat(response.getTitle()).isEqualTo("redis title");
        assertThat(response.getContentVersion()).isEqualTo(2L);

        ArgumentCaptor<DraftCache> cacheCaptor = forClass(DraftCache.class);
        verify(draftRedisRepository).saveInitial(cacheCaptor.capture());
        DraftCache migratedCache = cacheCaptor.getValue();
        assertThat(migratedCache.ownerId()).isEqualTo(userId);
        assertThat(migratedCache.title()).isEqualTo("redis title");
        assertThat(migratedCache.contentVersion()).isEqualTo(2L);
    }

    @Test
    void publishUsesRdbSnapshotWhenRedisConnectionFails() {
        Long userId = 7L;
        Long draftId = 11L;
        User user = mock(User.class);
        Draft draft = mock(Draft.class);
        Post post = mock(Post.class);
        DraftPublishRequestDto request = new DraftPublishRequestDto(
                "request title",
                "request body",
                null,
                2L
        );

        when(user.getUserId()).thenReturn(userId);
        when(user.getNickname()).thenReturn("nickname");
        when(draft.getDraftId()).thenReturn(draftId);
        when(draft.getActiveOwnerId()).thenReturn(userId);
        when(draft.getTitle()).thenReturn("rdb title");
        when(draft.getPostBody()).thenReturn("rdb body");
        when(draft.getPostImage()).thenReturn(null);
        when(draft.getContentVersion()).thenReturn(1L);
        when(draft.getRdbSavedAt()).thenReturn(LocalDateTime.now());
        when(draft.getStatus()).thenReturn(DraftStatus.ACTIVE);
        when(draft.isActive()).thenReturn(true);
        when(draftRepository.findByDraftIdAndUserId(draftId, userId))
                .thenReturn(Optional.of(draft));
        when(postRepository.countByUserIdAndCreatedAtAfter(any(), any()))
                .thenReturn(0L);
        when(draftRedisRepository.saveIfNewer(any(DraftCache.class), any(DraftCache.class)))
                .thenThrow(new RedisConnectionFailureException("Redis unavailable"));
        when(postRepository.saveAndFlush(any(Post.class))).thenReturn(post);
        when(post.getPostId()).thenReturn(99L);
        when(post.getUser()).thenReturn(user);

        DraftPublishResponseDto response = draftService.publishDraft(
                userId,
                draftId,
                request
        );

        assertThat(response.getPostId()).isEqualTo(99L);
        ArgumentCaptor<Post> postCaptor = forClass(Post.class);
        verify(postRepository).saveAndFlush(postCaptor.capture());
        assertThat(postCaptor.getValue().getTitle()).isEqualTo("rdb title");
        assertThat(postCaptor.getValue().getPostBody()).isEqualTo("rdb body");
        verify(draft).publish(
                eq("rdb title"),
                eq("rdb body"),
                isNull(),
                eq(1L),
                eq(99L),
                any(LocalDateTime.class)
        );
    }
}
