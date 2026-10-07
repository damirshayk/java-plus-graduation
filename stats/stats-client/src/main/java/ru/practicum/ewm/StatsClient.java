package ru.practicum.ewm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class StatsClient {

    private final RestTemplate rest;
    private final String serviceId;
    private final LoadBalancerClient loadBalancerClient;
    private final RetryTemplate discoveryRetry;
    private final CircuitBreaker circuitBreaker;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public StatsClient(
            @Value("${stats.service-id:stats-server}") String serviceId,
            LoadBalancerClient loadBalancerClient,
            RestTemplateBuilder restTemplateBuilder,
            @Value("${stats.discovery.max-attempts:2}") int discoveryAttempts,
            @Value("${stats.discovery.backoff-ms:100}") long discoveryBackoffMs,
            @Value("${stats.http.connect-timeout-ms:1000}") long connectTimeoutMs,
            @Value("${stats.http.read-timeout-ms:2000}") long readTimeoutMs,
            @Value("${stats.circuit-breaker.sliding-window-size:10}") int windowSize,
            @Value("${stats.circuit-breaker.minimum-number-of-calls:1}") int minimumCalls,
            @Value("${stats.circuit-breaker.failure-rate-threshold:50}") float failureThreshold,
            @Value("${stats.circuit-breaker.wait-duration-in-open-state-ms:5000}") long openDurationMs,
            @Value("${stats.circuit-breaker.permitted-number-of-calls-in-half-open-state:1}") int halfOpenCalls
    ) {
        this.serviceId = serviceId;
        this.loadBalancerClient = loadBalancerClient;
        this.discoveryRetry = RetryTemplate.builder()
                .maxAttempts(discoveryAttempts)
                .fixedBackoff(discoveryBackoffMs)
                .retryOn(NoStatsInstanceException.class)
                .build();
        this.rest = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(connectTimeoutMs))
                .setReadTimeout(Duration.ofMillis(readTimeoutMs))
                .build();
        this.circuitBreaker = CircuitBreaker.of("stats", CircuitBreakerConfig.custom()
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureThreshold)
                .waitDurationInOpenState(Duration.ofMillis(openDurationMs))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .recordException(this::isUnavailable)
                .ignoreException(exception -> !isUnavailable(exception))
                .build());
    }

    // POST /hit - отправка статистики
    public void hit(EndpointHitRequestDto requestDto) {
        try {
            CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
                URI uri = UriComponentsBuilder.fromUri(resolveServiceUri()).path("/hit").build().toUri();
                rest.postForEntity(uri, requestDto, Void.class);
                log.debug("Статистика отправлена: {}", requestDto);
                return null;
            }).get();
        } catch (NoStatsInstanceException | ResourceAccessException | HttpServerErrorException
                 | CallNotPermittedException exception) {
            logUnavailable("POST /hit", exception);
        }
    }

    // GET /stats - получение статистики
    public List<ViewStats> getStats(
            LocalDateTime start,
            LocalDateTime end,
            List<String> uris,
            Boolean unique
    ) {
        try {
            return CircuitBreaker.decorateSupplier(circuitBreaker, () -> {
                URI uri = buildStatsUri(start, end, uris, unique);

                ResponseEntity<List<ViewStats>> response = rest.exchange(
                        uri,
                        HttpMethod.GET,
                        null,
                        new ParameterizedTypeReference<List<ViewStats>>() {
                        }
                );

                return response.getBody() != null ? response.getBody() : Collections.<ViewStats>emptyList();
            }).get();
        } catch (NoStatsInstanceException | ResourceAccessException | HttpServerErrorException
                 | CallNotPermittedException exception) {
            logUnavailable("GET /stats", exception);
            return Collections.emptyList();
        }
    }

    private boolean isUnavailable(Throwable exception) {
        return exception instanceof NoStatsInstanceException || exception instanceof ResourceAccessException
                || exception instanceof HttpServerErrorException;
    }

    private URI resolveServiceUri() {
        return discoveryRetry.execute(context -> {
            ServiceInstance instance = loadBalancerClient.choose(serviceId);
            if (instance == null) {
                throw new NoStatsInstanceException(serviceId);
            }
            return instance.getUri();
        });
    }

    private void logUnavailable(String operation, RuntimeException exception) {
        String reason = exception instanceof HttpServerErrorException httpException
                ? "HTTP " + httpException.getStatusCode().value()
                : exception.getClass().getSimpleName();
        log.warn("Сервис статистики {} недоступен. Операция {} пропущена: {}", serviceId, operation, reason);
    }

    private URI buildStatsUri(LocalDateTime start, LocalDateTime end, List<String> uris, Boolean unique) {
        var builder = UriComponentsBuilder
                .fromUri(resolveServiceUri())
                .path("/stats")
                .queryParam("start", "{start}")
                .queryParam("end", "{end}");
        Map<String, Object> parameters = new HashMap<>();
        parameters.put("start", start.format(FORMATTER));
        parameters.put("end", end.format(FORMATTER));

        if (uris != null && !uris.isEmpty()) {
            builder.queryParam("uris", "{uris}");
            parameters.put("uris", String.join(",", uris));
        }

        if (unique != null) {
            builder.queryParam("unique", unique);
        }

        return builder.encode().buildAndExpand(parameters).toUri();
    }

    private static final class NoStatsInstanceException extends RuntimeException {

        private NoStatsInstanceException(String serviceId) {
            super("Нет доступных экземпляров сервиса статистики: " + serviceId);
        }
    }
}
