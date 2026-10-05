package ru.practicum.ewm.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorHandlerTest {

    private final ErrorHandler errorHandler = new ErrorHandler();

    @Test
    void shouldHandleNotFoundException() {
        NotFoundException exception = new NotFoundException("Entity not found");
        ApiError error = errorHandler.handleNotFound(exception);

        assertThat(error.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.name());
        assertThat(error.getMessage()).isEqualTo("Entity not found");
        assertThat(error.getReason()).isEqualTo("Требуемый объект не найден.");
    }

    @Test
    void shouldHandleConflictException() {
        ConflictException exception = new ConflictException("Duplicate entry");
        ApiError error = errorHandler.handleConflict(exception);

        assertThat(error.getStatus()).isEqualTo(HttpStatus.CONFLICT.name());
        assertThat(error.getMessage()).isEqualTo("Duplicate entry");
        assertThat(error.getReason()).isEqualTo("Нарушено ограничение целостности данных.");
    }

    @Test
    void shouldHandleValidationException() {
        ValidationException exception = new ValidationException("Invalid data");
        ApiError error = errorHandler.handleValidation(exception);

        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.name());
        assertThat(error.getMessage()).isEqualTo("Invalid data");
    }
}
