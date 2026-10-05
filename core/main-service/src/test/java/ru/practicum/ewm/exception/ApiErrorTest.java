package ru.practicum.ewm.exception;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorTest {

    @Test
    void shouldCreateApiError() {
        ApiError error = ApiError.builder()
                .errors(java.util.List.of("Error 1", "Error 2"))
                .status(HttpStatus.BAD_REQUEST.name())
                .reason("Invalid request")
                .message("Validation failed")
                .timestamp(LocalDateTime.now())
                .build();

        assertThat(error).isNotNull();
        assertThat(error.getErrors()).hasSize(2);
        assertThat(error.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.name());
        assertThat(error.getReason()).isEqualTo("Invalid request");
    }
}
