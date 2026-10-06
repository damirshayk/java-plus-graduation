package ru.practicum.ewm.dto;

import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.user.UserShortDto;

import static org.assertj.core.api.Assertions.assertThat;

class UserShortDtoTest {

    @Test
    void shouldHaveNoArgsConstructor() {
        UserShortDto dto = new UserShortDto();
        assertThat(dto).isNotNull();
    }

    @Test
    void shouldSetAndGetFields() {
        UserShortDto dto = new UserShortDto();
        dto.setId(1L);
        dto.setName("Test User");

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("Test User");
    }
}
