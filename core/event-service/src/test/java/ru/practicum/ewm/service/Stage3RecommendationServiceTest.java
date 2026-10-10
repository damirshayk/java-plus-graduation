package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mapstruct.factory.Mappers;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.exception.ValidationException;
import ru.practicum.ewm.mapper.EventMapper;
import ru.practicum.ewm.model.Category;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.repository.CategoryRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.impl.EventServiceImpl;
import ru.practicum.ewm.stats.client.AnalyzerClient;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class Stage3RecommendationServiceTest {
    @Mock
    private EventRepository events;
    @Mock
    private CategoryRepository categories;
    @Mock
    private UserDirectory users;
    @Mock
    private ConfirmedRequestCounter requests;
    @Mock
    private AnalyzerClient analyzer;
    @Mock
    private UserDataGuard guard;
    @Mock
    private PlatformTransactionManager transactionManager;
    private EventService service;

    @BeforeEach
    void setUp() {
        EventDisplayProperties properties = new EventDisplayProperties();
        properties.getRetry().setBackoffMs(1);
        service = new EventServiceImpl(events, users, categories, Mappers.getMapper(EventMapper.class),
                requests, new EventDisplayEnrichment(users, requests, properties), analyzer, guard,
                transactionManager, 2);
    }

    @Test
    void recommendationsMustKeepAnalyzerOrderAndHideMissingOrUnpublishedEvents() {
        Event first = event(10L, 1L, EventState.PUBLISHED);
        Event second = event(20L, 2L, EventState.PUBLISHED);
        Event hidden = event(30L, 3L, EventState.PENDING);
        when(analyzer.recommendations(5L, 10)).thenReturn(List.of(
                recommendation(20L), recommendation(40L), recommendation(30L), recommendation(10L)));
        when(events.findAllById(List.of(20L, 40L, 30L, 10L))).thenReturn(List.of(first, hidden, second));
        when(analyzer.ratings(List.of(20L, 10L))).thenReturn(Map.of(20L, 3.4, 10L, 0.4));
        when(requests.countAll(List.of(20L, 10L))).thenReturn(Map.of(20L, 2L, 10L, 1L));
        when(users.findAll(List.of(2L, 1L))).thenReturn(Map.of(1L, user(1L), 2L, user(2L)));

        List<EventShortDto> result = service.getRecommendations(5L, 10);

        assertThat(result).extracting(EventShortDto::getId).containsExactly(20L, 10L);
        assertThat(result).extracting(EventShortDto::getRating).containsExactly(3.4, 0.4);
        assertThat(result).extracting(EventShortDto::getConfirmedRequests).containsExactly(2L, 1L);
        assertThat(result).extracting(dto -> dto.getCategory().getId()).containsExactly(1L, 1L);
        verify(events).findAllById(List.of(20L, 40L, 30L, 10L));
        verifyNoMoreInteractions(events);
        verify(analyzer).recommendations(5L, 10);
        verify(analyzer).ratings(List.of(20L, 10L));
        verifyNoMoreInteractions(analyzer);
        verify(users).findAll(List.of(2L, 1L));
        verifyNoMoreInteractions(users);
        verify(requests).countAll(List.of(20L, 10L));
        verifyNoMoreInteractions(requests);
    }

    @Test
    void emptyRecommendationsMustNotQueryRepositoryOrEnrichmentServices() {
        when(analyzer.recommendations(5L, 10)).thenReturn(List.of());

        assertThat(service.getRecommendations(5L, 10)).isEmpty();

        verify(analyzer).recommendations(5L, 10);
        verifyNoMoreInteractions(analyzer);
        verifyNoInteractions(events, users, requests);
    }

    @Test
    void recommendationsContainingOnlyHiddenEventsMustNotRequestRatingsOrUsers() {
        when(analyzer.recommendations(5L, 10)).thenReturn(List.of(recommendation(10L)));
        when(events.findAllById(List.of(10L))).thenReturn(List.of(event(10L, 1L, EventState.CANCELED)));

        assertThat(service.getRecommendations(5L, 10)).isEmpty();

        verify(analyzer).recommendations(5L, 10);
        verifyNoMoreInteractions(analyzer);
        verifyNoInteractions(users, requests);
    }

    @Test
    void confirmedParticipantMayLikePastPublishedEvent() {
        when(events.findById(10L)).thenReturn(Optional.of(event(10L, 1L, EventState.PUBLISHED)));
        when(requests.hasConfirmedRequest(5L, 10L)).thenReturn(true);

        assertDoesNotThrow(() -> service.validateLike(5L, 10L));

        verify(requests).hasConfirmedRequest(5L, 10L);
        verifyNoMoreInteractions(requests);
    }

    @Test
    void futureEventMustNotPermitLikeEvenBeforeAttendanceRequest() {
        Event event = event(10L, 1L, EventState.PUBLISHED);
        event.setEventDate(LocalDateTime.now().plusDays(1));
        when(events.findById(10L)).thenReturn(Optional.of(event));

        assertThatThrownBy(() -> service.validateLike(5L, 10L)).isInstanceOf(ValidationException.class);

        verifyNoInteractions(requests);
    }

    @Test
    void participantWithoutConfirmedRequestMustNotLikePastEvent() {
        when(events.findById(10L)).thenReturn(Optional.of(event(10L, 1L, EventState.PUBLISHED)));
        when(requests.hasConfirmedRequest(5L, 10L)).thenReturn(false);

        assertThatThrownBy(() -> service.validateLike(5L, 10L)).isInstanceOf(ValidationException.class);
    }

    @Test
    void missingOrUnpublishedEventMustNotCheckAttendance() {
        when(events.findById(10L)).thenReturn(Optional.empty());
        when(events.findById(20L)).thenReturn(Optional.of(event(20L, 1L, EventState.PENDING)));

        assertThatThrownBy(() -> service.validateLike(5L, 10L)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.validateLike(5L, 20L)).isInstanceOf(NotFoundException.class);

        verifyNoInteractions(requests);
    }

    @Test
    void unavailableAttendanceCheckMustNotPermitLikeThroughDisplayFallback() {
        when(events.findById(10L)).thenReturn(Optional.of(event(10L, 1L, EventState.PUBLISHED)));
        when(requests.hasConfirmedRequest(5L, 10L))
                .thenThrow(new ServiceUnavailableException("Сервис заявок временно недоступен"));

        assertThatThrownBy(() -> service.validateLike(5L, 10L)).isInstanceOf(ServiceUnavailableException.class);

        verify(requests).hasConfirmedRequest(5L, 10L);
        verifyNoMoreInteractions(requests);
    }

    private RecommendedEventProto recommendation(long id) {
        return RecommendedEventProto.newBuilder().setEventId(id).setScore(0.5).build();
    }

    private Event event(long id, long initiatorId, EventState state) {
        Category category = new Category();
        category.setId(1L);
        category.setName("Категория");
        return Event.builder().id(id).initiatorId(initiatorId).category(category)
                .title("Событие " + id).annotation("Аннотация события").description("Описание события")
                .eventDate(LocalDateTime.now().minusDays(1)).createdOn(LocalDateTime.now().minusDays(2))
                .state(state).build();
    }

    private UserShortDto user(long id) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName("Инициатор " + id);
        return user;
    }
}
