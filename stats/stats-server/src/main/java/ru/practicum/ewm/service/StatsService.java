package ru.practicum.ewm.service;

import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.ViewStats;

import java.time.LocalDateTime;
import java.util.List;

public interface StatsService {

    void saveHit(EndpointHitRequestDto hitDto);

    List<ViewStats> getStats(
            LocalDateTime start,
            LocalDateTime end,
            List<String> uris,
            boolean unique
    );
}
