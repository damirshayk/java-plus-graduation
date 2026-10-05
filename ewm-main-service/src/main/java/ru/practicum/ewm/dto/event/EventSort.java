package ru.practicum.ewm.dto.event;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum EventSort {
    EVENT_DATE("eventDate"), // Сортировка по дате события
    VIEWS("views");          // Сортировка по количеству просмотров

    private final String property;
}
