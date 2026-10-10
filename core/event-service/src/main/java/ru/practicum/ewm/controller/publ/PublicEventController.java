package ru.practicum.ewm.controller.publ;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.proto.ActionTypeProto;
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
    private final CollectorClient collectorClient;

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
            @RequestParam(defaultValue = "10") @Positive int size) {
        log.info("Публичный поиск событий: from={}, size={}, onlyAvailable={}", from, size, isOnlyAvailable);
        return eventService.getPublicEvents(text, categories, isPaid, rangeStart, rangeEnd,
                isOnlyAvailable, sort, from, size);
    }

    @GetMapping("/{id}")
    public EventFullDto getEvent(@PathVariable @Positive Long id,
                                @RequestHeader("X-EWM-USER-ID") @Positive Long userId) {
        log.info("Получение опубликованного события с id={}", id);
        EventFullDto event = eventService.getPublicEvent(id);
        collectorClient.collect(userId, id, ActionTypeProto.ACTION_VIEW);
        return event;
    }

    @GetMapping("/recommendations")
    public List<EventShortDto> getRecommendations(@RequestHeader("X-EWM-USER-ID") @Positive Long userId,
                                                  @RequestParam(defaultValue = "10") @Positive int size) {
        return eventService.getRecommendations(userId, size);
    }

    @PutMapping("/{eventId}/like")
    public void like(@PathVariable @Positive Long eventId,
                     @RequestHeader("X-EWM-USER-ID") @Positive Long userId) {
        eventService.validateLike(userId, eventId);
        collectorClient.collect(userId, eventId, ActionTypeProto.ACTION_LIKE);
    }
}
