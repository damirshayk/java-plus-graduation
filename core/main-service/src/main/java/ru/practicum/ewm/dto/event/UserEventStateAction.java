package ru.practicum.ewm.dto.event;

public enum UserEventStateAction {
    SEND_TO_REVIEW, // Событие отправляется на модерацию (статус PENDING)
    CANCEL_REVIEW   // Событие снимается с публикации (статус CANCELED)
}
