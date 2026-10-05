package ru.practicum.ewm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CompilationMapper;
import ru.practicum.ewm.model.Compilation;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.repository.CompilationRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.CompilationService;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Slf4j
@Service
@Transactional(readOnly = true)
public class CompilationServiceImpl implements CompilationService {

    private final CompilationMapper compilationMapper;
    private final CompilationRepository compilationRepository;
    private final EventRepository eventRepository;

    @Override
    @Transactional
    public CompilationDto create(NewCompilationDto newCompilationDto) {
        log.info("Создание подборки: {}", newCompilationDto);
        if (compilationRepository.existsByTitle(newCompilationDto.getTitle())) {
            throw new ConflictException(String.format("Подборка с названием %s уже существует", newCompilationDto.getTitle()));
        }
        Compilation compilation = compilationMapper.toCompilation(newCompilationDto);
        if (compilation.getIsPinned() == null) {
            compilation.setIsPinned(false);
        }
        Set<Event> eventEntities = new HashSet<>();
        if (newCompilationDto.getEvents() != null) {
            eventEntities.addAll(eventRepository.findAllById(newCompilationDto.getEvents()));
        }
        compilation.setEvents(eventEntities);
        Compilation saved = compilationRepository.save(compilation);
        log.info("Подборка создана с id={}", saved.getId());
        return compilationMapper.toDto(saved);
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
    @Transactional
    public CompilationDto update(Long compId, UpdateCompilationRequest updateRequest) {
        log.info("Обновление подборки с id={}", compId);
        Compilation compilation = compilationRepository.findById(compId)
                .orElseThrow(() -> new NotFoundException(String.format("Подборка с id %d не найдена", compId)));
        if (updateRequest.getTitle() != null &&
                !updateRequest.getTitle().equals(compilation.getTitle()) &&
                compilationRepository.existsByTitle(updateRequest.getTitle())) {
            throw new ConflictException(String.format("Подборка с названием %s уже существует", updateRequest.getTitle()));
        }
        compilationMapper.updateCompilation(compilation, updateRequest);
        if (updateRequest.getEvents() != null) {
            Set<Event> eventEntities = new HashSet<>();
            for (Long eventId : updateRequest.getEvents()) {
                Event event = eventRepository.findById(eventId)
                        .orElseThrow(() -> new NotFoundException("Event with id " + eventId + " was not found"));
                eventEntities.add(event);
            }
            compilation.setEvents(eventEntities);
        }
        Compilation updated = compilationRepository.save(compilation);
        log.info("Обновлена подборка с названием {}", updated.getTitle());
        return compilationMapper.toDto(updated);
    }

    @Override
    public List<CompilationDto> getCompilations(Boolean isPinned, Integer from, Integer size) {
        log.info("Получение подборок: isPinned={}, from={}, size={}", isPinned, from, size);
        Pageable pageable = PageRequest.of(from / size, size);
        List<Compilation> compilations;
        if (isPinned != null) {
            compilations = compilationRepository.findAllByIsPinned(isPinned, pageable);
        } else {
            compilations = compilationRepository.findAll(pageable).getContent();
        }
        return compilations.stream()
                .map(compilationMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    public CompilationDto getCompilation(Long compId) {
        log.info("Получение подборки с id: {}", compId);
        Compilation compilation = compilationRepository.findByIdWithEvents(compId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Подборка с id %d не найдена", compId)
                ));
        return compilationMapper.toDto(compilation);
    }
}
