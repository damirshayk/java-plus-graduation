package ru.practicum.ewm.service.impl;

import feign.FeignException;
import feign.Request;
import feign.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.client.UserDataCleanupClient;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.mapper.UserMapper;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.UserRepository;

import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

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

    @Mock
    private UserDataCleanupClient cleanupClient;

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

        verify(userRepository).findAll(PageRequest.of(0, 10, Sort.by("id")));
    }

    @Test
    void shouldGetUsersWithCorrectPaginationParametersWhenFromIsNotMultipleOfSize() {
        Page<User> page = new PageImpl<>(List.of(user));

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        userService.getUsers(null, 7, 10);

        verify(userRepository).findAll(PageRequest.of(0, 10, Sort.by("id")));
    }

    @Test
    void shouldGetUsersWithCorrectPaginationParametersWhenFromIsMultipleOfSize() {
        Page<User> page = new PageImpl<>(List.of(user));

        when(userRepository.findAll(any(PageRequest.class))).thenReturn(page);
        when(userMapper.toDto(any(User.class))).thenReturn(userDto);

        userService.getUsers(null, 20, 10);

        verify(userRepository).findAll(PageRequest.of(2, 10, Sort.by("id")));
    }

    @Test
    void shouldDeleteUser() {
        when(userRepository.existsById(1L)).thenReturn(true);
        doNothing().when(userRepository).deleteById(1L);

        userService.deleteUser(1L);

        InOrder order = inOrder(userRepository, cleanupClient);
        order.verify(userRepository).existsById(1L);
        order.verify(cleanupClient).deleteUserData(1L);
        order.verify(userRepository).deleteById(1L);
    }

    @Test
    void shouldThrowNotFoundExceptionWhenDeletingNonExistingUser() {
        when(userRepository.existsById(99L)).thenReturn(false);

        assertThatThrownBy(() -> userService.deleteUser(99L))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining("User with id 99 was not found");

        verify(userRepository, never()).deleteById(anyLong());
        verifyNoInteractions(cleanupClient);
    }

    @Test
    void shouldRetainUserWhenCleanupIsUnavailable() {
        when(userRepository.existsById(1L)).thenReturn(true);
        doThrow(cleanupError(503)).when(cleanupClient).deleteUserData(1L);

        assertThatThrownBy(() -> userService.deleteUser(1L))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(userRepository, never()).deleteById(anyLong());
        verify(cleanupClient).deleteUserData(1L);
    }

    @Test
    void shouldRetainUserWhenCleanupConnectionFails() {
        when(userRepository.existsById(1L)).thenReturn(true);
        doThrow(cleanupError(-1)).when(cleanupClient).deleteUserData(1L);

        assertThatThrownBy(() -> userService.deleteUser(1L))
                .isInstanceOf(ServiceUnavailableException.class);

        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void shouldPropagateOtherCleanupErrors() {
        when(userRepository.existsById(1L)).thenReturn(true);
        FeignException failure = cleanupError(400);
        doThrow(failure).when(cleanupClient).deleteUserData(1L);

        assertThatThrownBy(() -> userService.deleteUser(1L)).isSameAs(failure);
        verify(userRepository, never()).deleteById(anyLong());
    }

    @Test
    void shouldAllowExplicitRetryAfterCleanupFailure() {
        when(userRepository.existsById(1L)).thenReturn(true);
        doThrow(cleanupError(503)).doNothing().when(cleanupClient).deleteUserData(1L);

        assertThatThrownBy(() -> userService.deleteUser(1L))
                .isInstanceOf(ServiceUnavailableException.class);
        userService.deleteUser(1L);

        verify(cleanupClient, times(2)).deleteUserData(1L);
        verify(userRepository).deleteById(1L);
    }

    @Test
    void shouldGetInternalUser() {
        UserShortDto shortDto = new UserShortDto();
        shortDto.setId(user.getId());
        shortDto.setName(user.getName());
        when(userRepository.findById(1L)).thenReturn(java.util.Optional.of(user));
        when(userMapper.toShortDto(user)).thenReturn(shortDto);

        assertThat(userService.getUser(1L)).isSameAs(shortDto);
    }

    @Test
    void shouldRejectMissingInternalUser() {
        when(userRepository.findById(99L)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> userService.getUser(99L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldNotQueryForEmptyInternalLookup() {
        assertThat(userService.findUsers(List.of())).isEmpty();
        verifyNoInteractions(userRepository, userMapper);
    }

    @Test
    void shouldLookupDistinctIdsWithOneQuery() {
        UserShortDto shortDto = new UserShortDto();
        shortDto.setId(user.getId());
        when(userRepository.findAllByIdIn(List.of(1L, 99L))).thenReturn(List.of(user));
        when(userMapper.toShortDto(user)).thenReturn(shortDto);

        assertThat(userService.findUsers(List.of(1L, 99L, 1L))).containsExactly(shortDto);
        verify(userRepository).findAllByIdIn(List.of(1L, 99L));
        verifyNoMoreInteractions(userRepository);
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

    private FeignException cleanupError(int status) {
        Request request = Request.create(Request.HttpMethod.DELETE,
                "http://event-service/internal/users/1/data", Map.of(), null, StandardCharsets.UTF_8, null);
        return FeignException.errorStatus("UserDataCleanupClient#deleteUserData", Response.builder()
                .status(status).reason("Ошибка").headers(Map.of()).request(request).build());
    }
}
