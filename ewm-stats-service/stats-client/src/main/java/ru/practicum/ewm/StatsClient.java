package ru.practicum.ewm;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class StatsClient {

    private final RestTemplate rest;
    private final String serverUrl;
    private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public StatsClient(
            @Value("${stats-server.url:http://localhost:9090}") String serverUrl,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this.serverUrl = serverUrl;
        this.rest = restTemplateBuilder.build();
    }

    // POST /hit - отправка статистики
    public void hit(EndpointHitRequestDto requestDto) {
        String url = serverUrl + "/hit";
        rest.postForEntity(url, requestDto, Void.class);
        log.debug("Статистика отправлена: {}", requestDto);
    }

    // GET /stats - получение статистики
    public List<ViewStats> getStats(
            LocalDateTime start,
            LocalDateTime end,
            List<String> uris,
            Boolean unique
    ) {
        String url = buildUrl(start, end, uris, unique);

        ResponseEntity<List<ViewStats>> response = rest.exchange(
                url,
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<ViewStats>>() {
                }
        );

        return response.getBody() != null ? response.getBody() : Collections.emptyList();
    }

    // Вспомогательный метод для построения URL
    private String buildUrl(LocalDateTime start, LocalDateTime end, List<String> uris, Boolean unique) {
        var builder = UriComponentsBuilder
                .fromHttpUrl(serverUrl + "/stats")
                .queryParam("start", start.format(FORMATTER))
                .queryParam("end", end.format(FORMATTER));

        if (uris != null && !uris.isEmpty()) {
            builder.queryParam("uris", String.join(",", uris));
        }

        if (unique != null) {
            builder.queryParam("unique", unique);
        }

        return builder.build().toUriString();
    }
}