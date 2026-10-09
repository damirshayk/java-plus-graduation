package ru.practicum.ewm.dto.event;

import ru.practicum.ewm.model.EventState;

public record EventInfoDto(Long id, Long initiatorId, EventState state,
                           Integer participantLimit, Boolean requestModeration) {
}
