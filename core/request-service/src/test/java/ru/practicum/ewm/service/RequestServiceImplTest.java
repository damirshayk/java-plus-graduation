package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.dto.request.ParticipationRequestDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.mapper.RequestMapper;
import ru.practicum.ewm.model.*;
import ru.practicum.ewm.client.event.EventDirectory;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.service.impl.RequestServiceImpl;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.proto.ActionTypeProto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RequestServiceImplTest {
    @Mock
    private RequestRepository requestRepository;
    @Mock
    private UserDirectory userDirectory;
    @Mock
    private EventDirectory eventDirectory;
    @Mock
    private RequestMapper requestMapper;

    @Mock
    private RequestDataGuard requestDataGuard;
    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private CollectorClient collectorClient;

    private RequestServiceImpl requestService;

    private UserShortDto requester;
    private UserShortDto initiator;
    private EventInfoDto event;

    @BeforeEach
    void setUp() {
        requester = user(1L, "Участник");
        initiator = user(2L, "Инициатор");
        requestService = new RequestServiceImpl(requestRepository, userDirectory, eventDirectory,
                requestMapper, requestDataGuard, transactionManager, collectorClient);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());

        event = new EventInfoDto(10L, initiator.getId(), EventState.PUBLISHED, 10, true);
    }

    @Test
    void addParticipationRequest_Success_Pending() {
        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(false);
        when(requestRepository.countByEventIdAndStatus(10L, RequestStatus.CONFIRMED)).thenReturn(5L);

        ParticipationRequest savedRequest = ParticipationRequest.builder()
                .eventId(event.id()).requesterId(requester.getId()).status(RequestStatus.PENDING).build();

        LocalDateTime now = LocalDateTime.now();

        ParticipationRequestDto expectedDto = ParticipationRequestDto.builder()
                .id(100L)
                .event(10L)
                .requester(1L)
                .status("PENDING")
                .created(now)
                .build();

        when(requestRepository.save(any(ParticipationRequest.class))).thenReturn(savedRequest);
        when(requestMapper.toDto(any(ParticipationRequest.class))).thenReturn(expectedDto);

        ParticipationRequestDto result = requestService.addParticipationRequest(1L, 10L);

        assertNotNull(result);
        assertEquals("PENDING", result.getStatus());
        var publicationOrder = inOrder(transactionManager, collectorClient);
        publicationOrder.verify(transactionManager).commit(any());
        publicationOrder.verify(collectorClient).collect(1L, 10L, ActionTypeProto.ACTION_REGISTER);
        verify(requestRepository, times(1)).save(any(ParticipationRequest.class));
    }

    @Test
    void addParticipationRequest_Success_AutoConfirmed_WhenNoModeration() {
        event = new EventInfoDto(10L, initiator.getId(), EventState.PUBLISHED, 10, false);

        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(false);

        LocalDateTime testDateTime = LocalDateTime.now();

        ParticipationRequestDto expectedDto = ParticipationRequestDto.builder()
                .id(100L)
                .event(10L)
                .requester(1L)
                .status("CONFIRMED")
                .created(testDateTime)
                .build();

        when(requestRepository.save(any(ParticipationRequest.class)))
                .thenAnswer(invocation -> invocation.getArgument(0, ParticipationRequest.class));
        when(requestMapper.toDto(any(ParticipationRequest.class))).thenReturn(expectedDto);

        ParticipationRequestDto result = requestService.addParticipationRequest(1L, 10L);

        assertNotNull(result);
        assertEquals(100L, result.getId());
        assertEquals(10L, result.getEvent());
        assertEquals(1L, result.getRequester());
        assertEquals("CONFIRMED", result.getStatus());
        assertEquals(testDateTime, result.getCreated());

        verify(requestRepository, times(1)).save(any(ParticipationRequest.class));
    }

    @Test
    void addParticipationRequest_ThrowNotFound_WhenUserMissing() {
        when(userDirectory.require(1L)).thenThrow(new NotFoundException("Пользователь не найден"));

        assertThrows(NotFoundException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenDuplicateRequest() {
        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenRequesterIsInitiator() {
        when(userDirectory.require(2L)).thenReturn(initiator);
        when(eventDirectory.require(10L)).thenReturn(event);

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(2L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenEventNotPublished() {
        event = new EventInfoDto(10L, initiator.getId(), EventState.PENDING, 10, true);

        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenLimitReached() {
        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(false);
        when(requestRepository.countByEventIdAndStatus(10L, RequestStatus.CONFIRMED)).thenReturn(10L); // Лимит достигнут

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void cancelRequest_Success() {
        ParticipationRequest activeRequest = ParticipationRequest.builder()
                .id(100L).eventId(event.id()).requesterId(requester.getId()).status(RequestStatus.PENDING).build();
        ParticipationRequestDto canceledDto = ParticipationRequestDto.builder()
                .id(100L).status("CANCELED").build();

        when(userDirectory.require(1L)).thenReturn(requester);
        when(requestRepository.findEventIdByRequestId(100L)).thenReturn(Optional.of(10L));
        when(requestRepository.findById(100L)).thenReturn(Optional.of(activeRequest));
        when(requestRepository.save(any(ParticipationRequest.class))).thenAnswer(i -> i.getArguments()[0]);
        when(requestMapper.toDto(any(ParticipationRequest.class))).thenReturn(canceledDto);

        ParticipationRequestDto result = requestService.cancelRequest(1L, 100L);

        assertNotNull(result);
        assertEquals("CANCELED", result.getStatus());
    }

    @Test
    void cancelRequest_ThrowConflict_WhenNotOwner() {
        ParticipationRequest activeRequest = ParticipationRequest.builder()
                .id(100L).eventId(event.id()).requesterId(initiator.getId()).status(RequestStatus.PENDING).build();

        when(userDirectory.require(1L)).thenReturn(requester);
        when(requestRepository.findEventIdByRequestId(100L)).thenReturn(Optional.of(10L));
        when(requestRepository.findById(100L)).thenReturn(Optional.of(activeRequest));

        assertThrows(ConflictException.class, () -> requestService.cancelRequest(1L, 100L));
    }

    @Test
    void changeRequestStatusShouldRejectForeignEventBeforeChangingAnyRequest() {
        Long anotherEventId = 20L;
        ParticipationRequest ownRequest = ParticipationRequest.builder()
                .id(100L).eventId(event.id()).requesterId(requester.getId()).status(RequestStatus.PENDING).build();
        ParticipationRequest foreignRequest = ParticipationRequest.builder()
                .id(200L).eventId(anotherEventId).requesterId(requester.getId()).status(RequestStatus.PENDING).build();
        EventRequestStatusUpdateRequest update = new EventRequestStatusUpdateRequest();
        update.setRequestIds(List.of(100L, 200L));
        update.setStatus(RequestUpdateStatus.CONFIRMED);
        when(userDirectory.require(2L)).thenReturn(initiator);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.findAllByIdIn(update.getRequestIds())).thenReturn(List.of(ownRequest, foreignRequest));

        assertThrows(NotFoundException.class, () -> requestService.changeRequestStatus(2L, 10L, update));

        assertEquals(RequestStatus.PENDING, ownRequest.getStatus());
        assertEquals(RequestStatus.PENDING, foreignRequest.getStatus());
        verify(requestRepository, never()).saveAll(any());
    }

    @Test
    void changeRequestStatusShouldRejectMissingIdBeforeChangingAnyRequest() {
        ParticipationRequest ownRequest = ParticipationRequest.builder()
                .id(100L).eventId(event.id()).requesterId(requester.getId()).status(RequestStatus.PENDING).build();
        EventRequestStatusUpdateRequest update = new EventRequestStatusUpdateRequest();
        update.setRequestIds(List.of(100L, 999L));
        update.setStatus(RequestUpdateStatus.REJECTED);
        when(userDirectory.require(2L)).thenReturn(initiator);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.findAllByIdIn(update.getRequestIds())).thenReturn(List.of(ownRequest));

        assertThrows(NotFoundException.class, () -> requestService.changeRequestStatus(2L, 10L, update));

        assertEquals(RequestStatus.PENDING, ownRequest.getStatus());
        verify(requestRepository, never()).saveAll(any());
    }

    @Test
    void createShouldCheckRemoteUserBeforeStartingLocalTransaction() {
        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);
        when(requestRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        requestService.addParticipationRequest(1L, 10L);

        var order = inOrder(userDirectory, transactionManager, requestDataGuard, eventDirectory,
                requestRepository, requestMapper);
        order.verify(userDirectory).require(1L);
        order.verify(eventDirectory).require(10L);
        order.verify(transactionManager).getTransaction(any());
        order.verify(requestDataGuard).lockForWrite(1L, 10L);
        order.verify(requestRepository).existsByRequesterIdAndEventId(1L, 10L);
        order.verify(requestRepository).countByEventIdAndStatus(10L, RequestStatus.CONFIRMED);
        order.verify(requestRepository).save(argThat(request -> request.getRequesterId().equals(1L)
                && request.getStatus() == RequestStatus.PENDING));
        order.verify(requestMapper).toDto(any());
        order.verify(transactionManager).commit(any());
    }

    @Test
    void createShouldRejectDeletingUserBeforeSavingRequest() {
        when(userDirectory.require(1L)).thenReturn(requester);
        when(eventDirectory.require(10L)).thenReturn(event);
        doThrow(new NotFoundException("Пользователь удаляется")).when(requestDataGuard).lockForWrite(1L, 10L);

        assertThrows(NotFoundException.class, () -> requestService.addParticipationRequest(1L, 10L));

        verify(eventDirectory).require(10L);
        verifyNoInteractions(requestRepository, requestMapper);
        verify(transactionManager).rollback(any());
    }

    @Test
    void ownRequestsShouldUseNonemptyLocalRowsWhenUserIsTemporarilyUnavailable() {
        ServiceUnavailableException unavailable = new ServiceUnavailableException("Сервис пользователей недоступен");
        ParticipationRequest request = ParticipationRequest.builder()
                .id(100L).requesterId(1L).eventId(10L).status(RequestStatus.PENDING).build();
        ParticipationRequestDto dto = ParticipationRequestDto.builder().id(100L).requester(1L).event(10L).build();
        when(userDirectory.require(1L)).thenThrow(unavailable);
        when(requestRepository.findAllByRequesterId(1L)).thenReturn(List.of(request));
        when(requestMapper.toDtoList(List.of(request))).thenReturn(List.of(dto));

        assertEquals(List.of(dto), assertDoesNotThrow(() -> requestService.getUserRequests(1L)));

        verify(requestRepository).findAllByRequesterId(1L);
        verify(userDirectory).require(1L);
        verifyNoMoreInteractions(requestRepository, userDirectory);
        verifyNoInteractions(eventDirectory, transactionManager, requestDataGuard);
    }

    @Test
    void ownRequestsShouldNotTreatEmptyRowsAsProofOfUserExistence() {
        ServiceUnavailableException unavailable = new ServiceUnavailableException("Сервис пользователей недоступен");
        when(userDirectory.require(1L)).thenThrow(unavailable);
        when(requestRepository.findAllByRequesterId(1L)).thenReturn(List.of());

        assertSame(unavailable, assertThrows(ServiceUnavailableException.class,
                () -> requestService.getUserRequests(1L)));

        verify(requestRepository).findAllByRequesterId(1L);
        verifyNoInteractions(requestMapper, eventDirectory, transactionManager, requestDataGuard);
    }

    @Test
    void ownRequestsShouldPreserveMissingUserAndProgrammerErrorsBeforeLocalRead() {
        for (RuntimeException failure : List.of(new NotFoundException("Пользователь не найден"),
                new IllegalStateException("Некорректный ответ"))) {
            doThrow(failure).when(userDirectory).require(1L);
            assertSame(failure, assertThrows(RuntimeException.class, () -> requestService.getUserRequests(1L)));
        }
        verifyNoInteractions(requestRepository, requestMapper, eventDirectory, transactionManager);
    }

    @Test
    void ownRequestsShouldRejectSuccessfulNullOrInvalidUserBeforeLocalRead() {
        for (UserShortDto invalid : new UserShortDto[]{null, user(2L, "Другой пользователь"), user(1L, null)}) {
            when(userDirectory.require(1L)).thenReturn(invalid);
            assertThrows(IllegalStateException.class, () -> requestService.getUserRequests(1L));
        }
        verifyNoInteractions(requestRepository, requestMapper, eventDirectory, transactionManager);
    }

    @Test
    void participantsShouldUseLocalRowsOnlyAfterRealEventConfirmsOwnerDuringUserOutage() {
        when(userDirectory.require(2L)).thenThrow(new ServiceUnavailableException("Сервис пользователей недоступен"));
        when(eventDirectory.require(10L)).thenReturn(event);
        ParticipationRequest request = ParticipationRequest.builder()
                .id(100L).requesterId(1L).eventId(10L).status(RequestStatus.PENDING).build();
        ParticipationRequestDto dto = ParticipationRequestDto.builder().id(100L).requester(1L).event(10L).build();
        when(requestRepository.findAllByEventId(10L)).thenReturn(List.of(request));
        when(requestMapper.toDtoList(List.of(request))).thenReturn(List.of(dto));

        assertEquals(List.of(dto), assertDoesNotThrow(() -> requestService.getEventParticipants(2L, 10L)));

        verify(userDirectory).require(2L);
        var order = inOrder(eventDirectory, requestRepository);
        order.verify(eventDirectory).require(10L);
        order.verify(requestRepository).findAllByEventId(10L);
        verifyNoMoreInteractions(eventDirectory, requestRepository, userDirectory);
        verifyNoInteractions(transactionManager, requestDataGuard);
    }

    @Test
    void participantsShouldRejectForeignInitiatorDuringUserOutageBeforeLocalRead() {
        when(userDirectory.require(1L)).thenThrow(new ServiceUnavailableException("Сервис пользователей недоступен"));
        when(eventDirectory.require(10L)).thenReturn(event);

        assertThrows(ConflictException.class, () -> requestService.getEventParticipants(1L, 10L));

        verifyNoInteractions(requestRepository, requestMapper, transactionManager, requestDataGuard);
    }

    @Test
    void participantsShouldNeverInventOwnerDuringEventOutage() {
        when(userDirectory.require(2L)).thenReturn(initiator);
        ServiceUnavailableException unavailable = new ServiceUnavailableException("Сервис событий недоступен");
        when(eventDirectory.require(10L)).thenThrow(unavailable);

        assertSame(unavailable, assertThrows(ServiceUnavailableException.class,
                () -> requestService.getEventParticipants(2L, 10L)));

        verifyNoInteractions(requestRepository, requestMapper, transactionManager, requestDataGuard);
    }

    @Test
    void participantsShouldPreserveEvent404EvenWhenUserIsUnavailable() {
        when(userDirectory.require(2L)).thenThrow(new ServiceUnavailableException("Сервис пользователей недоступен"));
        NotFoundException missing = new NotFoundException("Событие не найдено");
        when(eventDirectory.require(10L)).thenThrow(missing);

        assertSame(missing, assertThrows(NotFoundException.class,
                () -> requestService.getEventParticipants(2L, 10L)));

        verifyNoInteractions(requestRepository, requestMapper, transactionManager, requestDataGuard);
    }

    private UserShortDto user(Long id, String name) {
        UserShortDto result = new UserShortDto();
        result.setId(id);
        result.setName(name);
        return result;
    }
}
