package ru.practicum.ewm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
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
    private final DiscoveryClient discoveryClient;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public StatsClient(
            @Value("${stats.service-id:stats-server}") String serviceId,
            DiscoveryClient discoveryClient,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.serviceId = serviceId;
        this.discoveryClient = discoveryClient;
        this.rest = restTemplateBuilder.build();
    }

    // POST /hit - отправка статистики
    public void hit(EndpointHitRequestDto requestDto) {
        URI uri = UriComponentsBuilder.fromUri(resolveServiceUri()).path("/hit").build().toUri();
        rest.postForEntity(uri, requestDto, Void.class);
        log.debug("Статистика отправлена: {}", requestDto);
    }

    // GET /stats - получение статистики
    public List<ViewStats> getStats(
            LocalDateTime start,
            LocalDateTime end,
            List<String> uris,
            Boolean unique
    ) {
        URI uri = buildStatsUri(start, end, uris, unique);

        ResponseEntity<List<ViewStats>> response = rest.exchange(
                uri,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<ViewStats>>() {
                }
        );

        return response.getBody() != null ? response.getBody() : Collections.emptyList();
    }

    private URI resolveServiceUri() {
        List<ServiceInstance> instances = discoveryClient.getInstances(serviceId);
        if (instances.isEmpty()) {
            throw new IllegalStateException("Нет доступных экземпляров сервиса статистики: " + serviceId);
        }
        return instances.get(0).getUri();
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
}
