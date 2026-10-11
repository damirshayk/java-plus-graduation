package ru.practicum.ewm.dto.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventSort {
    EVENT_DATE("eventDate"),
    RATING("rating"),
    VIEWS("rating");

    private final String property;
}
