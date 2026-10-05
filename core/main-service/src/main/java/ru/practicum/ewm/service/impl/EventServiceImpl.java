package ru.practicum.ewm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.ViewStats;
import ru.practicum.ewm.dto.event.*;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.EventMapper;
import ru.practicum.ewm.model.*;
import ru.practicum.ewm.repository.CategoryRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.repository.RequestRepository;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.EventService;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class EventServiceImpl implements EventService {
    private final EventRepository eventRepository;
    private final UserRepository userRepository;
    private final CategoryRepository categoryRepository;
    private final EventMapper eventMapper;
    private final RequestRepository requestRepository;
    private final StatsClient statsClient;
    private final int minStartDelayHours;

    public EventServiceImpl(
            EventRepository eventRepository,
            UserRepository userRepository,
            CategoryRepository categoryRepository,
            EventMapper eventMapper,
            RequestRepository requestRepository,
            StatsClient statsClient,
            @Value("${ewm.events.min-start-delay-hours}") int minStartDelayHours) {
        this.eventRepository = eventRepository;
        this.userRepository = userRepository;
        this.categoryRepository = categoryRepository;
        this.eventMapper = eventMapper;
        this.requestRepository = requestRepository;
        this.statsClient = statsClient;
        this.minStartDelayHours = minStartDelayHours;
    }

    @Override
    public EventFullDto createEvent(Long userId, NewEventDto dto) {
        log.info("Создание события пользователем с id={}", userId);
        LocalDateTime now = LocalDateTime.now();
        validateEventDate(dto.getEventDate());

        User initiator = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Пользователь с id=" + userId + " не найден"));
        Category category = categoryRepository.findById(dto.getCategory())
                .orElseThrow(() -> new NotFoundException("Категория с id=" + dto.getCategory() + " не найдена"));

        Event event = eventMapper.toEvent(dto);
        event.setInitiator(initiator);
        event.setCategory(category);
        event.setCreatedOn(now);
        event.setState(EventState.PENDING);

        Event savedEvent = eventRepository.save(event);
        return eventMapper.toFullDto(savedEvent, 0L, 0L);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventShortDto> getUserEvents(Long userId, int from, int size) {
        log.info("Получение событий пользователя с id={}", userId);
        requireUser(userId);
        List<Event> events = eventRepository.findAllByInitiatorIdOrderByIdAsc(
                userId, PageRequest.of(from / size, size));
        Map<Long, Long> counts = confirmedCounts(events);
        Map<String, Long> views = views(events);
        return events.stream()
                .map(event -> eventMapper.toShortDto(event, counts.getOrDefault(event.getId(), 0L),
                        views.getOrDefault(eventUri(event.getId()), 0L)))
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public EventFullDto getUserEvent(Long userId, Long eventId) {
        log.info("Получение события с id={} пользователя с id={}", eventId, userId);
        Event event = requireOwnedEvent(userId, eventId);
        long confirmed = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        long views = views(List.of(event)).getOrDefault(eventUri(eventId), 0L);
        return eventMapper.toFullDto(event, confirmed, views);
    }

    @Override
    @Transactional
    public EventFullDto updateUserEvent(Long userId, Long eventId, UpdateEventUserRequest request) {
        log.info("Изменение события с id={} пользователем с id={}", eventId, userId);
        Event event = requireOwnedEvent(userId, eventId);
        if (event.getState() != EventState.PENDING && event.getState() != EventState.CANCELED) {
            throw new ConflictException("Изменить можно только отменённое событие или событие в ожидании модерации");
        }
        validateEventDate(request.getEventDate());
        eventMapper.updateFromUserRequest(request, event);
        updateCategory(event, request.getCategory());
        if (request.getStateAction() == UserEventStateAction.SEND_TO_REVIEW) {
            event.setState(EventState.PENDING);
        } else if (request.getStateAction() == UserEventStateAction.CANCEL_REVIEW) {
            event.setState(EventState.CANCELED);
        }
        long confirmed = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        long views = views(List.of(event)).getOrDefault(eventUri(eventId), 0L);
        return eventMapper.toFullDto(event, confirmed, views);
    }

    private void requireUser(Long userId) {
        if (!userRepository.existsById(userId)) {
            throw new NotFoundException("Пользователь с id=" + userId + " не найден");
        }
    }

    private Event requireOwnedEvent(Long userId, Long eventId) {
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
        if (!event.getInitiator().getId().equals(userId)) {
            throw new NotFoundException("Событие с id=" + eventId + " недоступно пользователю id=" + userId);
        }
        return event;
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
        return requestRepository.countByEventIdsAndStatus(ids, RequestStatus.CONFIRMED).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventFullDto> getAdminEvents(List<Long> users, List<EventState> states, List<Long> categories,
                                             LocalDateTime rangeStart, LocalDateTime rangeEnd, int from, int size) {
        log.info("Административный поиск событий: from={}, size={}", from, size);
        validateRange(rangeStart, rangeEnd);
        Specification<Event> specification = filters(users, states, categories, null, null, rangeStart, rangeEnd);
        List<Event> events = eventRepository.findAll(specification, PageRequest.of(from / size, size,
                Sort.by("id"))).getContent();
        Map<Long, Long> counts = confirmedCounts(events);
        Map<String, Long> views = views(events);
        return events.stream().map(event -> eventMapper.toFullDto(event,
                counts.getOrDefault(event.getId(), 0L), views.getOrDefault(eventUri(event.getId()), 0L))).toList();
    }

    @Override
    @Transactional
    public EventFullDto updateAdminEvent(Long eventId, UpdateEventAdminRequest request) {
        log.info("Административное изменение события с id={}", eventId);
        Event event = eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException("Событие с id=" + eventId + " не найдено"));
        if (request.getStateAction() == AdminEventStateAction.PUBLISH_EVENT) {
            if (event.getState() != EventState.PENDING) {
                throw new ConflictException("Опубликовать можно только событие в ожидании публикации");
            }
            LocalDateTime resultingDate = request.getEventDate() == null ? event.getEventDate() : request.getEventDate();
            if (resultingDate.isBefore(LocalDateTime.now().plusHours(1))) {
                throw new ConflictException("До начала публикуемого события должно оставаться не менее часа");
            }
            event.setState(EventState.PUBLISHED);
            event.setPublishedOn(LocalDateTime.now());
        } else if (request.getStateAction() == AdminEventStateAction.REJECT_EVENT) {
            if (event.getState() == EventState.PUBLISHED) {
                throw new ConflictException("Опубликованное событие нельзя отклонить");
            }
            event.setState(EventState.CANCELED);
        }
        eventMapper.updateFromAdminRequest(request, event);
        updateCategory(event, request.getCategory());
        long confirmed = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        long views = views(List.of(event)).getOrDefault(eventUri(eventId), 0L);
        return eventMapper.toFullDto(event, confirmed, views);
    }

    @Override
    @Transactional(readOnly = true)
    public List<EventShortDto> getPublicEvents(String text, List<Long> categories, Boolean isPaid,
                                               LocalDateTime rangeStart, LocalDateTime rangeEnd,
                                               boolean isOnlyAvailable, EventSort sort, int from, int size) {
        log.info("Публичный поиск событий: from={}, size={}, onlyAvailable={}", from, size, isOnlyAvailable);
        LocalDateTime effectiveStart = rangeStart == null ? LocalDateTime.now() : rangeStart;
        validateRange(effectiveStart, rangeEnd);
        Specification<Event> specification = filters(null, List.of(EventState.PUBLISHED), categories,
                text, isPaid, effectiveStart, rangeEnd);
        List<Event> events = eventRepository.findAll(specification, Sort.by(EventSort.EVENT_DATE.getProperty()));
        Map<Long, Long> counts = confirmedCounts(events);
        Map<String, Long> views = views(events);
        List<EventShortDto> result = events.stream()
                .filter(event -> !isOnlyAvailable || event.getParticipantLimit() == 0 ||
                        counts.getOrDefault(event.getId(), 0L) < event.getParticipantLimit())
                .map(event -> eventMapper.toShortDto(event, counts.getOrDefault(event.getId(), 0L),
                        views.getOrDefault(eventUri(event.getId()), 0L)))
                .toList();
        if (sort == EventSort.VIEWS) {
            result = result.stream().sorted(java.util.Comparator.comparingLong(EventShortDto::getViews).reversed())
                    .toList();
        }
        int startIndex = Math.min(from, result.size());
        int endIndex = Math.min(startIndex + size, result.size());
        return result.subList(startIndex, endIndex);
    }

    @Override
    @Transactional(readOnly = true)
    public EventFullDto getPublicEvent(Long eventId) {
        log.info("Получение опубликованного события с id={}", eventId);
        Event event = eventRepository.findById(eventId)
                .filter(found -> found.getState() == EventState.PUBLISHED)
                .orElseThrow(() -> new NotFoundException("Опубликованное событие с id=" + eventId + " не найдено"));
        long confirmed = requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
        long views = views(List.of(event)).getOrDefault(eventUri(eventId), 0L);
        return eventMapper.toFullDto(event, confirmed, views);
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
            if (users != null && !users.isEmpty()) predicates.add(root.get("initiator").get("id").in(users));
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

    private Map<String, Long> views(List<Event> events) {
        if (events.isEmpty()) return Map.of();
        List<String> uris = events.stream().map(event -> eventUri(event.getId())).toList();
        return statsClient.getStats(LocalDateTime.of(2000, 1, 1, 0, 0), LocalDateTime.now(), uris, true)
                .stream().collect(Collectors.toMap(ViewStats::getUri, ViewStats::getHits, Long::max));
    }

    private String eventUri(Long eventId) {
        return "/events/" + eventId;
    }
}
