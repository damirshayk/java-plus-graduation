package ru.practicum.ewm.service.impl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.UserMapper;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.UserRepository;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserMapper userMapper;

    @InjectMocks
    private UserServiceImpl userService;

    private User user;
    private UserDto userDto;
    private NewUserRequest newUserRequest;

    @BeforeEach
    void setUp() {
        user = new User();
        user.setId(1L);
        user.setName("Test User");
        user.setEmail("test@example.com");

        userDto = new UserDto();
        userDto.setId(1L);
        userDto.setName("Test User");
        userDto.setEmail("test@example.com");

        newUserRequest = new NewUserRequest();
        newUserRequest.setName("Test User");
        newUserRequest.setEmail("test@example.com");
    }

    @Test
    void shouldRegisterUser() {
        when(userRepository.existsByEmail(newUserRequest.getEmail())).thenReturn(false);
        when(userMapper.toUser(newUserRequest)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        UserDto result = userService.registerUser(newUserRequest);

        assertThat(result).isNotNull();
        assertThat(result.getId()).isEqualTo(1L);
        assertThat(result.getName()).isEqualTo("Test User");
        assertThat(result.getEmail()).isEqualTo("test@example.com");
        verify(userRepository).save(any(User.class));
    }

    @Test
    void shouldThrowConflictExceptionWhenEmailExists() {
        when(userRepository.existsByEmail(newUserRequest.getEmail())).thenReturn(true);

        assertThatThrownBy(() -> userService.registerUser(newUserRequest))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Пользователь с таким адресом электронной почты уже существует");

        verify(userRepository, never()).save(any(User.class));
    }

    @Test
    void shouldGetUsersWithIds() {
        List<Long> ids = List.of(1L, 2L);
        List<User> users = List.of(user);
        List<UserDto> userDtos = List.of(userDto);

        when(userRepository.findAllByIdIn(ids)).thenReturn(users);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        List<UserDto> result = userService.getUsers(ids, 0, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        assertThat(result.get(0).getName()).isEqualTo("Test User");
        verify(userRepository, never()).findAll(any(PageRequest.class));
    }

    @Test
    void shouldGetUsersWithPaginationWhenIdsEmpty() {
        Page<User> page = new PageImpl<>(List.of(user));
        List<UserDto> userDtos = List.of(userDto);

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        List<UserDto> result = userService.getUsers(null, 0, 10);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getId()).isEqualTo(1L);
        verify(userRepository).findAll(any(PageRequest.class));
    }

    @Test
    void shouldGetUsersWithPaginationWhenIdsEmptyList() {
        Page<User> page = new PageImpl<>(List.of(user));
        List<UserDto> userDtos = List.of(userDto);

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        List<UserDto> result = userService.getUsers(List.of(), 0, 10);

        assertThat(result).hasSize(1);
        verify(userRepository).findAll(any(PageRequest.class));
    }

    @Test
    void shouldGetUsersWithCorrectPaginationParameters() {
        Page<User> page = new PageImpl<>(List.of(user));

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        userService.getUsers(null, 5, 10);

        verify(userRepository).findAll(PageRequest.of(0, 10));
    }

    @Test
    void shouldGetUsersWithCorrectPaginationParametersWhenFromIsNotMultipleOfSize() {
        Page<User> page = new PageImpl<>(List.of(user));

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        userService.getUsers(null, 7, 10);

        verify(userRepository).findAll(PageRequest.of(0, 10));
    }

    @Test
    void shouldGetUsersWithCorrectPaginationParametersWhenFromIsMultipleOfSize() {
        Page<User> page = new PageImpl<>(List.of(user));

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        userService.getUsers(null, 20, 10);

        verify(userRepository).findAll(PageRequest.of(2, 10));
    }

    @Test
    void shouldDeleteUser() {
        when(userRepository.existsById(1L)).thenReturn(true);
        doNothing().when(userRepository).deleteById(1L);

        userService.deleteUser(1L);

        verify(userRepository).deleteById(1L);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDeletingNonExistingUser() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User with id 99 was not found");

        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void shouldReturnEmptyListWhenNoUsersFound() {
        when(userRepository.findAllByIdIn(List.of(1L, 2L))).thenReturn(List.of());

        List<UserDto> result = userService.getUsers(List.of(1L, 2L), 0, 10);

        assertThat(result).isEmpty();
        verify(userMapper, never()).toDto(any(User.class));
    }

    @Test
    void shouldRegisterUserWithEmailThatContainsUppercase() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("TEST@EXAMPLE.COM");

        when(userRepository.existsByEmail("TEST@EXAMPLE.COM")).thenReturn(false);
        when(userMapper.toUser(request)).thenReturn(user);
        when(userRepository.save(any(User.class))).thenReturn(user);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        UserDto result = userService.registerUser(request);

        assertThat(result).isNotNull();
        verify(userRepository).existsByEmail("TEST@EXAMPLE.COM");
    }

    @Test
    void shouldHandleUserWithNameContainingSpaces() {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User With Spaces");
        request.setEmail("spaces@example.com");

        User userWithSpaces = new User();
        userWithSpaces.setId(2L);
        userWithSpaces.setName("Test User With Spaces");
        userWithSpaces.setEmail("spaces@example.com");

        UserDto expectedDto = new UserDto();
        expectedDto.setId(2L);
        expectedDto.setName("Test User With Spaces");
        expectedDto.setEmail("spaces@example.com");

        when(userRepository.existsByEmail(request.getEmail())).thenReturn(false);
        when(userMapper.toUser(request)).thenReturn(userWithSpaces);
        when(userRepository.save(any(User.class))).thenReturn(userWithSpaces);
        when(userMapper.toDto(any(User.class))).thenReturn(expectedDto);

        UserDto result = userService.registerUser(request);

        assertThat(result).isNotNull();
        assertThat(result.getName()).isEqualTo("Test User With Spaces");
    }
}
