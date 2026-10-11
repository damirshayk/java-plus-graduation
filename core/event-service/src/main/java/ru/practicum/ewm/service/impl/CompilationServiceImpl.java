package ru.practicum.ewm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CompilationMapper;
import ru.practicum.ewm.mapper.EventMapper;
import ru.practicum.ewm.model.Compilation;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.repository.CompilationRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.CompilationService;
import ru.practicum.ewm.service.EventDisplayEnrichment;
import ru.practicum.ewm.stats.client.AnalyzerClient;

import java.util.HashSet;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class CompilationServiceImpl implements CompilationService {

    private final CompilationMapper compilationMapper;
    private final CompilationRepository compilationRepository;
    private final EventRepository eventRepository;
    private final EventMapper eventMapper;
    private final EventDisplayEnrichment displayEnrichment;
    private final TransactionTemplate transactionTemplate;
    private final AnalyzerClient analyzerClient;

    public CompilationServiceImpl(CompilationMapper compilationMapper, CompilationRepository compilationRepository,
                                  EventRepository eventRepository, EventMapper eventMapper,
                                  EventDisplayEnrichment displayEnrichment,
                                  PlatformTransactionManager transactionManager,
                                  AnalyzerClient analyzerClient) {
        this.compilationMapper = compilationMapper;
        this.compilationRepository = compilationRepository;
        this.eventRepository = eventRepository;
        this.eventMapper = eventMapper;
        this.displayEnrichment = displayEnrichment;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.analyzerClient = analyzerClient;
    }

    @Override
    public CompilationDto create(NewCompilationDto newCompilationDto) {
        log.info("Создание подборки: {}", newCompilationDto);
        validateTitle(newCompilationDto.getTitle());
        List<Event> snapshot = newCompilationDto.getEvents() == null ? List.of()
                : eventRepository.findAllById(newCompilationDto.getEvents());
        Set<Long> foundIds = eventIds(snapshot);
        Map<Long, UserShortDto> users = findUsers(snapshot);
        Map<Long, Double> ratings = analyzerClient.ratings(foundIds.stream().sorted().toList());
        return transactionTemplate.execute(status -> {
            validateTitle(newCompilationDto.getTitle());
            List<Event> events = requireEvents(foundIds);
            Compilation compilation = compilationMapper.toCompilation(newCompilationDto);
            if (compilation.getIsPinned() == null) {
                compilation.setIsPinned(false);
            }
            compilation.setEvents(new HashSet<>(events));
            Compilation saved = compilationRepository.save(compilation);
            log.info("Подборка создана с id={}", saved.getId());
            return toDtos(List.of(saved), users, ratings).getFirst();
        });
    }

    @Override
    @Transactional
    public void delete(Long compId) {
        log.info("Удаление подборки с id={}", compId);
        if (!compilationRepository.existsById(compId)) {
            throw new NotFoundException(String.format("Подборка с id = %d не существует", compId));
        }
        compilationRepository.deleteById(compId);
        log.info("Подборка с id={} удалена", compId);
    }

    @Override
    public CompilationDto update(Long compId, UpdateCompilationRequest updateRequest) {
        log.info("Обновление подборки с id={}", compId);
        Compilation snapshot = requireCompilation(compId);
        validateUpdateTitle(snapshot, updateRequest);
        Set<Long> initialIds = eventIds(snapshot.getEvents());
        List<Event> events = updateRequest.getEvents() == null ? List.copyOf(snapshot.getEvents())
                : requireEvents(updateRequest.getEvents());
        Map<Long, UserShortDto> users = findUsers(events);
        Map<Long, Double> ratings = analyzerClient.ratings(eventIds(events).stream().sorted().toList());
        return transactionTemplate.execute(status -> {
            Compilation compilation = requireCompilation(compId);
            validateUpdateTitle(compilation, updateRequest);
            if (updateRequest.getEvents() == null) {
                if (!eventIds(compilation.getEvents()).equals(initialIds)) {
                    throw new ConflictException("Состав подборки изменился во время обновления");
                }
            } else {
                compilation.setEvents(new HashSet<>(requireEvents(updateRequest.getEvents())));
            }
            compilationMapper.updateCompilation(compilation, updateRequest);
            Compilation updated = compilationRepository.save(compilation);
            log.info("Обновлена подборка с названием {}", updated.getTitle());
            return toDtos(List.of(updated), users, ratings).getFirst();
        });
    }

    @Override
    public List<CompilationDto> getCompilations(Boolean isPinned, Integer from, Integer size) {
        log.info("Получение подборок: isPinned={}, from={}, size={}", isPinned, from, size);
        Pageable pageable = PageRequest.of(from / size, size, Sort.by("id"));
        List<Compilation> compilations;
        if (isPinned != null) {
            compilations = compilationRepository.findAllByIsPinned(isPinned, pageable);
        } else {
            compilations = compilationRepository.findAll(pageable).getContent();
        }
        if (compilations.isEmpty()) {
            return List.of();
        }
        List<Long> ids = compilations.stream().map(Compilation::getId).toList();
        Map<Long, Compilation> loaded = compilationRepository.findAllWithEventsByIdIn(ids).stream()
                .collect(Collectors.toMap(Compilation::getId, compilation -> compilation));
        return toDtos(ids.stream().map(loaded::get).filter(Objects::nonNull).toList());
    }

    @Override
    public CompilationDto getCompilation(Long compId) {
        log.info("Получение подборки с id: {}", compId);
        Compilation compilation = compilationRepository.findByIdWithEvents(compId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Подборка с id %d не найдена", compId)
                ));
        return toDtos(List.of(compilation)).getFirst();
    }

    private List<CompilationDto> toDtos(List<Compilation> compilations) {
        List<Event> events = compilations.stream().flatMap(compilation -> compilation.getEvents().stream()).toList();
        return toDtos(compilations, displayEnrichment.findUsers(
                events.stream().map(Event::getInitiatorId).toList()),
                analyzerClient.ratings(eventIds(events).stream().sorted().toList()));
    }

    private Compilation requireCompilation(Long id) {
        return compilationRepository.findByIdWithEvents(id)
                .orElseThrow(() -> new NotFoundException(String.format("Подборка с id %d не найдена", id)));
    }

    private void validateTitle(String title) {
        if (compilationRepository.existsByTitle(title)) {
            throw new ConflictException(String.format("Подборка с названием %s уже существует", title));
        }
    }

    private void validateUpdateTitle(Compilation compilation, UpdateCompilationRequest request) {
        if (request.getTitle() != null && !request.getTitle().equals(compilation.getTitle())) {
            validateTitle(request.getTitle());
        }
    }

    private Set<Long> eventIds(Collection<Event> events) {
        return events.stream().map(Event::getId).collect(Collectors.toSet());
    }

    private List<Event> requireEvents(Set<Long> ids) {
        List<Event> events = ids.isEmpty() ? List.of() : eventRepository.findAllById(ids);
        if (!eventIds(events).containsAll(ids)) {
            throw new NotFoundException("Одно или несколько событий подборки не найдены");
        }
        return events;
    }

    private Map<Long, UserShortDto> findUsers(List<Event> events) {
        return displayEnrichment.findUsers(events.stream().map(Event::getInitiatorId).toList());
    }

    private List<CompilationDto> toDtos(List<Compilation> compilations, Map<Long, UserShortDto> users,
                                         Map<Long, Double> ratings) {
        return compilations.stream().map(compilation -> {
            Set<EventShortDto> events = compilation.getEvents().stream()
                    .sorted(Comparator.comparing(Event::getId))
                    .map(event -> {
                        UserShortDto initiator = users.get(event.getInitiatorId());
                        if (initiator == null) {
                            throw new NotFoundException("Пользователь с id=" + event.getInitiatorId() + " не найден");
                        }
                        return eventMapper.toShortDto(event, initiator, 0L, ratings.getOrDefault(event.getId(), 0.0));
                    }).collect(Collectors.toCollection(LinkedHashSet::new));
            return compilationMapper.toDto(compilation, events);
        }).toList();
    }
}
