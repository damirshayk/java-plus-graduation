package ru.practicum.ewm.service;

import feign.FeignException;
import feign.Request;
import feign.codec.DecodeException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.client.user.UserClient;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.Mockito.*;

class EventDisplayEnrichmentTest {
    private UserClient users;
    private RequestClient requests;
    private EventDisplayEnrichment display;

    @BeforeEach
    void setUp() {
        users = mock(UserClient.class);
        requests = mock(RequestClient.class);
        EventDisplayProperties properties = new EventDisplayProperties();
        properties.getRetry().setBackoffMs(1);
        display = new EventDisplayEnrichment(new UserDirectory(users), new RemoteConfirmedRequestCounter(requests),
                properties);
    }

    @Test
    void retryMayRecoverUserWithoutCountingFailedAttemptAsLogicalFailure() {
        UserShortDto actual = user(1L, "Инициатор");
        when(users.get(1L)).thenThrow(unavailable()).thenReturn(actual);
        assertThat(display.requireUser(1L)).isSameAs(actual);
        verify(users, times(2)).get(1L);
        assertThat(usersCircuit().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(usersCircuit().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void batchesMustDeduplicateEachRetryAndKeepRealIdsInFallback() {
        when(users.batch(List.of(2L, 1L))).thenThrow(unavailable());
        Map<Long, UserShortDto> result = display.findUsers(List.of(2L, 1L, 2L));
        assertThat(result).containsOnlyKeys(2L, 1L);
        assertThat(result.get(2L).getId()).isEqualTo(2L);
        assertThat(result.get(1L).getId()).isEqualTo(1L);
        assertThat(result.values()).extracting(UserShortDto::getName)
                .containsOnly("Имя временно недоступно");
        verify(users, times(2)).batch(List.of(2L, 1L));
    }

    @Test
    void twoLogicalCounterFailuresOpenCircuitAfterFourRemoteAttempts() {
        when(requests.confirmedCounts(List.of(10L, 20L))).thenThrow(unavailable());
        for (int operation = 0; operation < 3; operation++) {
            assertThat(display.confirmedCounts(List.of(10L, 20L, 10L))).containsExactlyInAnyOrderEntriesOf(
                    Map.of(10L, 0L, 20L, 0L));
        }
        verify(requests, times(4)).confirmedCounts(List.of(10L, 20L));
        assertThat(requestsCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(requestsCircuit().getMetrics().getNumberOfFailedCalls()).isEqualTo(2);
    }

    @Test
    void requestCircuitMustNotAffectUserCircuitAndHalfOpenMayRecover() {
        when(requests.confirmedCounts(List.of(10L))).thenThrow(unavailable());
        display.confirmedCounts(List.of(10L));
        display.confirmedCounts(List.of(10L));
        when(users.get(1L)).thenReturn(user(1L, "Инициатор"));
        assertThat(display.requireUser(1L).getName()).isEqualTo("Инициатор");
        assertThat(usersCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        requestsCircuit().transitionToHalfOpenState();
        doReturn(Map.of(10L, 3L)).when(requests).confirmedCounts(List.of(10L));
        assertThat(display.confirmedCounts(List.of(10L))).containsEntry(10L, 3L);
        assertThat(requestsCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void failedHalfOpenUserOperationMustReopenCircuitWithoutAffectingCounters() {
        usersCircuit().transitionToOpenState();
        usersCircuit().transitionToHalfOpenState();
        when(users.get(1L)).thenThrow(unavailable());
        assertThat(display.requireUser(1L).getName()).isEqualTo("Имя временно недоступно");
        assertThat(usersCircuit().getState()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(display.requireUser(1L).getId()).isEqualTo(1L);
        verify(users, times(2)).get(1L);
        when(requests.confirmedCounts(List.of(10L))).thenReturn(Map.of(10L, 2L));
        assertThat(display.confirmedCounts(List.of(10L))).containsEntry(10L, 2L);
        assertThat(requestsCircuit().getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void openCircuitMustNotPerformRemoteCallOrRetryBackoff() {
        EventDisplayProperties properties = new EventDisplayProperties();
        properties.getRetry().setBackoffMs(500);
        display = new EventDisplayEnrichment(new UserDirectory(users), new RemoteConfirmedRequestCounter(requests),
                properties);
        usersCircuit().transitionToOpenState();
        assertTimeoutPreemptively(Duration.ofMillis(200), () ->
                assertThat(display.requireUser(1L).getName()).isEqualTo("Имя временно недоступно"));
        verifyNoInteractions(users, requests);
    }

    @Test
    void missingUsersAndDecodeErrorsMustPropagateWithoutRetryOrCircuitRecord() {
        Request request = request();
        when(users.get(1L)).thenThrow(new FeignException.NotFound("Не найден", request, null, Map.of()));
        assertThatThrownBy(() -> display.requireUser(1L)).isInstanceOf(NotFoundException.class);
        DecodeException decode = new DecodeException(503, "Некорректный JSON", request);
        when(users.get(2L)).thenThrow(decode);
        assertThatThrownBy(() -> display.requireUser(2L)).isSameAs(decode);
        when(users.batch(List.of(3L))).thenReturn(List.of());
        assertThat(display.findUsers(List.of(3L))).isEmpty();
        verify(users).get(1L);
        verify(users).get(2L);
        assertThat(usersCircuit().getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(usersCircuit().getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
    }

    @Test
    void successfulNullUserMustRemainErrorWithoutRetryOrFallback() {
        when(users.get(1L)).thenReturn(null);
        assertThatThrownBy(() -> display.requireUser(1L)).isInstanceOf(NullPointerException.class)
                .hasMessage("Сервис пользователей вернул пустой ответ");
        verify(users).get(1L);
        assertThat(usersCircuit().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void nullAndMalformedSuccessfulCountersMustRemainErrors() {
        when(requests.confirmedCounts(List.of(10L))).thenReturn(null).thenReturn(Map.of(10L, -1L))
                .thenReturn(Map.of(99L, 2L));
        for (int operation = 0; operation < 3; operation++) {
            assertThatThrownBy(() -> display.confirmedCounts(List.of(10L))).isInstanceOf(IllegalStateException.class);
        }
        verify(requests, times(3)).confirmedCounts(List.of(10L));
        assertThat(requestsCircuit().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void counterClientErrorsAndProgrammingErrorsMustNotBeFallback() {
        FeignException badRequest = new FeignException.BadRequest("Ошибка", request(), null, Map.of());
        when(requests.confirmedCounts(List.of(10L))).thenThrow(badRequest);
        assertThatThrownBy(() -> display.confirmedCounts(List.of(10L))).isSameAs(badRequest);
        IllegalArgumentException error = new IllegalArgumentException("Ошибка кода");
        when(requests.confirmedCounts(List.of(20L))).thenThrow(error);
        assertThatThrownBy(() -> display.confirmedCounts(List.of(20L))).isSameAs(error);
        DecodeException decode = new DecodeException(503, "Некорректный JSON", request());
        when(requests.confirmedCounts(List.of(30L))).thenThrow(decode);
        assertThatThrownBy(() -> display.confirmedCounts(List.of(30L))).isSameAs(decode);
        verify(requests).confirmedCounts(List.of(10L));
        verify(requests).confirmedCounts(List.of(20L));
        verify(requests).confirmedCounts(List.of(30L));
        assertThat(requestsCircuit().getMetrics().getNumberOfBufferedCalls()).isZero();
    }

    @Test
    void emptyBatchesMustNotCallRemoteClients() {
        assertThat(display.findUsers(List.of())).isEmpty();
        assertThat(display.confirmedCounts(List.of())).isEmpty();
        verifyNoInteractions(users, requests);
    }

    private CircuitBreaker usersCircuit() {
        return (CircuitBreaker) ReflectionTestUtils.getField(display, "usersCircuitBreaker");
    }

    private CircuitBreaker requestsCircuit() {
        return (CircuitBreaker) ReflectionTestUtils.getField(display, "requestsCircuitBreaker");
    }

    private FeignException unavailable() {
        return new FeignException.ServiceUnavailable("Недоступен", request(), null, Map.of());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://remote/internal", Map.of(), (byte[]) null, null, null);
    }

    private UserShortDto user(Long id, String name) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName(name);
        return user;
    }
}
