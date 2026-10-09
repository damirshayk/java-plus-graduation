package ru.practicum.ewm.service;

import jakarta.persistence.EntityManagerFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.EwmCommentServiceApplication;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.cleanup.CleanupEventCodec;
import ru.practicum.ewm.cleanup.CleanupEventType;
import ru.practicum.ewm.cleanup.CommonCleanupEvent;
import ru.practicum.ewm.dto.comment.NewCommentDto;
import ru.practicum.ewm.dto.comment.UpdateCommentRequest;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.model.EventState;
import feign.FeignException;
import feign.Request;
import feign.Response;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(classes = EwmCommentServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:comment-cleanup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
})
@AutoConfigureMockMvc
class CommentDataCleanupIntegrationTest {
    @SpyBean
    private JdbcTemplate jdbc;
    @Autowired
    private CommentDataCleanupService cleanup;
    @Autowired
    private CommentCleanupListener listener;
    @Autowired
    private CleanupEventCodec codec;
    @Autowired
    private CommentDataGuard guard;
    @Autowired
    private CommentService service;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private Flyway flyway;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private UserClient users;
    @MockBean
    private EventClient events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM comments");
        jdbc.update("DELETE FROM comment_user_guard");
        jdbc.update("DELETE FROM comment_event_guard");
        jdbc.update("""
                INSERT INTO comments(id, text, author_id, event_id, created_on)
                VALUES (100, 'Другой автор к удаляемому событию', 2, 10, CURRENT_TIMESTAMP),
                       (200, 'Удаляемый автор к чужому событию', 1, 20, CURRENT_TIMESTAMP),
                       (300, 'Сохраняемый комментарий', 2, 20, CURRENT_TIMESTAMP),
                       (400, 'Другое событие и автор', 3, 30, CURRENT_TIMESTAMP)
                """);
    }

    @Test
    void contextMustUseOwnMigrationAndOnlyCommentEntity() {
        assertThat(flyway.info().applied()).hasSize(1);
        assertThat(entityManagerFactory.getMetamodel().getEntities())
                .extracting(entity -> entity.getJavaType().getSimpleName()).containsExactly("Comment");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = 'PUBLIC' AND table_name IN ('EVENTS', 'USERS', 'REQUESTS')
                """, Long.class)).isZero();
    }

    @Test
    void cleanupMustDeleteAuthorOnOtherEventsAndAllAuthorsOnTargetEvents() throws Exception {
        listener.consume(payload(1L, "[10]"));

        assertThat(ids()).containsExactly(300L, 400L);
        assertThat(jdbc.queryForObject("SELECT deleting FROM comment_user_guard WHERE user_id = ?",
                Boolean.class, 1L)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deleting FROM comment_event_guard WHERE event_id = ?",
                Boolean.class, 10L)).isTrue();
        verifyNoInteractions(users, events);
    }

    @Test
    void emptyEventsMustOnlyDeleteAuthorComments() throws Exception {
        listener.consume(payload(1L, "[]"));

        assertThat(ids()).containsExactly(100L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_event_guard", Long.class)).isZero();
        verifyNoInteractions(users, events);
    }

    @Test
    void duplicateUnsortedEventsMustBeDistinctAndCleanupIdempotent() {
        cleanup.deleteUserData(1L, List.of(20L, 10L, 20L, 10L));
        cleanup.deleteUserData(1L, List.of(10L, 20L));

        assertThat(ids()).containsExactly(400L);
        assertThat(jdbc.queryForList("SELECT event_id FROM comment_event_guard ORDER BY event_id", Long.class))
                .containsExactly(10L, 20L);
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> guard.lockForWrite(2L, 10L)))
                .isInstanceOf(NotFoundException.class);
        verifyNoInteractions(users, events);
    }

    @Test
    void nullAndInvalidEventIdsMustFailWithoutWrites() {
        for (String body : new String[]{"null", "[null]", "[0]", "[-1]"}) {
            assertThatThrownBy(() -> listener.consume(payload(1L, body))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_user_guard", Long.class)).isZero();
    }

    @Test
    void guardMustSortAndDeduplicateEventLocksBeforeSql() {
        clearInvocations(jdbc);
        tx().executeWithoutResult(status -> guard.markDeleting(1L, List.of(20L, 10L, 20L)));
        var query = mockingDetails(jdbc).getInvocations().stream()
                .filter(invocation -> invocation.getMethod().getName().equals("queryForList")
                        && invocation.<String>getArgument(0).contains("comment_event_guard"))
                .findFirst().orElseThrow();
        assertThat((Object[]) query.getRawArguments()[2]).containsExactly(10L, 20L);
    }

    @Test
    void guardMustRejectUseWithoutActualTransaction() {
        assertThatThrownBy(() -> guard.lockForWrite(1L, 10L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.markDeleting(1L, List.of(10L))).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_user_guard", Long.class)).isZero();
    }

    @Test
    void staleUserResponseMustNotAllowCreateAfterAuthorCleanup() {
        when(users.get(1L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            cleanup.deleteUserData(1L, List.of());
            return user(1L);
        });
        when(events.get(20L)).thenReturn(new EventInfoDto(20L, 2L, EventState.PUBLISHED, 0, true));

        assertThatThrownBy(() -> service.createComment(1L, 20L, input())).isInstanceOf(NotFoundException.class);

        assertThat(ids()).containsExactly(100L, 300L, 400L);
        verify(users).get(1L);
        verify(events).get(20L);
    }

    @Test
    void staleEventResponseMustNotAllowForeignAuthorCreateAfterEventCleanup() {
        when(users.get(2L)).thenReturn(user(2L));
        when(events.get(10L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            cleanup.deleteUserData(1L, List.of(10L));
            return new EventInfoDto(10L, 1L, EventState.PUBLISHED, 0, true);
        });

        assertThatThrownBy(() -> service.createComment(2L, 10L, input())).isInstanceOf(NotFoundException.class);

        assertThat(ids()).containsExactly(300L, 400L);
        verify(users).get(2L);
        verify(events).get(10L);
    }

    @Test
    void updateAndDeleteMustRejectFrozenEventEvenWithExistingCommentAndStaleUser() {
        cleanup.deleteUserData(1L, List.of(10L));
        insertComment(500L, 2L, 10L);
        when(users.get(2L)).thenReturn(user(2L));
        UpdateCommentRequest update = new UpdateCommentRequest();
        update.setText("Обновление");

        assertThatThrownBy(() -> service.updateComment(2L, 10L, 500L, update)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.deleteComment(2L, 10L, 500L)).isInstanceOf(NotFoundException.class);

        assertThat(jdbc.queryForObject("SELECT text FROM comments WHERE id = ?", String.class, 500L))
                .isEqualTo("Комментарий");
        verifyNoInteractions(events);
    }

    @Test
    void realCommentQueryMustRespectAuthorAndEventPathAndPreserveDto() {
        when(users.get(2L)).thenReturn(user(2L));
        var result = service.getCommentById(2L, 10L, 100L);
        assertThat(result.getId()).isEqualTo(100L);
        assertThat(result.getEventId()).isEqualTo(10L);
        assertThat(result.getAuthor().getId()).isEqualTo(2L);
        assertThat(result.getCreatedOn()).isNotNull();
        assertThat(result.getUpdatedOn()).isNull();
        assertThatThrownBy(() -> service.getCommentById(2L, 20L, 100L)).isInstanceOf(NotFoundException.class);
        when(users.get(3L)).thenReturn(user(3L));
        assertThatThrownBy(() -> service.getCommentById(3L, 10L, 100L)).isInstanceOf(NotFoundException.class);
        verifyNoInteractions(events);
    }

    @Test
    void createAndListMustCallEachRemoteOnceOutsideLocalTransaction() {
        when(users.get(2L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return user(2L);
        });
        when(events.get(20L)).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return new EventInfoDto(20L, 2L, EventState.PUBLISHED, 0, true);
        });
        service.createComment(2L, 20L, input());

        var result = service.getCommentsByEvent(2L, 20L, 0, 10);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(comment -> {
            assertThat(comment.getEventId()).isEqualTo(20L);
            assertThat(comment.getAuthor().getId()).isEqualTo(2L);
        });
        verify(users, times(2)).get(2L);
        verify(events, times(2)).get(20L);
        verifyNoMoreInteractions(users, events);
    }

    @Test
    void publicCommentEndpointsMustKeep404ForNonPositiveIds() throws Exception {
        when(users.get(2L)).thenReturn(user(2L));
        for (long id : new long[]{0, -1}) {
            doThrow(remoteFailure(404)).when(users).get(id);
            doThrow(remoteFailure(404)).when(events).get(id);
            mvc.perform(get("/users/{userId}/events/20/comments", id)).andExpect(status().isNotFound());
            mvc.perform(post("/users/2/events/{eventId}/comments", id)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Комментарий\"}"))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/users/2/events/20/comments/{commentId}", id)).andExpect(status().isNotFound());
        }
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_user_guard", Long.class)).isZero();
    }

    @Test
    void eventConnectionAndServerFailuresMustReturn503BeforeWrites() throws Exception {
        when(users.get(2L)).thenReturn(user(2L));
        for (int status : new int[]{-1, 503}) {
            doThrow(remoteFailure(status)).when(events).get(20L);
            mvc.perform(post("/users/2/events/20/comments")
                            .contentType(MediaType.APPLICATION_JSON).content("{\"text\":\"Комментарий\"}"))
                    .andExpect(status().isServiceUnavailable());
        }
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_user_guard", Long.class)).isZero();
    }

    @Test
    void cleanupSqlCountMustRemainFixedForOneAndManyEvents() {
        clearInvocations(jdbc);
        cleanup.deleteUserData(11L, List.of(110L));
        long small = sqlCalls();
        clearInvocations(jdbc);
        cleanup.deleteUserData(12L, LongStream.rangeClosed(210, 260).boxed().toList());
        long large = sqlCalls();

        assertThat(small).isEqualTo(7);
        assertThat(large).isEqualTo(small);
        verifyNoInteractions(users, events);
    }

    @Test
    void cleanupMustWaitForAuthorCreateAndRemoveCommittedComment() throws Exception {
        assertConcurrentCleanup(1L, 20L);
    }

    @Test
    void cleanupMustWaitForForeignAuthorEventCreateAndRemoveCommittedComment() throws Exception {
        assertConcurrentCleanup(2L, 10L);
    }

    private void assertConcurrentCleanup(Long authorId, Long eventId) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch allowCommit = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var creation = executor.submit(() -> tx().executeWithoutResult(status -> {
                guard.lockForWrite(authorId, eventId);
                locked.countDown();
                await(allowCommit);
                insertComment(500L, authorId, eventId);
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> {
                cleanupStarted.countDown();
                cleanup.deleteUserData(1L, List.of(10L));
            });
            assertThat(cleanupStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> deletion.get(150, TimeUnit.MILLISECONDS));
            allowCommit.countDown();
            creation.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(ids()).containsExactly(300L, 400L);
            assertThatThrownBy(() -> tx().executeWithoutResult(status -> guard.lockForWrite(authorId, eventId)))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            allowCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    private long sqlCalls() {
        Set<String> methods = Set.of("update", "queryForList", "queryForObject");
        return mockingDetails(jdbc).getInvocations().stream().filter(invocation -> {
            var method = invocation.getMethod();
            var parameters = method.getParameterTypes();
            return methods.contains(method.getName()) && parameters.length > 0
                    && parameters[parameters.length - 1] == Object[].class;
        }).count();
    }

    private TransactionTemplate tx() {
        return new TransactionTemplate(transactionManager);
    }

    @Test
    void duplicateAndReorderedPhasesMustNotUnfreezeOrRestoreData() {
        String snapshot = payload(1L, "[10]");
        listener.consume(snapshot);
        listener.consume(codec.encode(new CommonCleanupEvent(UUID.randomUUID(),
                CleanupEventType.USER_DELETED, 1L, List.of())));
        listener.consume(snapshot);
        assertThat(ids()).containsExactly(300L, 400L);
        assertThat(jdbc.queryForObject("SELECT deleting FROM comment_event_guard WHERE event_id = 10", Boolean.class)).isTrue();
        verifyNoInteractions(users, events);
    }

    @Test
    void listenerMustRollbackDeletedCommentsAndMarkersTogether() {
        assertThatThrownBy(() -> tx().executeWithoutResult(status -> {
            listener.consume(payload(1L, "[10]"));
            throw new IllegalStateException("Проверка отката");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(ids()).containsExactly(100L, 200L, 300L, 400L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_user_guard", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM comment_event_guard", Long.class)).isZero();
    }

    private String payload(Long userId, String body) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"type\":\"USER_EVENTS_DELETED\",\"userId\":"
                + userId + ",\"eventIds\":" + body + "}";
    }

    private List<Long> ids() {
        return jdbc.queryForList("SELECT id FROM comments ORDER BY id", Long.class);
    }

    private void insertComment(Long id, Long authorId, Long eventId) {
        jdbc.update("""
                INSERT INTO comments(id, text, author_id, event_id, created_on)
                VALUES (?, 'Комментарий', ?, ?, CURRENT_TIMESTAMP)
                """, id, authorId, eventId);
    }

    private UserShortDto user(Long id) {
        UserShortDto result = new UserShortDto();
        result.setId(id);
        result.setName("Пользователь " + id);
        return result;
    }

    private FeignException remoteFailure(int status) {
        Request request = Request.create(Request.HttpMethod.GET, "http://directory/internal/20",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("Directory#get",
                Response.builder().status(status).request(request).headers(Map.of()).build());
    }

    private NewCommentDto input() {
        NewCommentDto input = new NewCommentDto();
        input.setText("Комментарий");
        return input;
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Истекло время ожидания тестовой транзакции");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Тестовая транзакция прервана", exception);
        }
    }
}
