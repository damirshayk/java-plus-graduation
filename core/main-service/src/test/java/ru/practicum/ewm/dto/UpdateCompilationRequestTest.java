package ru.practicum.ewm.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.compilation.UpdateCompilationRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class UpdateCompilationRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldValidateValidUpdateCompilationRequest() {
        UpdateCompilationRequest dto = new UpdateCompilationRequest();
        dto.setTitle("Updated Compilation");
        dto.setIsPinned(true);

        Set<ConstraintViolation<UpdateCompilationRequest>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }
}
