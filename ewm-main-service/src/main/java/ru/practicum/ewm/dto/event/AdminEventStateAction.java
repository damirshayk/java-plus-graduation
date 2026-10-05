package ru.practicum.ewm.dto.event;

public enum AdminEventStateAction {
    PUBLISH_EVENT,  // Событие должно быть опубликовано (статус PUBLISHED)
    REJECT_EVENT    // Событие должно быть отклонено (статус CANCELED)
}
