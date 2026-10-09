package ru.practicum.ewm.controller;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import ru.practicum.ewm.controller.internal.InternalUsersController;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.service.UserService;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalUsersController.class)
class InternalUsersControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private UserService userService;

    @Test
    void shouldReturnShortUser() throws Exception {
        UserShortDto user = new UserShortDto();
        user.setId(7L);
        user.setName("Пользователь");
        when(userService.getUser(7L)).thenReturn(user);

        mockMvc.perform(get("/internal/users/7"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(7))
                .andExpect(jsonPath("$.name").value("Пользователь"))
                .andExpect(jsonPath("$.email").doesNotExist());
    }

    @Test
    void shouldReturnNotFound() throws Exception {
        when(userService.getUser(7L)).thenThrow(new NotFoundException("Пользователь не найден"));

        mockMvc.perform(get("/internal/users/7")).andExpect(status().isNotFound());
    }

    @Test
    void shouldReturnOnlyFoundUsers() throws Exception {
        UserShortDto user = new UserShortDto();
        user.setId(7L);
        user.setName("Пользователь");
        when(userService.findUsers(List.of(7L, 8L))).thenReturn(List.of(user));

        mockMvc.perform(post("/internal/users/lookup")
                        .contentType(MediaType.APPLICATION_JSON).content("[7,8]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(7));
    }

    @Test
    void shouldReturnEmptyLookup() throws Exception {
        when(userService.findUsers(List.of())).thenReturn(List.of());

        mockMvc.perform(post("/internal/users/lookup")
                        .contentType(MediaType.APPLICATION_JSON).content("[]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
