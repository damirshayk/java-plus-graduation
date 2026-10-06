package ru.practicum.ewm.service;

import ru.practicum.ewm.dto.event.*;
import ru.practicum.ewm.model.EventState;

import java.util.List;

public interface EventService {
    EventFullDto createEvent(Long userId, NewEventDto dto);

    List<EventShortDto> getUserEvents(Long userId, int from, int size);

    EventFullDto getUserEvent(Long userId, Long eventId);

    EventFullDto updateUserEvent(Long userId, Long eventId, UpdateEventUserRequest request);

    List<EventFullDto> getAdminEvents(List<Long> users, List<EventState> states, List<Long> categories,
                                      java.time.LocalDateTime rangeStart, java.time.LocalDateTime rangeEnd,
                                      int from, int size);

    EventFullDto updateAdminEvent(Long eventId, UpdateEventAdminRequest request);

    List<EventShortDto> getPublicEvents(String text, List<Long> categories, Boolean isPaid,
                                        java.time.LocalDateTime rangeStart, java.time.LocalDateTime rangeEnd,
                                        boolean isOnlyAvailable, EventSort sort, int from, int size);

    EventFullDto getPublicEvent(Long eventId);
}
