package ru.practicum.ewm.service;

import ru.practicum.ewm.dto.request.ParticipationRequestDto;
import ru.practicum.ewm.model.EventRequestStatusUpdateRequest;
import ru.practicum.ewm.model.EventRequestStatusUpdateResult;

import java.util.List;

public interface RequestService {
    List<ParticipationRequestDto> getUserRequests(Long userId);

    ParticipationRequestDto addParticipationRequest(Long userId, Long eventId);


    ParticipationRequestDto cancelRequest(Long userId, Long requestId);


    List<ParticipationRequestDto> getEventParticipants(Long userId, Long eventId);


    EventRequestStatusUpdateResult changeRequestStatus(Long userId,
                                                       Long eventId,
                                                       EventRequestStatusUpdateRequest updateRequest);
}

