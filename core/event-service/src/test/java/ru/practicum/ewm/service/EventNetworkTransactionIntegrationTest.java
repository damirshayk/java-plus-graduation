package ru.practicum.ewm.service;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.EwmEventServiceApplication;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.client.CommentCleanupClient;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.dto.event.UpdateEventAdminRequest;
import ru.practicum.ewm.dto.event.UpdateEventUserRequest;
import ru.practicum.ewm.dto.event.NewEventDto;
import ru.practicum.ewm.dto.event.EventFullDto;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = EwmEventServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:event-network-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.open-in-view=false", "ewm.events.min-start-delay-hours=2",
        "ewm.display.retry.backoff-ms=1"
})
class EventNetworkTransactionIntegrationTest {
    @Autowired
    private EventService service;
    @Autowired
    private EventDisplayEnrichment display;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockBean
    private UserClient users;
    @MockBean
    private RequestClient requests;
    @MockBean
    private StatsClient stats;
    @MockBean
    private CommentCleanupClient comments;

    @BeforeEach
    void setUp() {
        ((CircuitBreaker) ReflectionTestUtils.getField(display, "usersCircuitBreaker")).reset();
        ((CircuitBreaker) ReflectionTestUtils.getField(display, "requestsCircuitBreaker")).reset();
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("DELETE FROM user_data_guard");
        jdbc.update("INSERT INTO categories(id, name) VALUES (1, 'Категория')");
        jdbc.update("""
                INSERT INTO events(id, annotation, category_id, created_on, description, event_date, initiator_id,
                                   location_lat, location_lon, paid, participant_limit, request_moderation, state, title)
                VALUES (10, 'Описание', 1, CURRENT_TIMESTAMP, 'Описание', DATEADD('DAY', 3, CURRENT_TIMESTAMP), 1,
                        1, 1, false, 1, true, 'PENDING', 'Исходное')
                """);
        when(users.get(1L)).thenAnswer(invocation -> {
            assertOutsideTransaction();
            UserShortDto user = new UserShortDto();
            user.setId(1L);
            user.setName("Инициатор");
            return user;
        });
        when(users.batch(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            UserShortDto user = new UserShortDto();
            user.setId(1L);
            user.setName("Инициатор");
            return List.of(user);
        });
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return Map.of();
        });
        when(stats.getStats(any(), any(), anyList(), eq(true))).thenAnswer(invocation -> {
            assertOutsideTransaction();
            return List.of();
        });
    }

    @ParameterizedTest
    @CsvSource({"public,users", "public,requests", "public-list,users", "public-list,requests",
            "admin-list,users", "admin-list,requests", "user,users", "user,requests",
            "user-list,users", "user-list,requests"})
    void ordinaryReadsMustDisplayFallbackOutsideCallerTransaction(String operation, String dependency) {
        jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
        if (dependency.equals("users")) {
            when(users.get(1L)).thenAnswer(invocation -> {
                assertOutsideTransaction();
                throw unavailable();
            });
            when(users.batch(anyList())).thenAnswer(invocation -> {
                assertOutsideTransaction();
                throw unavailable();
            });
            when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
                assertOutsideTransaction();
                return Map.of(10L, 3L);
            });
        } else {
            when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
                assertOutsideTransaction();
                throw unavailable();
            });
        }
        String expectedName = dependency.equals("users") ? "Имя временно недоступно" : "Инициатор";
        long expectedCount = dependency.equals("requests") ? 0L : 3L;
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            if (operation.equals("public") || operation.equals("user") || operation.equals("admin-list")) {
                EventFullDto result = switch (operation) {
                    case "public" -> service.getPublicEvent(10L);
                    case "user" -> service.getUserEvent(1L, 10L);
                    default -> service.getAdminEvents(null, null, null, null, null, 0, 10).getFirst();
                };
                assertThat(result.getId()).isEqualTo(10L);
                assertThat(result.getInitiator().getId()).isEqualTo(1L);
                assertThat(result.getInitiator().getName()).isEqualTo(expectedName);
                assertThat(result.getConfirmedRequests()).isEqualTo(expectedCount);
            } else {
                EventShortDto result = operation.equals("user-list")
                        ? service.getUserEvents(1L, 0, 10).getFirst()
                        : service.getPublicEvents(null, null, null, null, null, false, null, 0, 10).getFirst();
                assertThat(result.getId()).isEqualTo(10L);
                assertThat(result.getInitiator().getId()).isEqualTo(1L);
                assertThat(result.getInitiator().getName()).isEqualTo(expectedName);
                assertThat(result.getConfirmedRequests()).isEqualTo(expectedCount);
            }
        });
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
    }

    @Test
    void onlyAvailableMustNotUseFallbackZeroForFullEvent() {
        jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            throw unavailable();
        });
        assertThatThrownBy(() -> service.getPublicEvents(null, null, null, null, null, true, null, 0, 10))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(requests).confirmedCounts(List.of(10L));
        verifyNoInteractions(users, stats);
    }

    @ParameterizedTest
    @ValueSource(strings = {"users", "requests"})
    void unavailableRequiredDataMustNotApplyUserOrAdminPatch(String dependency) {
        if (dependency.equals("users")) {
            when(users.get(1L)).thenAnswer(invocation -> {
                assertOutsideTransaction();
                throw unavailable();
            });
        } else {
            when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
                assertOutsideTransaction();
                throw unavailable();
            });
        }
        UpdateEventUserRequest userPatch = new UpdateEventUserRequest();
        userPatch.setTitle("Не сохранять");
        UpdateEventAdminRequest adminPatch = new UpdateEventAdminRequest();
        adminPatch.setTitle("Тоже не сохранять");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThatThrownBy(() -> service.updateUserEvent(1L, 10L, userPatch))
                    .isInstanceOf(ServiceUnavailableException.class);
            assertThatThrownBy(() -> service.updateAdminEvent(10L, adminPatch))
                    .isInstanceOf(ServiceUnavailableException.class);
        });
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
        if (dependency.equals("users")) verify(users, times(2)).get(1L);
        else verify(requests, times(2)).confirmedCounts(List.of(10L));
    }

    @Test
    void unavailableUserMustNotCreateEvent() {
        when(users.get(1L)).thenAnswer(invocation -> {
            assertOutsideTransaction();
            throw unavailable();
        });
        NewEventDto dto = new NewEventDto();
        dto.setEventDate(java.time.LocalDateTime.now().plusDays(3));
        dto.setCategory(1L);
        assertThatThrownBy(() -> service.createEvent(1L, dto)).isInstanceOf(ServiceUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM events", Long.class)).isEqualTo(1);
        verify(users).get(1L);
    }

    private FeignException unavailable() {
        Request request = Request.create(Request.HttpMethod.GET, "http://remote/internal", Map.of(),
                (byte[]) null, null, null);
        return new FeignException.ServiceUnavailable("Недоступен", request, null, Map.of());
    }

    @Test
    void readsMustSuspendCallerTransactionBeforeRemoteEnrichment() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(service.getUserEvents(1L, 0, 10)).hasSize(1);
            assertThat(service.getUserEvent(1L, 10L).getCategory().getName()).isEqualTo("Категория");
        });
    }

    @Test
    void userUpdateMustEnrichBeforeShortWriteTransaction() {
        UpdateEventUserRequest patch = new UpdateEventUserRequest();
        patch.setTitle("Изменено");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(service.updateUserEvent(1L, 10L, patch).getTitle()).isEqualTo("Изменено"));
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Изменено");
    }

    @Test
    void adminUpdateMustEnrichBeforeShortWriteTransaction() {
        UpdateEventAdminRequest patch = new UpdateEventAdminRequest();
        patch.setTitle("Администратор");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(service.updateAdminEvent(10L, patch).getTitle()).isEqualTo("Администратор"));
    }

    @Test
    void failedCounterMustNotApplyUserOrAdminPatch() {
        when(requests.confirmedCounts(anyList())).thenThrow(new IllegalStateException("Некорректный ответ"));
        UpdateEventUserRequest userPatch = new UpdateEventUserRequest();
        userPatch.setTitle("Не сохранять");
        assertThatThrownBy(() -> service.updateUserEvent(1L, 10L, userPatch)).isInstanceOf(IllegalStateException.class);
        UpdateEventAdminRequest adminPatch = new UpdateEventAdminRequest();
        adminPatch.setTitle("Тоже не сохранять");
        assertThatThrownBy(() -> service.updateAdminEvent(10L, adminPatch)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
    }

    private void assertOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @Test
    void userStateMustBeRecheckedOnFreshEntityAfterRemoteReply() {
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
            return Map.of();
        });
        UpdateEventUserRequest patch = new UpdateEventUserRequest();
        patch.setTitle("Не сохранять");
        assertThatThrownBy(() -> service.updateUserEvent(1L, 10L, patch))
                .isInstanceOf(ru.practicum.ewm.exception.ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
    }

    @Test
    void adminStateMustBeRecheckedOnFreshEntityAfterRemoteReply() {
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
            return Map.of();
        });
        UpdateEventAdminRequest patch = new UpdateEventAdminRequest();
        patch.setStateAction(ru.practicum.ewm.dto.event.AdminEventStateAction.REJECT_EVENT);
        assertThatThrownBy(() -> service.updateAdminEvent(10L, patch))
                .isInstanceOf(ru.practicum.ewm.exception.ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT state FROM events WHERE id = 10", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    void earlyLocalOwnerStateAndDateFailuresMustNotCallRemoteCounter() {
        assertThatThrownBy(() -> service.updateUserEvent(2L, 10L, new UpdateEventUserRequest()))
                .isInstanceOf(ru.practicum.ewm.exception.NotFoundException.class);
        UpdateEventUserRequest invalidDate = new UpdateEventUserRequest();
        invalidDate.setEventDate(java.time.LocalDateTime.now().plusMinutes(1));
        assertThatThrownBy(() -> service.updateUserEvent(1L, 10L, invalidDate))
                .isInstanceOf(ru.practicum.ewm.exception.ConflictException.class);
        jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
        assertThatThrownBy(() -> service.updateUserEvent(1L, 10L, new UpdateEventUserRequest()))
                .isInstanceOf(ru.practicum.ewm.exception.ConflictException.class);
        verifyNoInteractions(requests, stats, users);
    }
}
