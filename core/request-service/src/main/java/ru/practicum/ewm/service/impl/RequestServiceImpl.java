package ru.practicum.ewm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.client.event.EventDirectory;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.request.ParticipationRequestDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.mapper.RequestMapper;
import ru.practicum.ewm.model.*;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.service.RequestService;
import ru.practicum.ewm.service.RequestDataGuard;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.proto.ActionTypeProto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class RequestServiceImpl implements RequestService {

    private final RequestRepository requestRepository;
    private final UserDirectory userDirectory;
    private final EventDirectory eventDirectory;
    private final RequestMapper requestMapper;
    private final RequestDataGuard requestDataGuard;
    private final TransactionTemplate localTransaction;
    private final CollectorClient collectorClient;

    public RequestServiceImpl(RequestRepository requestRepository,
                              UserDirectory userDirectory,
                              EventDirectory eventDirectory,
                              RequestMapper requestMapper,
                              RequestDataGuard requestDataGuard,
                              PlatformTransactionManager transactionManager,
                              CollectorClient collectorClient) {
        this.requestRepository = requestRepository;
        this.userDirectory = userDirectory;
        this.eventDirectory = eventDirectory;
        this.requestMapper = requestMapper;
        this.requestDataGuard = requestDataGuard;
        this.localTransaction = new TransactionTemplate(transactionManager);
        this.collectorClient = collectorClient;
    }

    @Override
    public List<ParticipationRequestDto> getUserRequests(Long userId) {
        ServiceUnavailableException unavailable = null;
        try {
            requireReadUser(userId);
        } catch (ServiceUnavailableException exception) {
            unavailable = exception;
        }
        List<ParticipationRequest> requests = requestRepository.findAllByRequesterId(userId);
        if (unavailable != null && requests.isEmpty()) {
            throw unavailable;
        }
        return requestMapper.toDtoList(requests);
    }

    @Override
    public ParticipationRequestDto addParticipationRequest(Long userId, Long eventId) {
        userDirectory.require(userId);
        EventInfoDto event = requireEvent(eventId);
        ParticipationRequestDto result = localTransaction.execute(transaction -> {
            requestDataGuard.lockForWrite(userId, eventId);

            if (requestRepository.existsByRequesterIdAndEventId(userId, eventId)) {
                throw new ConflictException("Нельзя добавить повторный запрос от пользователя id="
                        + userId + " на событие id=" + eventId);
            }
            if (userId.equals(event.initiatorId())) {
                throw new ConflictException("Инициатор события не может добавить запрос на участие в своём событии");
            }
            if (event.state() != EventState.PUBLISHED) {
                throw new ConflictException("Нельзя участвовать в неопубликованном событии");
            }

            if (event.participantLimit() > 0) {
                long confirmedCount = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
                if (confirmedCount >= event.participantLimit()) {
                    throw new ConflictException("Достигнут лимит запросов на участие в событии id=" + eventId);
                }
            }

            RequestStatus initialStatus = RequestStatus.PENDING;
            if (!event.requestModeration() || event.participantLimit() == 0) {
                initialStatus = RequestStatus.CONFIRMED;
            }

            ParticipationRequest newRequest = ParticipationRequest.builder()
                    .eventId(eventId)
                    .requesterId(userId)
                    .status(initialStatus)
                    .created(LocalDateTime.now())
                    .build();
            return requestMapper.toDto(requestRepository.save(newRequest));
        });
        collectorClient.collect(userId, eventId, ActionTypeProto.ACTION_REGISTER);
        return result;
    }

    @Override
    public ParticipationRequestDto cancelRequest(Long userId, Long requestId) {
        userDirectory.require(userId);
        return localTransaction.execute(transaction -> {
            Long eventId = requestRepository.findEventIdByRequestId(requestId)
                    .orElseThrow(() -> new NotFoundException("Запрос на участие с id=" + requestId + " не найден"));
            requestDataGuard.lockForWrite(userId, eventId);
            ParticipationRequest request = requestRepository.findById(requestId)
                    .orElseThrow(() -> new NotFoundException("Запрос на участие с id=" + requestId + " не найден"));

            if (!request.getRequesterId().equals(userId)) {
                throw new ConflictException("Пользователь id=" + userId + " не является автором заявки id=" + requestId);
            }

            request.setStatus(RequestStatus.CANCELED);
            return requestMapper.toDto(requestRepository.save(request));
        });
    }

    @Override
    public List<ParticipationRequestDto> getEventParticipants(Long userId, Long eventId) {
        try {
            requireReadUser(userId);
        } catch (ServiceUnavailableException exception) {
            log.warn("Сервис пользователей недоступен; права чтения заявок события id={} проверяются по инициатору",
                    eventId);
        }
        EventInfoDto event = requireEvent(eventId);
        requireInitiator(userId, event);

        List<ParticipationRequest> requests = requestRepository.findAllByEventId(eventId);
        return requestMapper.toDtoList(requests);
    }

    @Override
    public EventRequestStatusUpdateResult changeRequestStatus(Long userId,
                                                              Long eventId,
                                                              EventRequestStatusUpdateRequest updateRequest) {
        userDirectory.require(userId);
        EventInfoDto event = requireEvent(eventId);
        requireInitiator(userId, event);
        return localTransaction.execute(transaction -> {
            requestDataGuard.lockForWrite(userId, eventId);
            List<Long> requestIds = updateRequest.getRequestIds().stream().distinct().sorted().toList();
            List<ParticipationRequest> requests = requestRepository.findAllByIdIn(requestIds);
            if (requests.size() != requestIds.size()
                    || requests.stream().anyMatch(request -> !eventId.equals(request.getEventId()))) {
                throw new NotFoundException("Не все указанные заявки принадлежат событию id=" + eventId);
            }

            List<ParticipationRequest> confirmedRequests = new ArrayList<>();
            List<ParticipationRequest> rejectedRequests = new ArrayList<>();

            if (event.participantLimit() == 0 || !event.requestModeration()) {
                return EventRequestStatusUpdateResult.builder()
                        .confirmedRequests(requestMapper.toDtoList(requests))
                        .rejectedRequests(List.of())
                        .build();
            }

            long confirmedCount = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
            if (confirmedCount >= event.participantLimit()
                    && updateRequest.getStatus() == RequestUpdateStatus.CONFIRMED) {
                throw new ConflictException("Нельзя подтвердить заявки, так как лимит участников уже исчерпан");
            }
            if (requests.stream().anyMatch(request -> request.getStatus() != RequestStatus.PENDING)) {
                throw new ConflictException("Статус можно изменить только у заявок, находящихся в состоянии ожидания (PENDING)");
            }

            for (ParticipationRequest request : requests) {
                if (updateRequest.getStatus() == RequestUpdateStatus.REJECTED) {
                    request.setStatus(RequestStatus.REJECTED);
                    rejectedRequests.add(request);
                } else if (confirmedCount < event.participantLimit()) {
                    request.setStatus(RequestStatus.CONFIRMED);
                    confirmedRequests.add(request);
                    confirmedCount++;
                } else {
                    request.setStatus(RequestStatus.REJECTED);
                    rejectedRequests.add(request);
                }
            }

            requestRepository.saveAll(requests);
            return EventRequestStatusUpdateResult.builder()
                    .confirmedRequests(requestMapper.toDtoList(confirmedRequests))
                    .rejectedRequests(requestMapper.toDtoList(rejectedRequests))
                    .build();
        });
    }

    private void requireReadUser(Long userId) {
        UserShortDto user = userDirectory.require(userId);
        if (user == null || user.getId() == null || !userId.equals(user.getId()) || user.getId() <= 0
                || user.getName() == null) {
            throw new IllegalStateException("Сервис пользователей вернул некорректные сведения о пользователе id=" + userId);
        }
    }

    private EventInfoDto requireEvent(Long eventId) {
        EventInfoDto event = eventDirectory.require(eventId);
        if (event == null || event.id() == null || !eventId.equals(event.id()) || event.id() <= 0
                || event.initiatorId() == null || event.initiatorId() <= 0 || event.state() == null
                || event.participantLimit() == null || event.participantLimit() < 0
                || event.requestModeration() == null) {
            throw new IllegalStateException("Сервис событий вернул некорректные сведения о событии id=" + eventId);
        }
        return event;
    }

    private void requireInitiator(Long userId, EventInfoDto event) {
        if (!userId.equals(event.initiatorId())) {
            throw new ConflictException("Пользователь id=" + userId
                    + " не является инициатором события id=" + event.id());
        }
    }
}
