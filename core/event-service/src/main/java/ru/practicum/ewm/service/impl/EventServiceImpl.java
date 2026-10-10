package ru.practicum.ewm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.stats.client.AnalyzerClient;
import ru.practicum.ewm.stats.proto.RecommendedEventProto;
import ru.practicum.ewm.dto.event.*;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.EventMapper;
import ru.practicum.ewm.model.*;
import ru.practicum.ewm.repository.CategoryRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.ConfirmedRequestCounter;
import ru.practicum.ewm.service.EventService;
import ru.practicum.ewm.service.EventDisplayEnrichment;
import ru.practicum.ewm.service.UserDataGuard;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EventServiceImpl implements EventService {
    private final EventRepository eventRepository;
    private final UserDirectory userDirectory;
    private final UserDataGuard userDataGuard;
    private final TransactionTemplate transactionTemplate;
    private final CategoryRepository categoryRepository;
    private final EventMapper eventMapper;
    private final ConfirmedRequestCounter confirmedRequestCounter;
    private final EventDisplayEnrichment displayEnrichment;
    private final AnalyzerClient statsClient;
    private final int minStartDelayHours;

    public EventServiceImpl(
            EventRepository eventRepository,
            UserDirectory userDirectory,
            CategoryRepository categoryRepository,
            EventMapper eventMapper,
            ConfirmedRequestCounter confirmedRequestCounter,
            EventDisplayEnrichment displayEnrichment,
            AnalyzerClient statsClient,
            UserDataGuard userDataGuard,
            PlatformTransactionManager transactionManager,
            @Value("${ewm.events.min-start-delay-hours}") int minStartDelayHours) {
        this.eventRepository = eventRepository;
        this.userDirectory = userDirectory;
        this.userDataGuard = userDataGuard;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.categoryRepository = categoryRepository;
        this.eventMapper = eventMapper;
        this.confirmedRequestCounter = confirmedRequestCounter;
        this.displayEnrichment = displayEnrichment;
        this.statsClient = statsClient;
        this.minStartDelayHours = minStartDelayHours;
    }

    @Override
    public EventInfoDto getInternalEvent(Long eventId) {
        return eventRepository.findInfoById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
    }

    @Override
    public EventFullDto createEvent(Long userId, NewEventDto dto) {
        log.info("Создание события пользователем с id={}", userId);
        LocalDateTime now = LocalDateTime.now();
        validateEventDate(dto.getEventDate());

        UserShortDto initiator = userDirectory.require(userId);
        return transactionTemplate.execute(status -> {
            userDataGuard.lockForCreate(userId);
            Category category = categoryRepository.findById(dto.getCategory())
                    .orElseThrow(() -> new NotFoundException("Категория с id=" + dto.getCategory() + " не найдена"));
            Event event = eventMapper.toEvent(dto);
            event.setInitiatorId(userId);
            event.setCategory(category);
            event.setCreatedOn(now);
            event.setState(EventState.PENDING);
            Event savedEvent = eventRepository.save(event);
            return eventMapper.toFullDto(savedEvent, initiator, 0L, 0L);
        });
    }

    @Override
    public List<EventShortDto> getUserEvents(Long userId, int from, int size) {
        log.info("Получение событий пользователя с id={}", userId);
        UserShortDto initiator = displayEnrichment.requireUser(userId);
        List<Event> events = eventRepository.findAllByInitiatorIdOrderByIdAsc(
                userId, PageRequest.of(from / size, size));
        Map<Long, Long> counts = confirmedCounts(events);
        Map<Long, Double> ratings = ratings(events);
        return events.stream()
                .map(event -> eventMapper.toShortDto(event, initiator, counts.getOrDefault(event.getId(), 0L),
                        ratings.getOrDefault(event.getId(), 0.0)))
                .toList();
    }

    @Override
    public EventFullDto getUserEvent(Long userId, Long eventId) {
        log.info("Получение события с id={} пользователя с id={}", eventId, userId);
        Event event = requireOwnedEvent(userId, eventId);
        long confirmed = displayEnrichment.confirmedCounts(List.of(eventId)).getOrDefault(eventId, 0L);
        double rating = ratings(List.of(event)).getOrDefault(eventId, 0.0);
        return eventMapper.toFullDto(event, displayEnrichment.requireUser(event.getInitiatorId()), confirmed, rating);
    }

    @Override
    public EventFullDto updateUserEvent(Long userId, Long eventId, UpdateEventUserRequest request) {
        log.info("Изменение события с id={} пользователем с id={}", eventId, userId);
        EventInfoDto snapshot = getInternalEvent(eventId);
        requireOwner(userId, eventId, snapshot.initiatorId());
        validateUserState(snapshot.state());
        validateEventDate(request.getEventDate());
        UserShortDto initiator = displayEnrichment.requireUser(userId);
        long confirmed = displayEnrichment.confirmedCounts(List.of(eventId)).getOrDefault(eventId, 0L);
        double rating = statsClient.ratings(List.of(eventId)).getOrDefault(eventId, 0.0);
        return transactionTemplate.execute(status -> {
            userDataGuard.lockForCreate(userId);
            Event event = requireOwnedEvent(userId, eventId);
            validateUserState(event.getState());
            validateEventDate(request.getEventDate());
            eventMapper.updateFromUserRequest(request, event);
            updateCategory(event, request.getCategory());
            if (request.getStateAction() == UserEventStateAction.SEND_TO_REVIEW) {
                event.setState(EventState.PENDING);
            } else if (request.getStateAction() == UserEventStateAction.CANCEL_REVIEW) {
                event.setState(EventState.CANCELED);
            }
            return eventMapper.toFullDto(event, initiator, confirmed, rating);
        });
    }

    private Event requireOwnedEvent(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
        requireOwner(userId, eventId, event.getInitiatorId());
        return event;
    }

    private void requireOwner(Long userId, Long eventId, Long initiatorId) {
        if (!userId.equals(initiatorId)) {
            throw new NotFoundException("Событие с id=" + eventId + " недоступно пользователю id=" + userId);
        }
    }

    private void validateUserState(EventState state) {
        if (state != EventState.PENDING && state != EventState.CANCELED) {
            throw new ConflictException("Изменить можно только отменённое событие или событие в ожидании модерации");
        }
    }

    private void validateEventDate(LocalDateTime date) {
        if (date != null && date.isBefore(LocalDateTime.now().plusHours(minStartDelayHours))) {
            throw new ConflictException(
                    "Дата события должна быть не раньше чем через "
                            + minStartDelayHours + " ч. от текущего момента"
            );
        }
    }

    private void updateCategory(Event event, Long categoryId) {
        if (categoryId == null) {
            return;
        }

        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new NotFoundException(
                        "Категория с id=" + categoryId + " не найдена"
                ));

        event.setCategory(category);
    }

    private Map<Long, Long> confirmedCounts(List<Event> events) {
        if (events.isEmpty()) return Map.of();
        List<Long> ids = events.stream().map(Event::getId).toList();
        return displayEnrichment.confirmedCounts(ids);
    }

    @Override
    public List<EventFullDto> getAdminEvents(List<Long> users, List<EventState> states, List<Long> categories,
                                             LocalDateTime rangeStart, LocalDateTime rangeEnd, int from, int size) {
        log.info("Административный поиск событий: from={}, size={}", from, size);
        validateRange(rangeStart, rangeEnd);
        Specification<Event> specification = filters(users, states, categories, null, null, rangeStart, rangeEnd);
        List<Event> events = eventRepository.findAll(specification, PageRequest.of(from / size, size,
                Sort.by("id"))).getContent();
        Map<Long, Long> counts = confirmedCounts(events);
        Map<Long, Double> ratings = ratings(events);
        Map<Long, UserShortDto> initiators = initiators(events);
        return events.stream().map(event -> eventMapper.toFullDto(event, requireInitiator(event, initiators),
                counts.getOrDefault(event.getId(), 0L), ratings.getOrDefault(event.getId(), 0.0))).toList();
    }

    @Override
    public EventFullDto updateAdminEvent(Long eventId, UpdateEventAdminRequest request) {
        log.info("Административное изменение события с id={}", eventId);
        EventInfoDto snapshot = getInternalEvent(eventId);
        validateAdminState(snapshot.state(), request.getStateAction());
        if (request.getStateAction() == AdminEventStateAction.PUBLISH_EVENT) {
            LocalDateTime date = request.getEventDate() == null
                    ? eventRepository.findEventDateById(eventId)
                            .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"))
                    : request.getEventDate();
            validatePublicationDate(date);
        }
        UserShortDto initiator = displayEnrichment.requireUser(snapshot.initiatorId());
        long confirmed = displayEnrichment.confirmedCounts(List.of(eventId)).getOrDefault(eventId, 0L);
        double rating = statsClient.ratings(List.of(eventId)).getOrDefault(eventId, 0.0);
        return transactionTemplate.execute(status -> {
            userDataGuard.lockForCreate(snapshot.initiatorId());
            Event event = eventRepository.findById(eventId)
                    .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
            validateAdminState(event.getState(), request.getStateAction());
            if (request.getStateAction() == AdminEventStateAction.PUBLISH_EVENT) {
                validatePublicationDate(request.getEventDate() == null ? event.getEventDate() : request.getEventDate());
                event.setState(EventState.PUBLISHED);
                event.setPublishedOn(LocalDateTime.now());
            } else if (request.getStateAction() == AdminEventStateAction.REJECT_EVENT) {
                event.setState(EventState.CANCELED);
            }
            eventMapper.updateFromAdminRequest(request, event);
            updateCategory(event, request.getCategory());
            return eventMapper.toFullDto(event, initiator, confirmed, rating);
        });
    }

    private void validateAdminState(EventState state, AdminEventStateAction action) {
        if (action == AdminEventStateAction.PUBLISH_EVENT && state != EventState.PENDING) {
            throw new ConflictException("Опубликовать можно только событие в ожидании публикации");
        }
        if (action == AdminEventStateAction.REJECT_EVENT && state == EventState.PUBLISHED) {
            throw new ConflictException("Опубликованное событие нельзя отклонить");
        }
    }

    private void validatePublicationDate(LocalDateTime date) {
        if (date.isBefore(LocalDateTime.now().plusHours(1))) {
            throw new ConflictException("До начала публикуемого события должно оставаться не менее часа");
        }
    }

    @Override
    public List<EventShortDto> getPublicEvents(String text, List<Long> categories, Boolean isPaid,
                                               LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                               boolean isOnlyAvailable, EventSort sort, int from, int size) {
        log.info("Публичный поиск событий: from={}, size={}, onlyAvailable={}", from, size, isOnlyAvailable);
        LocalDateTime effectiveStart = rangeStart == null ? LocalDateTime.now() : rangeStart;
        validateRange(effectiveStart, rangeEnd);
        Specification<Event> specification = filters(null, List.of(EventState.PUBLISHED), categories,
                text, isPaid, effectiveStart, rangeEnd);
        List<Event> events = eventRepository.findAll(specification, Sort.by(EventSort.EVENT_DATE.getProperty(), "id"));
        Map<Long, Long> counts = isOnlyAvailable
                ? confirmedRequestCounter.countAll(events.stream().map(Event::getId).toList())
                : confirmedCounts(events);
        Map<Long, Double> ratings = ratings(events);
        Map<Long, UserShortDto> initiators = initiators(events);
        List<EventShortDto> result = events.stream()
                .filter(event -> !isOnlyAvailable || event.getParticipantLimit() == 0 ||
                        counts.getOrDefault(event.getId(), 0L) < event.getParticipantLimit())
                .map(event -> eventMapper.toShortDto(event, requireInitiator(event, initiators),
                        counts.getOrDefault(event.getId(), 0L),
                        ratings.getOrDefault(event.getId(), 0.0)))
                .toList();
        if (sort == EventSort.RATING || sort == EventSort.VIEWS) {
            result = result.stream().sorted(java.util.Comparator.comparingDouble(EventShortDto::getRating).reversed()
                    .thenComparing(EventShortDto::getId))
                    .toList();
        }
        int startIndex = Math.min(from, result.size());
        int endIndex = (int) Math.min((long) startIndex + size, result.size());
        return result.subList(startIndex, endIndex);
    }

    @Override
    public EventFullDto getPublicEvent(Long eventId) {
        log.info("Получение опубликованного события с id={}", eventId);
        Event event = eventRepository.findById(eventId)
                .filter(found -> found.getState() == EventState.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Опубликованное событие с id=" + eventId + " не найдено"));
        long confirmed = displayEnrichment.confirmedCounts(List.of(eventId)).getOrDefault(eventId, 0L);
        double rating = ratings(List.of(event)).getOrDefault(eventId, 0.0);
        return eventMapper.toFullDto(event, displayEnrichment.requireUser(event.getInitiatorId()), confirmed, rating);
    }

    private void validateRange(LocalDateTime start, LocalDateTime end) {
        if (start != null && end != null && start.isAfter(end)) {
            throw new ru.practicum.ewm.exception.ValidationException("Начало диапазона не может быть позже окончания");
        }
    }

    private Specification<Event> filters(List<Long> users, List<EventState> states, List<Long> categories,
                                         String text, Boolean isPaid, LocalDateTime start, LocalDateTime end) {
        return (root, query, builder) -> {
            List<jakarta.persistence.criteria.Predicate> predicates = new java.util.ArrayList<>();
            if (users != null && !users.isEmpty()) predicates.add(root.get("initiatorId").in(users));
            if (states != null && !states.isEmpty()) predicates.add(root.get("state").in(states));
            if (categories != null && !categories.isEmpty())
                predicates.add(root.get("category").get("id").in(categories));
            if (isPaid != null) predicates.add(builder.equal(root.get("isPaid"), isPaid));
            if (text != null && !text.isBlank()) {
                String pattern = "%" + text.toLowerCase() + "%";
                predicates.add(builder.or(builder.like(builder.lower(root.get("annotation")), pattern),
                        builder.like(builder.lower(root.get("description")), pattern)));
            }
            if (start != null) predicates.add(builder.greaterThanOrEqualTo(root.get("eventDate"), start));
            if (end != null) predicates.add(builder.lessThanOrEqualTo(root.get("eventDate"), end));
            return builder.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
        };
    }

    private Map<Long, Double> ratings(List<Event> events) {
        if (events.isEmpty()) return Map.of();
        return statsClient.ratings(events.stream().map(Event::getId).distinct().toList());
    }

    @Override
    public List<EventShortDto> getRecommendations(Long userId, int size) {
        List<RecommendedEventProto> recommendations = statsClient.recommendations(userId, size);
        if (recommendations.isEmpty()) return List.of();
        List<Long> ids = recommendations.stream().map(RecommendedEventProto::getEventId).toList();
        Map<Long, Event> found = eventRepository.findAllById(ids).stream()
                .filter(event -> event.getState() == EventState.PUBLISHED)
                .collect(Collectors.toMap(Event::getId, event -> event));
        List<Event> events = ids.stream().map(found::get).filter(java.util.Objects::nonNull).toList();
        Map<Long, Long> counts = confirmedCounts(events);
        Map<Long, Double> ratings = ratings(events);
        Map<Long, UserShortDto> users = initiators(events);
        return events.stream().map(event -> eventMapper.toShortDto(event, requireInitiator(event, users),
                counts.getOrDefault(event.getId(), 0L), ratings.getOrDefault(event.getId(), 0.0))).toList();
    }

    @Override
    public void validateLike(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .filter(found -> found.getState() == EventState.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Опубликованное событие с id=" + eventId + " не найдено"));
        if (event.getEventDate().isAfter(LocalDateTime.now())
                || !confirmedRequestCounter.hasConfirmedRequest(userId, eventId)) {
            throw new ru.practicum.ewm.exception.ValidationException(
                    "Поставить лайк может только участник прошедшего мероприятия");
        }
    }

    private Map<Long, UserShortDto> initiators(List<Event> events) {
        return displayEnrichment.findUsers(events.stream().map(Event::getInitiatorId).toList());
    }

    private UserShortDto requireInitiator(Event event, Map<Long, UserShortDto> initiators) {
        UserShortDto initiator = initiators.get(event.getInitiatorId());
        if (initiator == null) {
            throw new NotFoundException("Пользователь с id=" + event.getInitiatorId() + " не найден");
        }
        return initiator;
    }
}
