package ru.practicum.ewm.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.controller.admin.AdminUsersController;
import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.service.UserService;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AdminUsersController.class)
class AdminUsersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private UserService userService;

    @Test
    void shouldGetUsers() throws Exception {
        UserDto userDto = new UserDto();
        userDto.setId(1L);
        userDto.setName("Test User");
        userDto.setEmail("test@example.com");

        when(userService.getUsers(null, 0, 10)).thenReturn(List.of(userDto));

        mockMvc.perform(get("/admin/users")
                        .param("from", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[0].name").value("Test User"))
                .andExpect(jsonPath("$[0].email").value("test@example.com"));

        verify(userService).getUsers(null, 0, 10);
    }

    @Test
    void shouldGetUsersWithIds() throws Exception {
        UserDto userDto1 = new UserDto();
        userDto1.setId(1L);
        userDto1.setName("User 1");
        userDto1.setEmail("user1@example.com");

        UserDto userDto2 = new UserDto();
        userDto2.setId(2L);
        userDto2.setName("User 2");
        userDto2.setEmail("user2@example.com");

        List<Long> ids = List.of(1L, 2L);
        when(userService.getUsers(ids, 0, 10)).thenReturn(List.of(userDto1, userDto2));

        mockMvc.perform(get("/admin/users")
                        .param("ids", "1", "2")
                        .param("from", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L))
                .andExpect(jsonPath("$[1].id").value(2L));

        verify(userService).getUsers(ids, 0, 10);
    }

    @Test
    void shouldGetUsersWithDefaultParameters() throws Exception {
        UserDto userDto = new UserDto();
        userDto.setId(1L);
        userDto.setName("Test User");
        userDto.setEmail("test@example.com");

        when(userService.getUsers(null, 0, 10)).thenReturn(List.of(userDto));

        mockMvc.perform(get("/admin/users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(1L));

        verify(userService).getUsers(null, 0, 10);
    }

    @Test
    void shouldRegisterUser() throws Exception {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("test@example.com");

        UserDto response = new UserDto();
        response.setId(1L);
        response.setName("Test User");
        response.setEmail("test@example.com");

        when(userService.registerUser(any(NewUserRequest.class))).thenReturn(response);

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(1L))
                .andExpect(jsonPath("$.name").value("Test User"))
                .andExpect(jsonPath("$.email").value("test@example.com"));

        verify(userService).registerUser(any(NewUserRequest.class));
    }

    @Test
    void shouldReturnBadRequestWhenRegisteringUserWithInvalidEmail() throws Exception {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("invalid-email");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).registerUser(any(NewUserRequest.class));
    }

    @Test
    void shouldReturnBadRequestWhenRegisteringUserWithBlankName() throws Exception {
        NewUserRequest request = new NewUserRequest();
        request.setName("");
        request.setEmail("test@example.com");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).registerUser(any(NewUserRequest.class));
    }

    @Test
    void shouldReturnBadRequestWhenRegisteringUserWithNameTooShort() throws Exception {
        NewUserRequest request = new NewUserRequest();
        request.setName("A");
        request.setEmail("test@example.com");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).registerUser(any(NewUserRequest.class));
    }

    @Test
    void shouldReturnBadRequestWhenRegisteringUserWithNameTooLong() throws Exception {
        NewUserRequest request = new NewUserRequest();
        request.setName("a".repeat(251));
        request.setEmail("test@example.com");

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());

        verify(userService, never()).registerUser(any(NewUserRequest.class));
    }

    @Test
    void shouldDeleteUser() throws Exception {
        doNothing().when(userService).deleteUser(1L);

        mockMvc.perform(delete("/admin/users/{userId}", 1L))
                .andExpect(status().isNoContent());

        verify(userService).deleteUser(1L);
    }

    @Test
    void shouldReturnNotFoundWhenDeletingNonExistingUser() throws Exception {
        doThrow(new ru.practicum.ewm.exception.NotFoundException("User with id 99 was not found"))
                .when(userService).deleteUser(99L);

        mockMvc.perform(delete("/admin/users/{userId}", 99L))
                .andExpect(status().isNotFound());

        verify(userService).deleteUser(99L);
    }

    @Test
    void shouldReturnConflictWhenRegisteringUserWithDuplicateEmail() throws Exception {
        NewUserRequest request = new NewUserRequest();
        request.setName("Test User");
        request.setEmail("existing@example.com");

        when(userService.registerUser(any(NewUserRequest.class)))
                .thenThrow(new ru.practicum.ewm.exception.ConflictException("Пользователь с таким адресом электронной почты уже существует"));

        mockMvc.perform(post("/admin/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());

        verify(userService).registerUser(any(NewUserRequest.class));
    }

    @Test
    void shouldHandleEmptyIdsList() throws Exception {
        UserDto userDto = new UserDto();
        userDto.setId(1L);
        userDto.setName("Test User");
        userDto.setEmail("test@example.com");

        when(userService.getUsers(List.of(), 0, 10)).thenReturn(List.of(userDto));

        mockMvc.perform(get("/admin/users")
                        .param("ids", "")
                        .param("from", "0")
                        .param("size", "10"))
                .andExpect(status().isOk());

        verify(userService).getUsers(List.of(), 0, 10);
    }
}