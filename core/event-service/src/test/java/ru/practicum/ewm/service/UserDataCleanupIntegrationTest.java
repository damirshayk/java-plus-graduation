package ru.practicum.ewm.service;

import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.EwmEventServiceApplication;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.client.CommentCleanupClient;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.dto.event.NewEventDto;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import feign.FeignException;
import feign.Request;
import feign.Response;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Map;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.ArgumentMatchers.eq;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.event.EventFullDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.concurrent.CountDownLatch;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = EwmEventServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:user-cleanup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "ewm.events.min-start-delay-hours=2"
})
@AutoConfigureMockMvc
class UserDataCleanupIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserDataCleanupService cleanup;
    @Autowired
    private UserDataGuard guard;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private CompilationService compilationService;
    @Autowired
    private EventService eventService;
    @Autowired
    private EntityManagerFactory entityManagerFactory;
    @MockBean
    private UserClient userClient;
    @MockBean
    private StatsClient statsClient;
    @MockBean
    private CommentCleanupClient comments;

    @MockBean
    private RequestClient requests;

    @BeforeEach
    void setUp() {
        when(requests.confirmedCounts(anyList())).thenReturn(Map.of());
        jdbc.update("DELETE FROM compilations_events");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM user_data_guard");
        jdbc.update("INSERT INTO categories(id, name) VALUES (1, 'Категория')");
        insertEvent(10L, 1L);
        insertEvent(20L, 2L);
        jdbc.update("INSERT INTO compilations(id, title, is_pinned) VALUES (1, 'Подборка', false)");
        jdbc.update("INSERT INTO compilations_events(compilation_id, event_id) VALUES (1, 10), (1, 20)");
    }

    @Test
    void cleanupShouldPreserveUnrelatedDataAndBeIdempotent() throws Exception {
        mvc.perform(delete("/internal/users/1/data")).andExpect(status().isNoContent());
        mvc.perform(delete("/internal/users/1/data")).andExpect(status().isNoContent());
        mvc.perform(delete("/internal/users/999/data")).andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("SELECT id FROM events ORDER BY id", Long.class)).containsExactly(20L);
        assertThat(jdbc.queryForList("SELECT event_id FROM compilations_events ORDER BY event_id", Long.class)).containsExactly(20L);
        assertThat(count("categories")).isEqualTo(1);
        assertThat(count("compilations")).isEqualTo(1);
        verify(requests).cleanup(1L, List.of(10L));
        verify(requests).cleanup(1L, List.of());
        verify(requests).cleanup(999L, List.of());
        verifyNoInteractions(userClient);
    }

    @Test
    void cleanupMustKeepFreezeWhenCallingTransactionRollsBack() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            cleanup.deleteUserData(1L);
            assertThat(count("events")).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = ?", Boolean.class, 1L))
                    .isTrue();
            throw new IllegalStateException("Проверка отката");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(count("events")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = ?", Boolean.class, 1L))
                .isTrue();
    }

    @Test
    void staleUserResponseMustNotAllowCreationAfterCleanup() {
        cleanup.deleteUserData(1L);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> tx.executeWithoutResult(status -> guard.lockForCreate(1L)))
                .isInstanceOf(NotFoundException.class);
        assertThat(count("events")).isEqualTo(1);
    }

    @Test
    void guardShouldRejectUseWithoutLocalTransaction() {
        assertThatThrownBy(() -> guard.lockForCreate(1L)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> guard.markDeleting(1L)).isInstanceOf(IllegalStateException.class);
        assertThat(count("user_data_guard")).isZero();
    }

    @Test
    void cleanupMustWaitForCreationTransactionAndDeleteItsCommittedData() throws Exception {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch allowCreateCommit = new CountDownLatch(1);
        CountDownLatch cleanupStarted = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            var creation = executor.submit(() -> tx.executeWithoutResult(status -> {
                guard.lockForCreate(1L);
                locked.countDown();
                await(allowCreateCommit);
                insertEvent(30L, 1L);
            }));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> {
                cleanupStarted.countDown();
                cleanup.deleteUserData(1L);
            });
            assertThat(cleanupStarted.await(3, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> deletion.get(150, TimeUnit.MILLISECONDS));
            allowCreateCommit.countDown();
            creation.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);

            assertThat(jdbc.queryForList("SELECT id FROM events ORDER BY id", Long.class)).containsExactly(20L);
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> guard.lockForCreate(1L)))
                    .isInstanceOf(NotFoundException.class);
        } finally {
            allowCreateCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void compilationPageShouldUseOneUserBatchAndFixedSqlQueryCount() {
        jdbc.update("INSERT INTO categories(id, name) VALUES (2, 'Другая категория')");
        jdbc.update("UPDATE events SET category_id = 2 WHERE id = 20");
        for (int id = 2; id <= 8; id++) {
            jdbc.update("INSERT INTO compilations(id, title, is_pinned) VALUES (?, ?, false)", id, "Подборка " + id);
            jdbc.update("INSERT INTO compilations_events(compilation_id, event_id) VALUES (?, 10), (?, 20)", id, id);
        }
        when(userClient.batch(anyList())).thenReturn(List.of(user(1L), user(2L)));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<CompilationDto> result = compilationService.getCompilations(null, 0, 20);

        assertThat(result).hasSize(8);
        assertThat(result).allSatisfy(compilation -> {
            assertThat(compilation.getEvents()).hasSize(2);
            assertThat(compilation.getEvents()).allSatisfy(event -> {
                assertThat(event.getInitiator().getName()).isEqualTo("Пользователь " + event.getInitiator().getId());
                assertThat(event.getCategory().getName()).isNotBlank();
                assertThat(event.getConfirmedRequests()).isZero();
                assertThat(event.getViews()).isZero();
            });
        });
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        verify(userClient, times(1)).batch(argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(1L, 2L))));
    }

    @Test
    void adminEventPageShouldBatchUsersAndCategories() {
        jdbc.update("INSERT INTO categories(id, name) VALUES (2, 'Другая категория')");
        jdbc.update("UPDATE events SET category_id = 2 WHERE id = 20");
        when(userClient.batch(anyList())).thenReturn(List.of(user(1L), user(2L)));
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        List<EventFullDto> result = eventService.getAdminEvents(null, null, null, null, null, 0, 20);

        assertThat(result).hasSize(2);
        assertThat(result).allSatisfy(event -> {
            assertThat(event.getInitiator().getName()).isEqualTo("Пользователь " + event.getInitiator().getId());
            assertThat(event.getCategory().getName()).isNotBlank();
        });
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(3);
        verify(userClient, times(1)).batch(List.of(1L, 2L));
    }


    @Test
    void cleanupMustFreezeAndSnapshotBeforeRemoteCallOutsideTransaction() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<List<Long>>getArgument(1)).containsExactly(10L);
            assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = ?",
                    Boolean.class, 1L)).isTrue();
            assertThat(count("events")).isEqualTo(2);
            return null;
        }).when(comments).cleanup(eq(1L), anyList());

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> cleanup.deleteUserData(1L));

        assertThat(count("events")).isEqualTo(1);
        verify(comments).cleanup(1L, List.of(10L));
    }

    @Test
    void remoteFailureMustRetainEventsAndFreezeCreatesUntilIdempotentRetry() {
        insertEvent(30L, 1L);
        doThrow(remoteFailure(503)).doNothing().when(comments).cleanup(1L, List.of(10L, 30L));

        assertThatThrownBy(() -> cleanup.deleteUserData(1L)).isInstanceOf(ServiceUnavailableException.class);

        assertThat(count("events")).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = ?",
                Boolean.class, 1L)).isTrue();
        when(userClient.get(1L)).thenReturn(user(1L));
        NewEventDto dto = new NewEventDto();
        dto.setCategory(1L);
        dto.setEventDate(LocalDateTime.now().plusDays(2));
        assertThatThrownBy(() -> eventService.createEvent(1L, dto)).isInstanceOf(NotFoundException.class);
        assertThat(count("events")).isEqualTo(3);

        cleanup.deleteUserData(1L);

        assertThat(jdbc.queryForList("SELECT id FROM events ORDER BY id", Long.class)).containsExactly(20L);
        verify(comments, times(2)).cleanup(1L, List.of(10L, 30L));
    }

    @Test
    void connectionFailureMustBecome503() throws Exception {
        doThrow(remoteFailure(-1)).when(comments).cleanup(eq(1L), anyList());
        mvc.perform(delete("/internal/users/1/data")).andExpect(status().isServiceUnavailable());
        assertThat(count("events")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = ?",
                Boolean.class, 1L)).isTrue();
    }

    @Test
    void phaseTwoDatabaseFailureMustRollbackLocalDeletesButKeepInitialFreezeAndSnapshot() {
        jdbc.execute("CREATE TABLE cleanup_test_block(event_id BIGINT REFERENCES events(id))");
        jdbc.update("INSERT INTO cleanup_test_block(event_id) VALUES (10)");
        try {
            assertThatThrownBy(() -> cleanup.deleteUserData(1L))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(count("events")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = ?",
                    Boolean.class, 1L)).isTrue();
        } finally {
            jdbc.execute("DROP TABLE cleanup_test_block");
        }

        cleanup.deleteUserData(1L);

        verify(comments, times(2)).cleanup(1L, List.of(10L));
        assertThat(count("events")).isEqualTo(1);
        verify(requests, times(2)).cleanup(1L, List.of(10L));
    }

    @Test
    void emptySnapshotMustStillCleanAuthorOnce() {
        cleanup.deleteUserData(999L);
        verify(comments).cleanup(999L, List.of());
        verify(requests).cleanup(999L, List.of());
        assertThat(count("events")).isEqualTo(2);
    }

    @Test
    void bothRemoteAcksMustFinishOutsideCallerTransactionBeforeLocalEventDeletion() {
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            assertThat(invocation.<List<Long>>getArgument(1)).containsExactly(10L);
            assertThat(count("events")).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = 1", Boolean.class))
                    .isTrue();
            return null;
        }).when(requests).cleanup(eq(1L), anyList());
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> cleanup.deleteUserData(1L));
        var order = org.mockito.Mockito.inOrder(comments, requests);
        order.verify(comments).cleanup(1L, List.of(10L));
        order.verify(requests).cleanup(1L, List.of(10L));
        assertThat(count("events")).isEqualTo(1);
    }

    @Test
    void requestFailureAfterCommentSuccessMustRetainSnapshotAndAllowIdempotentRetry() {
        doThrow(remoteFailure(503)).doNothing().when(requests).cleanup(1L, List.of(10L));
        assertThatThrownBy(() -> cleanup.deleteUserData(1L)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(count("events")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT deleting FROM user_data_guard WHERE user_id = 1", Boolean.class)).isTrue();
        cleanup.deleteUserData(1L);
        assertThat(jdbc.queryForList("SELECT id FROM events ORDER BY id", Long.class)).containsExactly(20L);
        verify(comments, times(2)).cleanup(1L, List.of(10L));
        verify(requests, times(2)).cleanup(1L, List.of(10L));
    }

    @Test
    void requestConnectionAndServerFailuresMustStopPhaseTwoWith503() throws Exception {
        for (int status : new int[]{-1, 500, 503}) {
            doThrow(remoteFailure(status)).when(requests).cleanup(1L, List.of(10L));
            mvc.perform(delete("/internal/users/1/data")).andExpect(status().isServiceUnavailable());
            assertThat(count("events")).isEqualTo(2);
        }
        verify(comments, times(3)).cleanup(1L, List.of(10L));
    }

    @Test
    void requestDecodeAndOtherHttpFailuresMustKeepTheirOriginalMeaning() {
        Request request = Request.create(Request.HttpMethod.DELETE, "http://request-service/internal/requests/users/1",
                Map.of(), null, StandardCharsets.UTF_8, null);
        feign.codec.DecodeException decode = new feign.codec.DecodeException(500, "Некорректный ответ", request);
        doThrow(decode).when(requests).cleanup(1L, List.of(10L));
        assertThatThrownBy(() -> cleanup.deleteUserData(1L)).isSameAs(decode);
        FeignException invalid = remoteFailure(400);
        doThrow(invalid).when(requests).cleanup(1L, List.of(10L));
        assertThatThrownBy(() -> cleanup.deleteUserData(1L)).isSameAs(invalid);
        assertThat(count("events")).isEqualTo(2);
    }

    @Test
    void internalEventEndpointMustUseOneProjectionWithoutOtherServices() throws Exception {
        jdbc.update("UPDATE events SET participant_limit = 25, request_moderation = true WHERE id = 10");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(get("/internal/events/10")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.initiatorId").value(1))
                .andExpect(jsonPath("$.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.participantLimit").value(25))
                .andExpect(jsonPath("$.requestModeration").value(true))
                .andExpect(jsonPath("$.initiator").doesNotExist());
        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verifyNoInteractions(userClient, statsClient, comments);
        mvc.perform(get("/internal/events/999")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/events/0")).andExpect(status().isNotFound());
        mvc.perform(get("/internal/events/-1")).andExpect(status().isNotFound());
    }

    @Test
    void internalEventEndpointMustPreserveZeroLimitAndDisabledModeration() throws Exception {
        jdbc.update("UPDATE events SET participant_limit = 0, request_moderation = false WHERE id = 20");
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();

        mvc.perform(get("/internal/events/20")).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(20))
                .andExpect(jsonPath("$.initiatorId").value(2))
                .andExpect(jsonPath("$.state").value("PUBLISHED"))
                .andExpect(jsonPath("$.participantLimit").value(0))
                .andExpect(jsonPath("$.requestModeration").value(false));

        assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
        verifyNoInteractions(userClient, statsClient, comments);
    }

    private FeignException remoteFailure(int status) {
        Request request = Request.create(Request.HttpMethod.POST, "http://comment-service/internal/users/1/comments/cleanup",
                Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("CommentCleanupClient#cleanup",
                Response.builder().status(status).request(request).headers(Map.of()).build());
    }

    private UserShortDto user(Long id) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName("Пользователь " + id);
        return user;
    }

    private void insertEvent(Long eventId, Long initiatorId) {
        jdbc.update("""
                INSERT INTO events(id, annotation, category_id, created_on, description, event_date, initiator_id,
                                   location_lat, location_lon, paid, participant_limit, request_moderation, state, title)
                VALUES (?, 'Описание', 1, CURRENT_TIMESTAMP, 'Описание события', CURRENT_TIMESTAMP, ?,
                        1, 1, false, 0, true, 'PUBLISHED', 'Событие')
                """, eventId, initiatorId);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
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
