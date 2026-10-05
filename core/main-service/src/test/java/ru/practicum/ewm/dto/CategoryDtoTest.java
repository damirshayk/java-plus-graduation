package ru.practicum.ewm.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.category.CategoryDto;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class CategoryDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldValidateValidCategoryDto() {
        CategoryDto dto = new CategoryDto();
        dto.setName("Test Category");

        Set<ConstraintViolation<CategoryDto>> violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldFailWhenNameIsBlank() {
        CategoryDto dto = new CategoryDto();
        dto.setName("");

        Set<ConstraintViolation<CategoryDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void shouldFailWhenNameExceedsMaxLength() {
        CategoryDto dto = new CategoryDto();
        dto.setName("a".repeat(51));

        Set<ConstraintViolation<CategoryDto>> violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }
}
