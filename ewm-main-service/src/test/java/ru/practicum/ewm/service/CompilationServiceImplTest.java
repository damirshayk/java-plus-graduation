package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CompilationMapper;
import ru.practicum.ewm.model.Compilation;
import ru.practicum.ewm.repository.CompilationRepository;
import ru.practicum.ewm.service.impl.CompilationServiceImpl;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompilationServiceImplTest {

    @Mock
    private CompilationRepository compilationRepository;

    @Mock
    private CompilationMapper compilationMapper;

    @InjectMocks
    private CompilationServiceImpl compilationService;

    private Compilation compilation;
    private CompilationDto compilationDto;
    private NewCompilationDto newCompilationDto;

    @BeforeEach
    void setUp() {
        compilation = Compilation.builder()
                .id(1L)
                .title("Test Compilation")
                .isPinned(true)
                .build();

        compilationDto = new CompilationDto();
        compilationDto.setId(1L);
        compilationDto.setTitle("Test Compilation");
        compilationDto.setIsPinned(true);

        newCompilationDto = NewCompilationDto.builder()
                .title("Test Compilation")
                .isPinned(true)
                .build();
    }

    @Test
    void shouldCreateCompilation() {
        when(compilationRepository.existsByTitle(newCompilationDto.getTitle())).thenReturn(false);
        when(compilationMapper.toCompilation(newCompilationDto)).thenReturn(compilation);
        when(compilationRepository.save(any(Compilation.class))).thenReturn(compilation);
        when(compilationMapper.toDto(any(Compilation.class))).thenReturn(compilationDto);

        CompilationDto result = compilationService.create(newCompilationDto);

        assertThat(result).isNotNull();
        assertThat(result.getTitle()).isEqualTo("Test Compilation");
        verify(compilationRepository).save(any(Compilation.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenTitleExists() {
        when(compilationRepository.existsByTitle(newCompilationDto.getTitle())).thenReturn(true);

        assertThatThrownBy(() -> compilationService.create(newCompilationDto))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Подборка с названием Test Compilation уже существует");

        verify(compilationRepository, never()).save(any(Compilation.class));
    }

    @Test
    void shouldDeleteCompilation() {
        when(compilationRepository.existsById(1L)).thenReturn(true);
        doNothing().when(compilationRepository).deleteById(1L);

        compilationService.delete(1L);

        verify(compilationRepository).deleteById(1L);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDeletingNonExisting() {
        when(compilationRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> compilationService.delete(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Подборка с id = 99 не существует");

        verify(compilationRepository, never()).deleteById(anyLong());
    }

    @Test
    void shouldUpdateCompilation() {
        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setTitle("Updated Compilation");
        request.setIsPinned(false);

        when(compilationRepository.findById(1L)).thenReturn(Optional.of(compilation));
        when(compilationRepository.existsByTitle("Updated Compilation")).thenReturn(false);
        doNothing().when(compilationMapper).updateCompilation(any(Compilation.class), any(UpdateCompilationRequest.class));
        when(compilationRepository.save(any(Compilation.class))).thenReturn(compilation);
        when(compilationMapper.toDto(any(Compilation.class))).thenReturn(compilationDto);

        CompilationDto result = compilationService.update(1L, request);

        assertThat(result).isNotNull();
        verify(compilationRepository).save(any(Compilation.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenUpdatingToExistingTitle() {
        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setTitle("Existing Title");

        when(compilationRepository.findById(1L)).thenReturn(Optional.of(compilation));
        when(compilationRepository.existsByTitle("Existing Title")).thenReturn(true);

        assertThatThrownBy(() -> compilationService.update(1L, request))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Подборка с названием Existing Title уже существует");

        verify(compilationRepository, never()).save(any(Compilation.class));
    }

    @Test
    void shouldGetCompilations() {
        List<Compilation> compilations = List.of(compilation);
        Page<Compilation> page = new PageImpl<>(compilations);

        when(compilationRepository.findAll(PageRequest.of(0, 10))).thenReturn(page);
        when(compilationMapper.toDto(any(Compilation.class))).thenReturn(compilationDto);

        List<CompilationDto> result = compilationService.getCompilations(null, 0, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Test Compilation");
    }

    @Test
    void shouldGetPinnedCompilations() {
        List<Compilation> compilations = List.of(compilation);

        when(compilationRepository.findAllByIsPinned(eq(true), any(PageRequest.class))).thenReturn(compilations);
        when(compilationMapper.toDto(any(Compilation.class))).thenReturn(compilationDto);

        List<CompilationDto> result = compilationService.getCompilations(true, 0, 10);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldGetCompilationById() {
        when(compilationRepository.findByIdWithEvents(1L)).thenReturn(Optional.of(compilation));
        when(compilationMapper.toDto(any(Compilation.class))).thenReturn(compilationDto);

        CompilationDto result = compilationService.getCompilation(1L);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenGettingNonExisting() {
        when(compilationRepository.findByIdWithEvents(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> compilationService.getCompilation(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("Подборка с id 99 не найдена");
    }
}
