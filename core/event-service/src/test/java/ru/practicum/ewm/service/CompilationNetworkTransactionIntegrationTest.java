package ru.practicum.ewm.service;

import feign.FeignException;
import feign.Request;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

@SpringBootTest(classes = EwmEventServiceApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:compilation-network-boundary;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
        "ewm.events.min-start-delay-hours=2", "ewm.display.retry.backoff-ms=1"
})
class CompilationNetworkTransactionIntegrationTest {
    @Autowired
    private CompilationService service;
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
        jdbc.update("DELETE FROM compilations_events");
        jdbc.update("DELETE FROM compilations");
        jdbc.update("DELETE FROM events");
        jdbc.update("DELETE FROM categories");
        jdbc.update("INSERT INTO categories(id, name) VALUES (1, 'Категория')");
        jdbc.update("""
                INSERT INTO events(id, annotation, category_id, created_on, description, event_date, initiator_id,
                                   location_lat, location_lon, paid, participant_limit, request_moderation, state, title)
                VALUES (10, 'Описание', 1, CURRENT_TIMESTAMP, 'Описание', DATEADD('DAY', 3, CURRENT_TIMESTAMP), 1,
                        1, 1, false, 1, true, 'PENDING', 'Событие')
                """);
        jdbc.update("INSERT INTO compilations(id, title, is_pinned) VALUES (100, 'Исходная', false)");
        jdbc.update("INSERT INTO compilations_events(compilation_id, event_id) VALUES (100, 10)");
        when(users.batch(anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return List.of(user());
        });
    }

    @Test
    void createMustEnrichOutsideOwnAndCallerTransaction() {
        assertThat(service.create(newCompilation("Первая")).getEvents()).hasSize(1);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(service.create(newCompilation("Вторая")).getEvents()).hasSize(1));
        assertThat(countCompilations()).isEqualTo(3);
    }

    @Test
    void updateMustEnrichOutsideOwnAndCallerTransaction() {
        assertThat(service.update(100L, patch("Первая")).getTitle()).isEqualTo("Первая");
        new TransactionTemplate(transactionManager).executeWithoutResult(tx ->
                assertThat(service.update(100L, patch("Вторая")).getTitle()).isEqualTo("Вторая"));
        assertThat(title()).isEqualTo("Вторая");
    }

    @Test
    void readsMustSuspendCallerTransactionBeforeOneUserBatch() {
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            assertThat(service.getCompilation(100L).getEvents()).hasSize(1);
            assertThat(service.getCompilations(null, 0, 10)).hasSize(1);
        });
        verify(users, times(2)).batch(List.of(1L));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void readsMustDisplayUnavailableUserWithoutChangingCompilation(boolean list) {
        when(users.batch(anyList())).thenAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            throw unavailable();
        });
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            var result = list ? service.getCompilations(null, 0, 10).getFirst() : service.getCompilation(100L);
            assertThat(result.getEvents()).hasSize(1);
            var event = result.getEvents().iterator().next();
            assertThat(event.getId()).isEqualTo(10L);
            assertThat(event.getInitiator().getId()).isEqualTo(1L);
            assertThat(event.getInitiator().getName()).isEqualTo("Имя временно недоступно");
            assertThat(event.getConfirmedRequests()).isZero();
            assertThat(event.getViews()).isZero();
        });
        assertThat(title()).isEqualTo("Исходная");
        assertThat(countCompilations()).isEqualTo(1);
        verifyNoInteractions(requests, stats);
    }

    @Test
    void unavailableUsersMustNotCreateCompilation() {
        when(users.batch(anyList())).thenThrow(unavailable());
        assertThatThrownBy(() -> service.create(newCompilation("Не сохранять")))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(countCompilations()).isEqualTo(1);
    }

    @Test
    void unavailableUsersMustNotApplyPatch() {
        when(users.batch(anyList())).thenThrow(unavailable());
        assertThatThrownBy(() -> service.update(100L, patch("Не сохранять")))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(title()).isEqualTo("Исходная");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations_events", Long.class)).isEqualTo(1);
    }

    @Test
    void changedMembershipDuringRemoteCallMustRejectNullEventsPatch() {
        when(users.batch(anyList())).thenAnswer(invocation -> {
            jdbc.update("DELETE FROM compilations_events WHERE compilation_id = 100");
            return List.of(user());
        });
        assertThatThrownBy(() -> service.update(100L, patch("Не сохранять")))
                .isInstanceOf(ConflictException.class);
        assertThat(title()).isEqualTo("Исходная");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM compilations_events", Long.class)).isZero();
    }

    @Test
    void vanishedInitiallyExistingEventMustRejectCreate() {
        when(users.batch(anyList())).thenAnswer(invocation -> {
            jdbc.update("DELETE FROM events WHERE id = 10");
            return List.of(user());
        });
        assertThatThrownBy(() -> service.create(newCompilation("Не сохранять")))
                .isInstanceOf(NotFoundException.class);
        assertThat(countCompilations()).isEqualTo(1);
    }

    @Test
    void createMustIgnoreInitiallyUnknownIdsAndDefaultPinnedToFalse() {
        NewCompilationDto dto = newCompilation("Новая");
        dto.setEvents(Set.of(10L, 99L));
        assertThat(service.create(dto).getEvents()).extracting("id").containsExactly(10L);
        assertThat(jdbc.queryForObject("SELECT is_pinned FROM compilations WHERE title = 'Новая'", Boolean.class))
                .isFalse();
        verify(users).batch(List.of(1L));
    }

    @Test
    void updateMustRejectUnknownIdsBeforeCallingUsers() {
        UpdateCompilationRequest dto = patch("Не сохранять");
        dto.setEvents(Set.of(10L, 99L));
        assertThatThrownBy(() -> service.update(100L, dto)).isInstanceOf(NotFoundException.class);
        assertThat(title()).isEqualTo("Исходная");
        verifyNoInteractions(users);
    }

    private NewCompilationDto newCompilation(String title) {
        return NewCompilationDto.builder().title(title).events(Set.of(10L)).build();
    }

    private UpdateCompilationRequest patch(String title) {
        UpdateCompilationRequest patch = new UpdateCompilationRequest();
        patch.setTitle(title);
        return patch;
    }

    private UserShortDto user() {
        UserShortDto user = new UserShortDto();
        user.setId(1L);
        user.setName("Инициатор");
        return user;
    }

    private FeignException unavailable() {
        Request request = Request.create(Request.HttpMethod.POST, "http://user-service/internal/users/batch",
                Map.of(), (byte[]) null, null, null);
        return new FeignException.ServiceUnavailable("Недоступен", request, null, Map.of());
    }

    private long countCompilations() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM compilations", Long.class);
    }

    private String title() {
        return jdbc.queryForObject("SELECT title FROM compilations WHERE id = 100", String.class);
    }
}
