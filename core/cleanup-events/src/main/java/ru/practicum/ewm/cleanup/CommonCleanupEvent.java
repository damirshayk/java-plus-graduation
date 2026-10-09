package ru.practicum.ewm.cleanup;

import java.util.List;
import java.util.UUID;

public record CommonCleanupEvent(UUID eventId, CleanupEventType type, Long userId, List<Long> eventIds) {

    public CommonCleanupEvent {
        if (eventId == null || type == null || userId == null || userId <= 0 || eventIds == null) {
            throw new IllegalArgumentException("Событие очистки должно содержать UUID, тип и положительный userId");
        }
        if (eventIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Идентификаторы событий должны быть положительными");
        }
        if (type == CleanupEventType.USER_DELETED && !eventIds.isEmpty()) {
            throw new IllegalArgumentException("USER_DELETED не содержит снимок событий");
        }
        eventIds = eventIds.stream().distinct().sorted().toList();
    }
}
