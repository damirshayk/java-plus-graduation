package ru.practicum.ewm.mapper;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.practicum.ewm.dto.compilation.CompilationDto;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;
import ru.practicum.ewm.model.Compilation;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;

class CompilationMapperTest {

    private final CompilationMapper mapper = Mappers.getMapper(CompilationMapper.class);

    @Test
    void shouldMapToDto() {
        Compilation compilation = new Compilation();
        compilation.setId(1L);
        compilation.setTitle("Test Compilation");
        compilation.setIsPinned(true);
        compilation.setEvents(new HashSet<>());

        CompilationDto dto = mapper.toDto(compilation);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getTitle()).isEqualTo("Test Compilation");
        assertThat(dto.getIsPinned()).isTrue();
        assertThat(dto.getEvents()).isEmpty();
    }

    @Test
    void shouldMapToCompilation() {
        NewCompilationDto dto = NewCompilationDto.builder()
                .title("Test Compilation")
                .isPinned(true)
                .build();

        Compilation compilation = mapper.toCompilation(dto);

        assertThat(compilation).isNotNull();
        assertThat(compilation.getId()).isNull();
        assertThat(compilation.getTitle()).isEqualTo("Test Compilation");
        assertThat(compilation.getIsPinned()).isTrue();
    }

    @Test
    void shouldUpdateCompilation() {
        Compilation compilation = new Compilation();
        compilation.setId(1L);
        compilation.setTitle("Old Title");
        compilation.setIsPinned(false);

        UpdateCompilationRequest request = new UpdateCompilationRequest();
        request.setTitle("New Title");
        request.setIsPinned(true);

        mapper.updateCompilation(compilation, request);

        assertThat(compilation.getId()).isEqualTo(1L);
        assertThat(compilation.getTitle()).isEqualTo("New Title");
        assertThat(compilation.getIsPinned()).isTrue();
    }

    @Test
    void shouldIgnoreNullValuesWhenUpdating() {
        Compilation compilation = new Compilation();
        compilation.setId(1L);
        compilation.setTitle("Old Title");

        UpdateCompilationRequest request = new UpdateCompilationRequest();

        mapper.updateCompilation(compilation, request);

        assertThat(compilation.getTitle()).isEqualTo("Old Title");
    }
}
