package ru.practicum.ewm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.dto.request.ParticipationRequestDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.RequestMapper;
import ru.practicum.ewm.model.*;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.RequestService;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class RequestServiceImpl implements RequestService {

    private final RequestRepository requestRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final RequestMapper requestMapper;

    @Override
    public List<ParticipationRequestDto> getUserRequests(Long userId) {
        checkUserExists(userId);
        List<ParticipationRequest> requests = requestRepository.findAllByRequesterId(userId);
        return requestMapper.toDtoList(requests);
    }

    @Override
    @Transactional
    public ParticipationRequestDto addParticipationRequest(Long userId, Long eventId) {
        User requester = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Пользователь с id=" + userId + " не найден"));
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));

        if (requestRepository.existsByRequesterIdAndEventId(userId, eventId)) {
            throw new ConflictException("Нельзя добавить повторный запрос от пользователя id=" + userId + " на событие id=" + eventId);
        }
        if (event.getInitiator() != null && event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("Инициатор события не может добавить запрос на участие в своём событии");
        }
        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Нельзя участвовать в неопубликованном событии");
        }

        if (event.getParticipantLimit() > 0) {
            long confirmedCount = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
            if (confirmedCount >= event.getParticipantLimit()) {
                throw new ConflictException("Достигнут лимит запросов на участие в событии id=" + eventId);
            }
        }

        RequestStatus initialStatus = RequestStatus.PENDING;
        if (!event.isRequestModeration() || event.getParticipantLimit() == 0) {
            initialStatus = RequestStatus.CONFIRMED;
        }

        ParticipationRequest newRequest = ParticipationRequest.builder()
                .event(event)
                .requester(requester)
                .status(initialStatus)
                .created(LocalDateTime.now())
                .build();
        return requestMapper.toDto(requestRepository.save(newRequest));
    }

    @Override
    @Transactional
    public ParticipationRequestDto cancelRequest(Long userId, Long requestId) {
        checkUserExists(userId);
        ParticipationRequest request = requestRepository.findById(requestId)
                .orElseThrow(() -> new NotFoundException("Запрос на участие с id=" + requestId + " не найден"));

        if (!request.getRequester().getId().equals(userId)) {
            throw new ConflictException("Пользователь id=" + userId + " не является автором заявки id=" + requestId);
        }

        request.setStatus(RequestStatus.CANCELED);
        return requestMapper.toDto(requestRepository.save(request));
    }

    @Override
    public List<ParticipationRequestDto> getEventParticipants(Long userId, Long eventId) {
        checkUserExists(userId);
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));

        if (!event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("Пользователь id=" + userId + " не является инициатором события id=" + eventId);
        }

        List<ParticipationRequest> requests = requestRepository.findAllByEventId(eventId);
        return requestMapper.toDtoList(requests);
    }

    @Override
    @Transactional
    public EventRequestStatusUpdateResult changeRequestStatus(Long userId,
                                                              Long eventId,
                                                              EventRequestStatusUpdateRequest updateRequest) {
        checkUserExists(userId);
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));

        if (!event.getInitiator().getId().equals(userId)) {
            throw new ConflictException("Пользователь id=" + userId + " не является инициатором события id=" + eventId);
        }

        List<ParticipationRequest> requests = requestRepository.findAllByIdIn(updateRequest.getRequestIds());

        List<ParticipationRequest> confirmedRequests = new ArrayList<>();
        List<ParticipationRequest> rejectedRequests = new ArrayList<>();

        if (event.getParticipantLimit() == 0 || !event.isRequestModeration()) {
            return EventRequestStatusUpdateResult.builder()
                    .confirmedRequests(requestMapper.toDtoList(requests))
                    .rejectedRequests(List.of())
                    .build();
        }

        long confirmedCount = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        if (confirmedCount >= event.getParticipantLimit() && updateRequest.getStatus() == RequestUpdateStatus.CONFIRMED) {
            throw new ConflictException("Нельзя подтвердить заявки, так как лимит участников уже исчерпан");
        }

        for (ParticipationRequest request : requests) {
            if (request.getStatus() != RequestStatus.PENDING) {
                throw new ConflictException("Статус можно изменить только у заявок, находящихся в состоянии ожидания (PENDING)");
            }

            if (updateRequest.getStatus() == RequestUpdateStatus.REJECTED) {
                request.setStatus(RequestStatus.REJECTED);
                rejectedRequests.add(request);
            } else {
                if (confirmedCount < event.getParticipantLimit()) {
                    request.setStatus(RequestStatus.CONFIRMED);
                    confirmedRequests.add(request);
                    confirmedCount++;
                } else {
                    request.setStatus(RequestStatus.REJECTED);
                    rejectedRequests.add(request);
                }
            }
        }

        requestRepository.saveAll(requests);

        return EventRequestStatusUpdateResult.builder()
                .confirmedRequests(requestMapper.toDtoList(confirmedRequests))
                .rejectedRequests(requestMapper.toDtoList(rejectedRequests))
                .build();
    }

    private void checkUserExists(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("Пользователь с id=" + userId + " не найден");
        }
    }
}