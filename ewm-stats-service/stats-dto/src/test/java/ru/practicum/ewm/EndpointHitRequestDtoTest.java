package ru.practicum.ewm;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class EndpointHitRequestDtoTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @Test
    void shouldCreateValidDto() {
        LocalDateTime timestamp = LocalDateTime.now();

        EndpointHitRequestDto dto = EndpointHitRequestDto.builder()
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(timestamp)
                .build();

        assertThat(dto.getApp()).isEqualTo("ewm-main-service");
        assertThat(dto.getUri()).isEqualTo("/events/1");
        assertThat(dto.getIp()).isEqualTo("192.168.0.1");
        assertThat(dto.getTimestamp()).isEqualTo(timestamp);

        var violations = validator.validate(dto);
        assertThat(violations).isEmpty();
    }

    @Test
    void shouldFailValidationWhenAppIsBlank() {
        EndpointHitRequestDto dto = EndpointHitRequestDto.builder()
                .app("")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(LocalDateTime.now())
                .build();

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("app"));
    }

    @Test
    void shouldFailValidationWhenUriIsBlank() {
        EndpointHitRequestDto dto = EndpointHitRequestDto.builder()
                .app("ewm-main-service")
                .uri("")
                .ip("192.168.0.1")
                .timestamp(LocalDateTime.now())
                .build();

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("uri"));
    }

    @Test
    void shouldFailValidationWhenIpIsBlank() {
        EndpointHitRequestDto dto = EndpointHitRequestDto.builder()
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("")
                .timestamp(LocalDateTime.now())
                .build();

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("ip"));
    }

    @Test
    void shouldFailValidationWhenTimestampIsNull() {
        EndpointHitRequestDto dto = EndpointHitRequestDto.builder()
                .app("ewm-main-service")
                .uri("/events/1")
                .ip("192.168.0.1")
                .timestamp(null)
                .build();

        var violations = validator.validate(dto);
        assertThat(violations).isNotEmpty();
        assertThat(violations).anyMatch(v -> v.getPropertyPath().toString().equals("timestamp"));
    }
}
