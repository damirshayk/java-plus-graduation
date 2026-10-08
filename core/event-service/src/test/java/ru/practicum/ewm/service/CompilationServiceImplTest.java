package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CompilationMapper;
import ru.practicum.ewm.mapper.EventMapper;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.model.Compilation;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.repository.CompilationRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.service.impl.CompilationServiceImpl;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompilationServiceImplTest {

    @Mock
    private CompilationRepository compilationRepository;

    @Mock
    private CompilationMapper compilationMapper;

    @Mock
    private EventRepository eventRepository;

    @Mock
    private EventMapper eventMapper;

    @Mock
    private UserDirectory userDirectory;

    @Mock
    private PlatformTransactionManager transactionManager;

    private CompilationServiceImpl compilationService;

    private Compilation compilation;
    private CompilationDto compilationDto;
    private NewCompilationDto newCompilationDto;

    @BeforeEach
    void setUp() {
        compilationService = new CompilationServiceImpl(compilationMapper, compilationRepository, eventRepository,
                eventMapper,
                new EventDisplayEnrichment(userDirectory, mock(ConfirmedRequestCounter.class),
                        2, 1, 10, 2, 50, 5000, 1), transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
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
        when(compilationMapper.toDto(any(Compilation.class), any())).thenReturn(compilationDto);

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

        when(compilationRepository.findByIdWithEvents(1L)).thenReturn(Optional.of(compilation));
        when(compilationRepository.existsByTitle("Updated Compilation")).thenReturn(false);
        doNothing().when(compilationMapper).updateCompilation(any(Compilation.class), any(UpdateCompilationRequest.class));
        when(compilationRepository.save(any(Compilation.class))).thenReturn(compilation);
        when(compilationMapper.toDto(any(Compilation.class), any())).thenReturn(compilationDto);

        CompilationDto result = compilationService.update(1L, request);

        assertThat(result).isNotNull();
        verify(compilationRepository).save(any(Compilation.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenUpdatingToExistingTitle() {
        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setTitle("Existing Title");

        when(compilationRepository.findByIdWithEvents(1L)).thenReturn(Optional.of(compilation));
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

        when(compilationRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(compilationRepository.findAllWithEventsByIdIn(List.of(1L))).thenReturn(compilations);
        when(compilationMapper.toDto(any(Compilation.class), any())).thenReturn(compilationDto);

        List<CompilationDto> result = compilationService.getCompilations(null, 0, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getTitle()).isEqualTo("Test Compilation");
    }

    @Test
    void shouldGetPinnedCompilations() {
        List<Compilation> compilations = List.of(compilation);

        when(compilationRepository.findAllByIsPinned(eq(true), any(PageRequest.class))).thenReturn(compilations);
        when(compilationRepository.findAllWithEventsByIdIn(List.of(1L))).thenReturn(compilations);
        when(compilationMapper.toDto(any(Compilation.class), any())).thenReturn(compilationDto);

        List<CompilationDto> result = compilationService.getCompilations(true, 0, 10);

        assertThat(result).hasSize(1);
    }

    @Test
    void shouldGetCompilationById() {
        when(compilationRepository.findByIdWithEvents(1L)).thenReturn(Optional.of(compilation));
        when(compilationMapper.toDto(any(Compilation.class), any())).thenReturn(compilationDto);

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

    @Test
    void updateShouldLoadAllRequestedEventsWithFixedBatchQueries() {
        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setEvents(Set.of(10L, 20L));
        Event first = Event.builder().id(10L).initiatorId(1L).build();
        Event second = Event.builder().id(20L).initiatorId(1L).build();
        UserShortDto user = new UserShortDto();
        user.setId(1L);
        when(compilationRepository.findByIdWithEvents(1L)).thenReturn(Optional.of(compilation));
        when(eventRepository.findAllById(request.getEvents())).thenReturn(List.of(first, second));
        when(userDirectory.findAll(List.of(1L))).thenReturn(Map.of(1L, user));
        when(compilationRepository.save(compilation)).thenReturn(compilation);

        compilationService.update(1L, request);

        assertThat(compilation.getEvents()).containsExactlyInAnyOrder(first, second);
        verify(eventRepository, times(2)).findAllById(request.getEvents());
        verify(eventRepository, never()).findById(anyLong());
    }

    @Test
    void allCompilationEventsShouldShareOneUserBatch() {
        Event first = Event.builder().id(10L).initiatorId(1L).build();
        Event second = Event.builder().id(20L).initiatorId(2L).build();
        compilation.setEvents(Set.of(first));
        Compilation other = Compilation.builder().id(2L).title("Другая подборка").events(Set.of(first, second)).build();
        UserShortDto firstUser = new UserShortDto();
        firstUser.setId(1L);
        UserShortDto secondUser = new UserShortDto();
        secondUser.setId(2L);
        EventShortDto firstDto = new EventShortDto();
        EventShortDto secondDto = new EventShortDto();
        when(compilationRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(compilation, other)));
        when(compilationRepository.findAllWithEventsByIdIn(List.of(1L, 2L))).thenReturn(List.of(other, compilation));
        when(userDirectory.findAll(any())).thenReturn(Map.of(1L, firstUser, 2L, secondUser));
        when(eventMapper.toShortDto(first, firstUser, 0L, 0L)).thenReturn(firstDto);
        when(eventMapper.toShortDto(second, secondUser, 0L, 0L)).thenReturn(secondDto);
        when(compilationMapper.toDto(compilation, Set.of(firstDto))).thenReturn(compilationDto);
        CompilationDto otherDto = new CompilationDto();
        when(compilationMapper.toDto(other, Set.of(firstDto, secondDto))).thenReturn(otherDto);

        assertThat(compilationService.getCompilations(null, 0, 10)).containsExactly(compilationDto, otherDto);

        verify(userDirectory).findAll(argThat(ids -> ids.size() == 2 && ids.containsAll(List.of(1L, 2L))));
        verifyNoMoreInteractions(userDirectory);
        verify(compilationRepository).findAllWithEventsByIdIn(List.of(1L, 2L));
    }

    @Test
    void compilationDeletedBetweenPageAndFetchMustBeOmittedWithoutLosingOrder() {
        Compilation other = Compilation.builder().id(2L).title("Оставшаяся").build();
        CompilationDto otherDto = new CompilationDto();
        otherDto.setId(2L);
        when(compilationRepository.findAll(any(PageRequest.class)))
                .thenReturn(new PageImpl<>(List.of(compilation, other)));
        when(compilationRepository.findAllWithEventsByIdIn(List.of(1L, 2L))).thenReturn(List.of(other));
        when(compilationMapper.toDto(eq(other), any())).thenReturn(otherDto);

        List<CompilationDto> result = assertDoesNotThrow(() -> compilationService.getCompilations(null, 0, 10));
        assertThat(result).containsExactly(otherDto);

        verify(compilationRepository).findAllWithEventsByIdIn(List.of(1L, 2L));
        verifyNoInteractions(userDirectory);
    }

    @Test
    void allCompilationsDeletedBetweenPageAndFetchMustReturnEmptyWithoutUserCall() {
        when(compilationRepository.findAll(any(PageRequest.class))).thenReturn(new PageImpl<>(List.of(compilation)));
        when(compilationRepository.findAllWithEventsByIdIn(List.of(1L))).thenReturn(List.of());

        List<CompilationDto> result = assertDoesNotThrow(() -> compilationService.getCompilations(null, 0, 10));
        assertThat(result).isEmpty();

        verifyNoInteractions(userDirectory);
    }
}
