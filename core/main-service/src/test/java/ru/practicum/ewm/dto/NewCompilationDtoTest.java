package ru.practicum.ewm.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.compilation.NewCompilationDto;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NewCompilationDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldValidateValidNewCompilationDto() {
        NewCompilationDto dto = NewCompilationDto.builder()
                .title("Test Compilation")
                .isPinned(true)
                .build();

        Set<ConstraintViolation<NewCompilationDto>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldFailWhenTitleIsBlank() {
        NewCompilationDto dto = NewCompilationDto.builder()
                .title("")
                .build();

        Set<ConstraintViolation<NewCompilationDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("title"));
    }

    @Test
    void shouldFailWhenTitleExceedsMaxLength() {
        NewCompilationDto dto = NewCompilationDto.builder()
                .title("a".repeat(51))
                .build();

        Set<ConstraintViolation<NewCompilationDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("title"));
    }

    @Test
    void shouldHaveDefaultPinnedFalse() {
        NewCompilationDto dto = NewCompilationDto.builder()
                .title("Test")
                .build();

        assertThat(dto.getIsPinned()).isFalse();
    }
}
