package ru.practicum.ewm.controller.publ;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.dto.event.EventFullDto;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.dto.event.EventSort;
import ru.practicum.ewm.service.EventService;

import java.time.LocalDateTime;
import java.util.List;

@Validated
@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/events")
public class PublicEventController {
    private final EventService eventService;
    private final StatsClient statsClient;

    @GetMapping
    public List<EventShortDto> getEvents(
            @RequestParam(required = false) String text,
            @RequestParam(required = false) List<Long> categories,
            @RequestParam(name = "paid", required = false) Boolean isPaid,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime rangeStart,
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime rangeEnd,
            @RequestParam(name = "onlyAvailable", defaultValue = "false") boolean isOnlyAvailable,
            @RequestParam(required = false) EventSort sort,
            @RequestParam(defaultValue = "0") @PositiveOrZero int from,
            @RequestParam(defaultValue = "10") @Positive int size,
            HttpServletRequest httpRequest) {
        log.info("Публичный поиск событий: from={}, size={}, onlyAvailable={}", from, size, isOnlyAvailable);
        saveHit(httpRequest);
        return eventService.getPublicEvents(text, categories, isPaid, rangeStart, rangeEnd,
                isOnlyAvailable, sort, from, size);
    }

    @GetMapping("/{id}")
    public EventFullDto getEvent(@PathVariable Long id, HttpServletRequest httpRequest) {
        log.info("Получение опубликованного события с id={}", id);
        saveHit(httpRequest);
        return eventService.getPublicEvent(id);
    }

    private void saveHit(HttpServletRequest request) {
        statsClient.hit(EndpointHitRequestDto.builder()
                .app("ewm-main-service")
                .uri(request.getRequestURI())
                .ip(request.getRemoteAddr())
                .timestamp(LocalDateTime.now())
                .build());
    }
}
