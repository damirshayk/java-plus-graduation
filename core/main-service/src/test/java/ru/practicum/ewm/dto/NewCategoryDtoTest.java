package ru.practicum.ewm.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.category.NewCategoryDto;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NewCategoryDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldValidateValidNewCategoryDto() {
        NewCategoryDto dto = new NewCategoryDto();
        dto.setName("New Category");

        Set<ConstraintViolation<NewCategoryDto>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldFailWhenNameIsBlank() {
        NewCategoryDto dto = new NewCategoryDto();
        dto.setName("");

        Set<ConstraintViolation<NewCategoryDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
    }
}