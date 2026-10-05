package ru.practicum.ewm.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.user.NewUserRequest;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class NewUserRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    void shouldValidateValidNewUserRequest() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("test@example.com");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldFailWhenNameIsBlank() {
        NewUserRequest request = new NewUserRequest();
        request.setName("");
        request.setEmail("test@example.com");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void shouldFailWhenNameIsTooShort() {
        NewUserRequest request = new NewUserRequest();
        request.setName("A");
        request.setEmail("test@example.com");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void shouldFailWhenNameExceedsMaxLength() {
        NewUserRequest request = new NewUserRequest();
        request.setName("a".repeat(251));
        request.setEmail("test@example.com");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("name"));
    }

    @Test
    void shouldFailWhenEmailIsBlank() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    void shouldFailWhenEmailIsInvalid() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("invalid-email");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    void shouldFailWhenEmailIsTooShort() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("a@b.c");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    void shouldFailWhenEmailExceedsMaxLength() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("a".repeat(245) + "@example.com");

        Set<ConstraintViolation<NewUserRequest>> violations = validator.validate(request);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("email"));
    }

    @Test
    void shouldHaveNoArgsConstructor() {
        NewUserRequest request = new NewUserRequest();
        assertThat(request).isNotNull();
    }

    @Test
    void shouldSetAndGetFields() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("test@example.com");

        assertThat(request.getName()).isEqualTo("Test User");
        assertThat(request.getEmail()).isEqualTo("test@example.com");
    }
}
