package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.ViewStats;
import ru.practicum.ewm.dto.event.*;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.EventMapper;
import ru.practicum.ewm.model.Category;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.CategoryRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.impl.EventServiceImpl;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventServiceImplTest {
    @Mock
    private EventRepository eventRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private CategoryRepository categoryRepository;
    @Mock
    private RequestRepository requestRepository;
    @Mock
    private EventMapper eventMapper;
    @Mock
    private StatsClient statsClient;
    private EventServiceImpl eventService;

    @BeforeEach
    void setUp() {
        eventService = new EventServiceImpl(
                eventRepository,
                userRepository,
                categoryRepository,
                eventMapper,
                requestRepository,
                statsClient,
                2 // Дефолтное значение для тестов
        );
    }

    @Test
    void getUserEventShouldFailWhenEventDoesNotExist() {
        assertThrows(NotFoundException.class, () -> eventService.getUserEvent(1L, 10L));
    }

    @Test
    void getPublicEventShouldHideUnpublishedEvent() {
        Event event = new Event();
        event.setState(EventState.PENDING);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThrows(NotFoundException.class, () -> eventService.getPublicEvent(10L));
    }

    @Test
    void adminShouldNotRejectPublishedEvent() {
        Event event = new Event();
        event.setState(EventState.PUBLISHED);
        UpdateEventAdminRequest request = new UpdateEventAdminRequest();
        request.setStateAction(AdminEventStateAction.REJECT_EVENT);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThrows(ConflictException.class, () -> eventService.updateAdminEvent(10L, request));
    }

    @Test
    void getUserEventShouldIncludeViewsFromStatsService() {
        User user = new User();
        user.setId(1L);
        Event event = new Event();
        event.setId(10L);
        event.setInitiator(user);
        ViewStats stats = ViewStats.builder().uri("/events/10").hits(7L).build();
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(statsClient.getStats(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                eq(List.of("/events/10")), eq(true))).thenReturn(List.of(stats));

        eventService.getUserEvent(1L, 10L);

        verify(eventMapper).toFullDto(same(event), eq(0L), eq(7L));
    }

    @Test
    void createEventShouldRejectDateEarlierThanTwoHours() {
        NewEventDto request = new NewEventDto();
        request.setEventDate(LocalDateTime.now().plusMinutes(30));

        assertThrows(ConflictException.class, () -> eventService.createEvent(1L, request));
    }

    @Test
    void userShouldNotUpdatePublishedEvent() {
        User user = new User();
        user.setId(1L);
        Event event = new Event();
        event.setId(10L);
        event.setInitiator(user);
        event.setState(EventState.PUBLISHED);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThrows(ConflictException.class,
                () -> eventService.updateUserEvent(1L, 10L, new UpdateEventUserRequest()));
    }

    @Test
    void adminShouldNotPublishEventStartingEarlierThanOneHour() {
        Event event = new Event();
        event.setId(10L);
        event.setState(EventState.PENDING);
        event.setEventDate(LocalDateTime.now().plusMinutes(30));
        UpdateEventAdminRequest request = new UpdateEventAdminRequest();
        request.setStateAction(AdminEventStateAction.PUBLISH_EVENT);
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));

        assertThrows(ConflictException.class, () -> eventService.updateAdminEvent(10L, request));
    }

    @Test
    void createEventShouldSavePendingEvent() {
        User user = new User();
        user.setId(1L);
        Category category = new Category();
        category.setId(2L);
        NewEventDto request = new NewEventDto();
        request.setCategory(2L);
        request.setEventDate(LocalDateTime.now().plusDays(1));
        Event event = new Event();
        EventFullDto expected = new EventFullDto();
        when(userRepository.findById(1L)).thenReturn(Optional.of(user));
        when(categoryRepository.findById(2L)).thenReturn(Optional.of(category));
        when(eventMapper.toEvent(request)).thenReturn(event);
        when(eventRepository.save(event)).thenReturn(event);
        when(eventMapper.toFullDto(event, 0L, 0L)).thenReturn(expected);

        EventFullDto result = eventService.createEvent(1L, request);

        assertSame(expected, result);
        assertSame(user, event.getInitiator());
        assertSame(category, event.getCategory());
        assertEquals(EventState.PENDING, event.getState());
        assertNotNull(event.getCreatedOn());
        verify(eventRepository).save(event);
    }

    @Test
    void userShouldUpdateOwnPendingEvent() {
        User user = new User();
        user.setId(1L);
        Event event = new Event();
        event.setId(10L);
        event.setInitiator(user);
        event.setState(EventState.PENDING);
        UpdateEventUserRequest request = new UpdateEventUserRequest();
        request.setTitle("Обновлённое событие");
        request.setStateAction(ru.practicum.ewm.dto.event.UserEventStateAction.CANCEL_REVIEW);
        EventFullDto expected = new EventFullDto();
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(eventMapper.toFullDto(event, 0L, 0L)).thenReturn(expected);

        EventFullDto result = eventService.updateUserEvent(1L, 10L, request);

        assertSame(expected, result);
        verify(eventMapper).updateFromUserRequest(request, event);
        assertEquals(EventState.CANCELED, event.getState());
    }

    @Test
    void adminShouldPublishPendingEvent() {
        Event event = new Event();
        event.setId(10L);
        event.setState(EventState.PENDING);
        event.setEventDate(LocalDateTime.now().plusDays(1));
        UpdateEventAdminRequest request = new UpdateEventAdminRequest();
        request.setStateAction(AdminEventStateAction.PUBLISH_EVENT);
        EventFullDto expected = new EventFullDto();
        when(eventRepository.findById(10L)).thenReturn(Optional.of(event));
        when(eventMapper.toFullDto(event, 0L, 0L)).thenReturn(expected);

        EventFullDto result = eventService.updateAdminEvent(10L, request);

        assertSame(expected, result);
        assertEquals(EventState.PUBLISHED, event.getState());
        assertNotNull(event.getPublishedOn());
        verify(eventMapper).updateFromAdminRequest(request, event);
    }

    @Test
    void publicSearchShouldExcludeEventsWithoutAvailablePlaces() {
        Event full = event(10L, 1);
        Event available = event(20L, 0);
        EventShortDto availableDto = new EventShortDto();
        availableDto.setId(20L);
        when(eventRepository.findAll(org.mockito.ArgumentMatchers.<Specification<Event>>any(),
                org.mockito.ArgumentMatchers.any(Sort.class))).thenReturn(List.of(full, available));
        when(requestRepository.countByEventIdsAndStatus(List.of(10L, 20L),
                ru.practicum.ewm.model.RequestStatus.CONFIRMED))
                .thenReturn(List.<Object[]>of(new Object[]{10L, 1L}));
        when(eventMapper.toShortDto(available, 0L, 0L)).thenReturn(availableDto);

        List<EventShortDto> result = eventService.getPublicEvents(null, null, null,
                null, null, true, null, 0, 10);

        assertEquals(List.of(availableDto), result);
    }

    @Test
    void publicSearchShouldSortEventsByViewsDescending() {
        Event first = event(10L, 0);
        Event second = event(20L, 0);
        EventShortDto firstDto = new EventShortDto();
        firstDto.setId(10L);
        firstDto.setViews(2L);
        EventShortDto secondDto = new EventShortDto();
        secondDto.setId(20L);
        secondDto.setViews(7L);
        when(eventRepository.findAll(org.mockito.ArgumentMatchers.<Specification<Event>>any(),
                org.mockito.ArgumentMatchers.any(Sort.class))).thenReturn(List.of(first, second));
        when(requestRepository.countByEventIdsAndStatus(List.of(10L, 20L),
                ru.practicum.ewm.model.RequestStatus.CONFIRMED)).thenReturn(List.of());
        when(statsClient.getStats(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                eq(List.of("/events/10", "/events/20")), eq(true)))
                .thenReturn(List.of(
                        ViewStats.builder().uri("/events/10").hits(2L).build(),
                        ViewStats.builder().uri("/events/20").hits(7L).build()));
        when(eventMapper.toShortDto(first, 0L, 2L)).thenReturn(firstDto);
        when(eventMapper.toShortDto(second, 0L, 7L)).thenReturn(secondDto);

        List<EventShortDto> result = eventService.getPublicEvents(null, null, null,
                null, null, false, EventSort.VIEWS, 0, 10);

        assertEquals(List.of(secondDto, firstDto), result);
    }

    @Test
    void getUserEventsShouldFailWhenUserDoesNotExist() {
        when(userRepository.existsById(1L)).thenReturn(false);

        assertThrows(NotFoundException.class,
                () -> eventService.getUserEvents(1L, 0, 10));

        verify(userRepository).existsById(1L);
    }

    private Event event(Long id, int participantLimit) {
        Event event = new Event();
        event.setId(id);
        event.setParticipantLimit(participantLimit);
        event.setState(EventState.PUBLISHED);
        event.setEventDate(LocalDateTime.now().plusDays(1));
        return event;
    }
}
