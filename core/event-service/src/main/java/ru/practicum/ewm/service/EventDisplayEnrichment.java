package ru.practicum.ewm.service;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.stream.Collectors;

@Service
public class EventDisplayEnrichment {
    private final UserDirectory userDirectory;
    private final ConfirmedRequestCounter confirmedRequestCounter;
    private final Retry retry;
    private final CircuitBreaker usersCircuitBreaker;
    private final CircuitBreaker requestsCircuitBreaker;

    public EventDisplayEnrichment(UserDirectory userDirectory, ConfirmedRequestCounter confirmedRequestCounter,
            @Value("${ewm.display.retry.max-attempts:2}") int maxAttempts,
            @Value("${ewm.display.retry.backoff-ms:100}") long backoffMs,
            @Value("${ewm.display.circuit-breaker.sliding-window-size:10}") int windowSize,
            @Value("${ewm.display.circuit-breaker.minimum-number-of-calls:2}") int minimumCalls,
            @Value("${ewm.display.circuit-breaker.failure-rate-threshold:50}") float failureThreshold,
            @Value("${ewm.display.circuit-breaker.wait-duration-in-open-state-ms:5000}") long openDurationMs,
            @Value("${ewm.display.circuit-breaker.permitted-number-of-calls-in-half-open-state:1}") int halfOpenCalls) {
        this.userDirectory = userDirectory;
        this.confirmedRequestCounter = confirmedRequestCounter;
        this.retry = Retry.of("event-display", RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(Duration.ofMillis(backoffMs))
                .retryOnException(exception -> exception instanceof ServiceUnavailableException)
                .build());
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureThreshold)
                .waitDurationInOpenState(Duration.ofMillis(openDurationMs))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .recordException(exception -> exception instanceof ServiceUnavailableException)
                .ignoreException(exception -> !(exception instanceof ServiceUnavailableException))
                .build();
        this.usersCircuitBreaker = CircuitBreaker.of("event-display-users", config);
        this.requestsCircuitBreaker = CircuitBreaker.of("event-display-requests", config);
    }

    public UserShortDto requireUser(Long id) {
        return optional(usersCircuitBreaker, () -> Objects.requireNonNull(userDirectory.require(id),
                "Сервис пользователей вернул пустой ответ"), () -> unavailableUser(id));
    }

    public Map<Long, UserShortDto> findUsers(Collection<Long> userIds) {
        if (userIds.isEmpty()) return Map.of();
        List<Long> ids = userIds.stream().distinct().toList();
        return optional(usersCircuitBreaker, () -> userDirectory.findAll(ids),
                () -> ids.stream().collect(Collectors.toMap(id -> id, this::unavailableUser)));
    }

    public Map<Long, Long> confirmedCounts(List<Long> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        List<Long> ids = eventIds.stream().distinct().toList();
        return optional(requestsCircuitBreaker, () -> confirmedRequestCounter.countAll(ids),
                () -> ids.stream().collect(Collectors.toMap(id -> id, id -> 0L)));
    }

    private <T> T optional(CircuitBreaker circuitBreaker, Supplier<T> operation, Supplier<T> fallback) {
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, Retry.decorateSupplier(retry, operation)).get();
        } catch (ServiceUnavailableException | CallNotPermittedException exception) {
            return fallback.get();
        }
    }

    private UserShortDto unavailableUser(Long id) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName("Имя временно недоступно");
        return user;
    }
}
