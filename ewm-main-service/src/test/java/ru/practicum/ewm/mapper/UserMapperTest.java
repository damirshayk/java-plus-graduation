package ru.practicum.ewm.mapper;

import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;
import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.model.User;

import static org.assertj.core.api.Assertions.assertThat;

class UserMapperTest {

    private final UserMapper mapper = Mappers.getMapper(UserMapper.class);

    @Test
    void shouldMapToDto() {
        User user = new User();
        user.setId(1L);
        user.setName("Test User");
        user.setEmail("test@example.com");

        UserDto dto = mapper.toDto(user);

        assertThat(dto).isNotNull();
        assertThat(dto.getId()).isEqualTo(1L);
        assertThat(dto.getName()).isEqualTo("Test User");
        assertThat(dto.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void shouldMapToUser() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("test@example.com");

        User user = mapper.toUser(request);

        assertThat(user).isNotNull();
        assertThat(user.getId()).isNull();
        assertThat(user.getName()).isEqualTo("Test User");
        assertThat(user.getEmail()).isEqualTo("test@example.com");
    }

    @Test
    void shouldUpdateUser() {
        User user = new User();
        user.setId(1L);
        user.setName("Old Name");
        user.setEmail("old@example.com");

        UserDto dto = new UserDto();
        dto.setName("New Name");
        dto.setEmail("new@example.com");

        mapper.updateUser(user, dto);

        assertThat(user.getId()).isEqualTo(1L);
        assertThat(user.getName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("new@example.com");
    }

    @Test
    void shouldIgnoreNullValuesWhenUpdating() {
        User user = new User();
        user.setId(1L);
        user.setName("Old Name");
        user.setEmail("old@example.com");

        UserDto dto = new UserDto();
        dto.setName("New Name");

        mapper.updateUser(user, dto);

        assertThat(user.getId()).isEqualTo(1L);
        assertThat(user.getName()).isEqualTo("New Name");
        assertThat(user.getEmail()).isEqualTo("old@example.com");
    }

    @Test
    void shouldMapNullUserToNullDto() {
        UserDto dto = mapper.toDto(null);
        assertThat(dto).isNull();
    }

    @Test
    void shouldMapNullRequestToNullUser() {
        User user = mapper.toUser(null);
        assertThat(user).isNull();
    }
}
