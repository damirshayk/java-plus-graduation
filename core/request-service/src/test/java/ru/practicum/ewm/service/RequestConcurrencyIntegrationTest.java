package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.ewm.RequestServiceApplication;
import ru.practicum.ewm.client.event.EventClient;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.model.RequestStatus;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.model.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.model.RequestUpdateStatus;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.List;
import java.util.concurrent.TimeoutException;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = RequestServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:request-race;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "ewm.events.min-start-delay-hours=2"
})
class RequestConcurrencyIntegrationTest {
    @Autowired
    private RequestService service;
    @Autowired
    private JdbcTemplate jdbc;
    @SpyBean
    private RequestRepository repository;
    @SpyBean
    private RequestDataGuard guard;
    @Autowired
    private RequestDataCleanupService cleanup;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private UserClient users;
    @MockBean
    private EventClient events;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM requests");
        jdbc.update("DELETE FROM request_user_guard");
        jdbc.update("DELETE FROM request_event_guard");
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.PUBLISHED, 1, false));
        for (long id : new long[]{1, 2}) {
            UserShortDto user = new UserShortDto();
            user.setId(id);
            user.setName("Участник");
            when(users.get(id)).thenReturn(user);
        }
    }

    @Test
    void simultaneousAutomaticRequestsMustRespectLimit() throws Exception {
        CountDownLatch counts = new CountDownLatch(2);
        doAnswer(invocation -> {
            Long count = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM requests WHERE event_id = ? AND status = 'CONFIRMED'", Long.class, 10L);
            counts.countDown();
            counts.await(1, TimeUnit.SECONDS);
            return count;
        }).when(repository).countByEventIdAndStatus(10L, RequestStatus.CONFIRMED);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> create(start, 1L));
            var second = executor.submit(() -> create(start, 2L));
            start.countDown();
            int successful = first.get(15, TimeUnit.SECONDS) + second.get(15, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE status = 'CONFIRMED'", Long.class))
                    .isEqualTo(1);
            assertThat(successful).isEqualTo(1);
        }
    }

    private int create(CountDownLatch start, Long userId) throws InterruptedException {
        start.await();
        try {
            service.addParticipationRequest(userId, 10L);
            return 1;
        } catch (ConflictException exception) {
            return 0;
        }
    }

    @Test
    void simultaneousConfirmationsMustRespectLimit() throws Exception {
        moderatedEvent();
        insert(100L, 1L);
        insert(200L, 2L);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> confirm(start, 100L));
            var second = executor.submit(() -> confirm(start, 200L));
            start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests WHERE status = 'CONFIRMED'", Long.class))
                    .isEqualTo(1);
        }
    }

    @Test
    void cancelMustWaitForConfirmationBeforeFirstFullEntityLoad() throws Exception {
        moderatedEvent();
        insert(100L, 1L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch cancelAtLock = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            locked.countDown();
            await(release);
            return result;
        }).when(guard).lockForWrite(99L, 10L);
        doAnswer(invocation -> {
            cancelAtLock.countDown();
            return invocation.callRealMethod();
        }).when(guard).lockForWrite(1L, 10L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var confirmation = executor.submit(() -> service.changeRequestStatus(99L, 10L, update(100L)));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var cancellation = executor.submit(() -> service.cancelRequest(1L, 100L));
            assertThat(cancelAtLock.await(3, TimeUnit.SECONDS)).isTrue();
            assertThrows(TimeoutException.class, () -> cancellation.get(150, TimeUnit.MILLISECONDS));
            verify(repository, never()).findById(100L);
            release.countDown();
            assertThat(confirmation.get(5, TimeUnit.SECONDS).getConfirmedRequests()).hasSize(1);
            assertThat(cancellation.get(5, TimeUnit.SECONDS).getStatus()).isEqualTo("CANCELED");
            var order = inOrder(repository, guard);
            order.verify(repository).findEventIdByRequestId(100L);
            order.verify(guard).lockForWrite(1L, 10L);
            order.verify(repository).findById(100L);
            assertThat(jdbc.queryForObject("SELECT status FROM requests WHERE id = 100", String.class))
                    .isEqualTo("CANCELED");
        } finally {
            release.countDown();
        }
        verify(events, times(1)).get(10L);
    }

    @Test
    void cleanupWithoutOwnedEventsMustSerializeWithForeignEventModeration() throws Exception {
        moderatedEvent();
        insert(100L, 1L);
        insert(200L, 2L);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            locked.countDown();
            await(release);
            return result;
        }).when(guard).lockForWrite(99L, 10L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var moderation = executor.submit(() -> service.changeRequestStatus(99L, 10L, update(100L)));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> cleanup.deleteUserData(1L, List.of()));
            assertThrows(TimeoutException.class, () -> deletion.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            moderation.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForList("SELECT id FROM requests ORDER BY id", Long.class)).containsExactly(200L);
            assertThat(jdbc.queryForObject("SELECT deleting FROM request_event_guard WHERE event_id = 10", Boolean.class))
                    .isFalse();
            new TransactionTemplate(transactionManager).executeWithoutResult(tx -> guard.lockForWrite(2L, 10L));
        } finally {
            release.countDown();
        }
    }

    @Test
    void ownedEventCleanupMustWaitForForeignRequesterCreateAndDeleteCommittedRequest() throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            locked.countDown();
            await(release);
            return result;
        }).when(guard).lockForWrite(2L, 10L);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var creation = executor.submit(() -> service.addParticipationRequest(2L, 10L));
            assertThat(locked.await(3, TimeUnit.SECONDS)).isTrue();
            var deletion = executor.submit(() -> cleanup.deleteUserData(99L, List.of(10L)));
            assertThrows(TimeoutException.class, () -> deletion.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            creation.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM requests", Long.class)).isZero();
        } finally {
            release.countDown();
        }
    }

    private void moderatedEvent() {
        UserShortDto owner = new UserShortDto();
        owner.setId(99L);
        when(users.get(99L)).thenReturn(owner);
        when(events.get(10L)).thenReturn(new EventInfoDto(10L, 99L, EventState.PUBLISHED, 1, true));
    }

    private void insert(Long id, Long requesterId) {
        jdbc.update("""
                INSERT INTO requests(id, event_id, requester_id, status, created)
                VALUES (?, 10, ?, 'PENDING', CURRENT_TIMESTAMP)
                """, id, requesterId);
    }

    private EventRequestStatusUpdateRequest update(Long requestId) {
        EventRequestStatusUpdateRequest result = new EventRequestStatusUpdateRequest();
        result.setRequestIds(List.of(requestId));
        result.setStatus(RequestUpdateStatus.CONFIRMED);
        return result;
    }

    private int confirm(CountDownLatch start, Long requestId) throws InterruptedException {
        start.await();
        try {
            service.changeRequestStatus(99L, 10L, update(requestId));
            return 1;
        } catch (ConflictException exception) {
            return 0;
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Истекло ожидание тестовой транзакции");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
