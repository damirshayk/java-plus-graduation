package ru.practicum.ewm.dto;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.practicum.ewm.dto.event.NewEventDto;
import ru.practicum.ewm.dto.event.UpdateEventAdminRequest;
import ru.practicum.ewm.dto.event.UpdateEventUserRequest;
import ru.practicum.ewm.model.Location;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDtoValidationTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 11, 12, 0);
    private final Validator validator = Validation.byDefaultProvider().configure()
            .clockProvider(() -> Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC))
            .buildValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(classes = {NewEventDto.class, UpdateEventUserRequest.class, UpdateEventAdminRequest.class})
    void currentDateShouldBeInvalid(Class<?> dtoType) {
        assertFalse(validator.validateValue(dtoType, "eventDate", NOW).isEmpty());
    }

    @ParameterizedTest
    @ValueSource(classes = {NewEventDto.class, UpdateEventUserRequest.class, UpdateEventAdminRequest.class})
    void futureDateShouldReachServiceValidation(Class<?> dtoType) {
        assertTrue(validator.validateValue(dtoType, "eventDate", NOW.plusMinutes(30)).isEmpty());
    }

    @Test
    void missingDateShouldBeAllowedOnlyForUpdates() {
        assertFalse(validator.validateValue(NewEventDto.class, "eventDate", null).isEmpty());
        assertTrue(validator.validateValue(UpdateEventUserRequest.class, "eventDate", null).isEmpty());
        assertTrue(validator.validateValue(UpdateEventAdminRequest.class, "eventDate", null).isEmpty());
    }

    @Test
    void locationWithoutLongitudeShouldBeInvalid() {
        NewEventDto dto = new NewEventDto();
        dto.setAnnotation("Достаточно длинная аннотация события");
        dto.setCategory(1L);
        dto.setDescription("Достаточно длинное описание события");
        dto.setEventDate(LocalDateTime.now().plusDays(1));
        dto.setLocation(new Location(55.75F, null));
        dto.setTitle("Новое событие");

        assertFalse(validator.validate(dto).isEmpty());
    }
}
