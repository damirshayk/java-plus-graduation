package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.ewm.dto.request.ParticipationRequestDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.RequestMapper;
import ru.practicum.ewm.model.*;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.impl.RequestServiceImpl;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class RequestServiceImplTest {
    @Mock
    private RequestRepository requestRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private EventRepository eventRepository;
    @Mock
    private RequestMapper requestMapper;

    @InjectMocks
    private RequestServiceImpl requestService;

    private User requester;
    private User initiator;
    private Event event;

    @BeforeEach
    void setUp() {
        requester = User.builder().id(1L).name("Requester").email("req@test.com").build();
        initiator = User.builder().id(2L).name("Initiator").email("init@test.com").build();

        event = Event.builder()
                .id(10L)
                .initiator(initiator)
                .state(EventState.PUBLISHED)
                .participantLimit(10)
                .isRequestModeration(true)
                .build();
    }

    @Test
    void addParticipationRequest_Success_Pending() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(requester));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(false);
        when(requestRepository.countByEventIdAndStatus(10L, RequestStatus.CONFIRMED)).thenReturn(5L);

        ParticipationRequest savedRequest = ParticipationRequest.builder()
                .event(event).requester(requester).status(RequestStatus.PENDING).build();

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
        verify(requestRepository, times(1)).save(any(ParticipationRequest.class));
    }

    @Test
    void addParticipationRequest_Success_AutoConfirmed_WhenNoModeration() {
        event.setRequestModeration(false);

        when(userRepository.findById(1L)).thenReturn(Optional.of(requester));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
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
        when(userRepository.findById(1L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenDuplicateRequest() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(requester));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(true);

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenRequesterIsInitiator() {
        when(userRepository.findById(2L)).thenReturn(Optional.of(initiator));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(2L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenEventNotPublished() {
        event.setState(EventState.PENDING);

        when(userRepository.findById(1L)).thenReturn(Optional.of(requester));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void addParticipationRequest_ThrowConflict_WhenLimitReached() {
        when(userRepository.findById(1L)).thenReturn(Optional.of(requester));
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(requestRepository.existsByRequesterIdAndEventId(1L, 10L)).thenReturn(false);
        when(requestRepository.countByEventIdAndStatus(10L, RequestStatus.CONFIRMED)).thenReturn(10L); // Лимит достигнут

        assertThrows(ConflictException.class, () -> requestService.addParticipationRequest(1L, 10L));
    }

    @Test
    void cancelRequest_Success() {
        ParticipationRequest activeRequest = ParticipationRequest.builder()
                .id(100L).event(event).requester(requester).status(RequestStatus.PENDING).build();
        ParticipationRequestDto canceledDto = ParticipationRequestDto.builder()
                .id(100L).status("CANCELED").build();

        when(userRepository.existsById(1L)).thenReturn(true);
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
                .id(100L).event(event).requester(initiator).status(RequestStatus.PENDING).build();

        when(userRepository.existsById(1L)).thenReturn(true);
        when(requestRepository.findById(100L)).thenReturn(Optional.of(activeRequest));

        assertThrows(ConflictException.class, () -> requestService.cancelRequest(1L, 100L));
    }
}