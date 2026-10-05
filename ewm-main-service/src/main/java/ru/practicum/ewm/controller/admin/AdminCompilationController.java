package ru.practicum.ewm.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.service.CompilationService;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/compilations")
public class AdminCompilationController {

    private final CompilationService compilationService;

    @PostMapping
    public ResponseEntity<CompilationDto> createCompilation(
            @RequestBody @Valid NewCompilationDto newCompilationDto
    ) {
        log.info("Создание подборки мероприятий");
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(compilationService.create(newCompilationDto));
    }

    @DeleteMapping("/{compId}")
    public ResponseEntity<Void> deleteCompilation(@PathVariable Long compId) {
        log.info("Удаление подборки с id: {}", compId);
        compilationService.delete(compId);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{compId}")
    public ResponseEntity<CompilationDto> updateCompilation(
            @PathVariable Long compId,
            @RequestBody @Valid UpdateCompilationRequest updateRequest
    ) {
        log.info("Обновление подборки с id: {}", compId);
        return ResponseEntity
                .ok(compilationService.update(compId, updateRequest));
    }
}
