package ru.practicum.ewm.dto;

import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.user.UserDto;

import static org.assertj.core.api.Assertions.assertThat;

class UserDtoTest {

    @Test
    void shouldHaveNoArgsConstructor() {
        UserDto dto = new UserDto();
        assertThat(dto).isNotNull();
    }

    @Test
    void shouldSetAndGetFields() {
        UserDto dto = new UserDto();
        dto.setId(1L);
        dto.setName("Test User");
        dto.setEmail("test@example.com");

        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("Test User");
        assertThat(dto.getEmail()).isEqualTo("test@example.com");
    }
}
