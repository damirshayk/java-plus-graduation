package ru.practicum.ewm.service;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;

import java.util.List;

public interface CompilationService {

    CompilationDto create(@Valid NewCompilationDto newCompilationDto);

    void delete(Long compId);

    CompilationDto update(Long compId, @Valid UpdateCompilationRequest updateRequest);

    List<CompilationDto> getCompilations(Boolean isPinned, @PositiveOrZero Integer from, @Positive Integer size);

    CompilationDto getCompilation(@Positive Long compId);
}
