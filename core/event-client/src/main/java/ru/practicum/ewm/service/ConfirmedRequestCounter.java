package ru.practicum.ewm.service;

import java.util.List;
import java.util.Map;

public interface ConfirmedRequestCounter {
    long count(Long eventId);

    Map<Long, Long> countAll(List<Long> eventIds);
}
