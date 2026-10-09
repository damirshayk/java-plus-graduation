package ru.practicum.ewm.service;

import feign.FeignException;
import feign.Request;
import feign.codec.DecodeException;
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
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.dto.event.UpdateEventAdminRequest;
import ru.practicum.ewm.dto.event.UpdateEventUserRequest;
import ru.practicum.ewm.dto.event.NewEventDto;
import ru.practicum.ewm.dto.event.EventFullDto;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.dto.event.AdminEventStateAction;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.model.EventState;

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
    @CsvSource({"user,users", "user,requests", "user,both", "admin,users", "admin,requests", "admin,both",
            "publish,users", "publish,requests", "publish,both", "reject,users", "reject,requests", "reject,both"})
    void unavailableDisplayDataMustNotPreventPatch(String operation, String dependency) {
        doAnswer(invocation -> {
            assertOutsideTransaction();
            return Map.of(10L, 3L);
        }).when(requests).confirmedCounts(anyList());
        if (!dependency.equals("requests")) failDependency("users", unavailable());
        if (!dependency.equals("users")) failDependency("requests", unavailable());
        EventState expectedState = switch (operation) {
            case "publish" -> EventState.PUBLISHED;
            case "reject" -> EventState.CANCELED;
            default -> EventState.PENDING;
        };
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            EventFullDto result = update(operation, "Сохранено");
            assertThat(result.getId()).isEqualTo(10L);
            assertThat(result.getTitle()).isEqualTo("Сохранено");
            assertThat(result.getState()).isEqualTo(expectedState);
            assertThat(result.getInitiator().getId()).isEqualTo(1L);
            assertThat(result.getInitiator().getName()).isEqualTo(dependency.equals("requests")
                    ? "Инициатор" : "Имя временно недоступно");
            assertThat(result.getConfirmedRequests()).isEqualTo(dependency.equals("users") ? 3L : 0L);
            if (operation.equals("publish")) assertThat(result.getPublishedOn()).isNotNull();
        });
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Сохранено");
        assertThat(jdbc.queryForObject("SELECT state FROM events WHERE id = 10", String.class))
                .isEqualTo(expectedState.name());
    }

    @ParameterizedTest
    @CsvSource({"user,users", "user,requests", "admin,users", "admin,requests"})
    void patchMustRestoreDisplayDataAfterDependencyRecovers(String operation, String dependency) {
        failDependency(dependency, unavailable());
        for (int attempt = 0; attempt < 3; attempt++) {
            EventFullDto result = update(operation, "Во время отказа");
            assertThat(result.getInitiator().getId()).isEqualTo(1L);
            assertThat(result.getInitiator().getName()).isEqualTo(dependency.equals("users")
                    ? "Имя временно недоступно" : "Инициатор");
            assertThat(result.getConfirmedRequests()).isZero();
        }
        CircuitBreaker circuit = (CircuitBreaker) ReflectionTestUtils.getField(display,
                dependency.equals("users") ? "usersCircuitBreaker" : "requestsCircuitBreaker");
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        circuit.transitionToHalfOpenState();
        doAnswer(invocation -> {
            assertOutsideTransaction();
            UserShortDto user = new UserShortDto();
            user.setId(1L);
            user.setName("Восстановленное имя");
            return user;
        }).when(users).get(1L);
        doAnswer(invocation -> {
            assertOutsideTransaction();
            return Map.of(10L, 4L);
        }).when(requests).confirmedCounts(anyList());

        EventFullDto restored = update(operation, "После восстановления");

        assertThat(restored.getInitiator().getId()).isEqualTo(1L);
        assertThat(restored.getInitiator().getName()).isEqualTo("Восстановленное имя");
        assertThat(restored.getConfirmedRequests()).isEqualTo(4L);
        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class))
                .isEqualTo("После восстановления");
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
        return new FeignException.ServiceUnavailable("Недоступен", remoteRequest(), null, Map.of());
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

    @ParameterizedTest
    @CsvSource({"user,users,missing", "user,users,decode", "user,users,programming",
            "user,requests,missing", "user,requests,decode", "user,requests,programming",
            "admin,users,missing", "admin,users,decode", "admin,users,programming",
            "admin,requests,missing", "admin,requests,decode", "admin,requests,programming"})
    void permanentRemoteErrorsMustNotApplyPatch(String operation, String dependency, String fault) {
        RuntimeException error = switch (fault) {
            case "missing" -> new FeignException.NotFound("Не найден", remoteRequest(), null, Map.of());
            case "decode" -> new DecodeException(503, "Некорректный JSON", remoteRequest());
            default -> new IllegalStateException("Ошибка кода");
        };
        failDependency(dependency, error);
        assertThatThrownBy(() -> update(operation, "Не сохранять"))
                .isInstanceOf(dependency.equals("users") && fault.equals("missing")
                        ? NotFoundException.class : error.getClass());
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
        if (dependency.equals("users")) verify(users).get(1L);
        else verify(requests).confirmedCounts(List.of(10L));
    }

    private void assertOutsideTransaction() {
        assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void userStateMustBeRecheckedOnFreshEntityAfterRemoteReply(boolean unavailable) {
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
            if (unavailable) throw unavailable();
            return Map.of();
        });
        UpdateEventUserRequest patch = new UpdateEventUserRequest();
        patch.setTitle("Не сохранять");
        assertThatThrownBy(() -> service.updateUserEvent(1L, 10L, patch))
                .isInstanceOf(ru.practicum.ewm.exception.ConflictException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void adminStateMustBeRecheckedOnFreshEntityAfterRemoteReply(boolean unavailable) {
        when(requests.confirmedCounts(anyList())).thenAnswer(invocation -> {
            assertOutsideTransaction();
            jdbc.update("UPDATE events SET state = 'PUBLISHED' WHERE id = 10");
            if (unavailable) throw unavailable();
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unknownCategoryMustRollbackPatchEvenWhenDisplayDataIsUnavailable(boolean admin) {
        failDependency("users", unavailable());
        failDependency("requests", unavailable());
        UpdateEventUserRequest userPatch = new UpdateEventUserRequest();
        userPatch.setTitle("Не сохранять");
        userPatch.setCategory(99L);
        UpdateEventAdminRequest adminPatch = new UpdateEventAdminRequest();
        adminPatch.setTitle("Не сохранять");
        adminPatch.setCategory(99L);
        assertThatThrownBy(() -> {
            if (admin) service.updateAdminEvent(10L, adminPatch);
            else service.updateUserEvent(1L, 10L, userPatch);
        }).isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT title FROM events WHERE id = 10", String.class)).isEqualTo("Исходное");
        assertThat(jdbc.queryForObject("SELECT category_id FROM events WHERE id = 10", Long.class)).isEqualTo(1L);
    }

    private EventFullDto update(String operation, String title) {
        if (operation.equals("user")) {
            UpdateEventUserRequest patch = new UpdateEventUserRequest();
            patch.setTitle(title);
            return service.updateUserEvent(1L, 10L, patch);
        }
        UpdateEventAdminRequest patch = new UpdateEventAdminRequest();
        patch.setTitle(title);
        if (operation.equals("publish")) patch.setStateAction(AdminEventStateAction.PUBLISH_EVENT);
        if (operation.equals("reject")) patch.setStateAction(AdminEventStateAction.REJECT_EVENT);
        return service.updateAdminEvent(10L, patch);
    }

    private void failDependency(String dependency, RuntimeException error) {
        if (dependency.equals("users")) {
            doAnswer(invocation -> {
                assertOutsideTransaction();
                throw error;
            }).when(users).get(1L);
        } else {
            doAnswer(invocation -> {
                assertOutsideTransaction();
                throw error;
            }).when(requests).confirmedCounts(anyList());
        }
    }

    private Request remoteRequest() {
        return Request.create(Request.HttpMethod.GET, "http://remote/internal", Map.of(), (byte[]) null, null, null);
    }
}
