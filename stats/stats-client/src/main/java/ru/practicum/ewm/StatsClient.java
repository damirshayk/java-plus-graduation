package ru.practicum.ewm;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
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
            StatsClientProperties properties,
            LoadBalancerClient loadBalancerClient,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.serviceId = properties.getServiceId();
        this.loadBalancerClient = loadBalancerClient;
        StatsClientProperties.Discovery discovery = properties.getDiscovery();
        StatsClientProperties.Http http = properties.getHttp();
        StatsClientProperties.CircuitBreaker protection = properties.getCircuitBreaker();
        this.discoveryRetry = RetryTemplate.builder()
                .maxAttempts(discovery.getMaxAttempts())
                .fixedBackoff(discovery.getBackoffMs())
                .retryOn(NoStatsInstanceException.class)
                .build();
        this.rest = restTemplateBuilder
                .setConnectTimeout(Duration.ofMillis(http.getConnectTimeoutMs()))
                .setReadTimeout(Duration.ofMillis(http.getReadTimeoutMs()))
                .build();
        this.circuitBreaker = CircuitBreaker.of("stats", CircuitBreakerConfig.custom()
                .slidingWindowSize(protection.getSlidingWindowSize())
                .minimumNumberOfCalls(protection.getMinimumNumberOfCalls())
                .failureRateThreshold(protection.getFailureRateThreshold())
                .waitDurationInOpenState(Duration.ofMillis(protection.getWaitDurationInOpenStateMs()))
                .permittedNumberOfCallsInHalfOpenState(protection.getPermittedNumberOfCallsInHalfOpenState())
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
