package ru.practicum.ewm.controller;

import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.controller.admin.AdminEventController;
import ru.practicum.ewm.controller.priv.PrivateEventController;
import ru.practicum.ewm.controller.publ.PublicEventController;
import ru.practicum.ewm.exception.ErrorHandler;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.service.EventService;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class EventControllerTest {
    @Mock
    private EventService eventService;
    @Mock
    private StatsClient statsClient;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(
                validated(new PrivateEventController(eventService)),
                validated(new AdminEventController(eventService)),
                validated(new PublicEventController(eventService, statsClient))
        ).setControllerAdvice(new ErrorHandler()).build();
    }

    @SuppressWarnings("unchecked")
    private <T> T validated(T controller) {
        ProxyFactory proxyFactory = new ProxyFactory(controller);
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new MethodValidationInterceptor(
                Validation.buildDefaultValidatorFactory()));
        return (T) proxyFactory.getProxy();
    }

    @Test
    void privateEventsEndpointShouldReturnOk() throws Exception {
        mockMvc.perform(get("/users/1/events"))
                .andExpect(status().isOk());
    }

    @Test
    void adminUpdateEndpointShouldReturnOk() throws Exception {
        mockMvc.perform(patch("/admin/events/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void publicEventEndpointShouldReturnOk() throws Exception {
        mockMvc.perform(get("/events/10"))
                .andExpect(status().isOk());

        verify(statsClient).hit(any(EndpointHitRequestDto.class));
    }

    @Test
    void invalidEventBodyShouldReturnApiError() throws Exception {
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/users/1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.reason").value("Некорректный запрос."));
    }

    @Test
    void createEventWithPastDateShouldReturnBadRequest() throws Exception {
        mockMvc.perform(post("/users/1/events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"annotation\":\"Достаточно длинная аннотация события\","
                                + "\"category\":1,"
                                + "\"description\":\"Достаточно длинное описание события\","
                                + "\"eventDate\":\"2020-12-31 15:10:05\","
                                + "\"location\":{\"lat\":55.75,\"lon\":37.62},"
                                + "\"title\":\"Новое событие\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));

        verifyNoInteractions(eventService);
    }

    @Test
    void userUpdateWithPastDateShouldReturnBadRequest() throws Exception {
        mockMvc.perform(patch("/users/1/events/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventDate\":\"2020-10-11 23:10:05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));

        verifyNoInteractions(eventService);
    }

    @Test
    void adminUpdateWithPastDateShouldReturnBadRequest() throws Exception {
        mockMvc.perform(patch("/admin/events/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"eventDate\":\"2020-10-11 23:10:05\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));

        verifyNoInteractions(eventService);
    }

    @Test
    void malformedEventBodyShouldReturnApiError() throws Exception {
        mockMvc.perform(patch("/admin/events/10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stateAction\":\"UNKNOWN_ACTION\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.reason").value("Некорректный запрос."));
    }

    @Test
    void invalidRequestParameterShouldReturnApiError() throws Exception {
        mockMvc.perform(get("/events").param("size", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.reason").value("Некорректный запрос."));
    }

    @Test
    void invalidEnumRequestParameterShouldReturnApiError() throws Exception {
        mockMvc.perform(get("/events").param("sort", "UNKNOWN"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.reason").value("Некорректный запрос."));
    }

    @Test
    void missingEventShouldReturnRussianApiError() throws Exception {
        when(eventService.getPublicEvent(404L))
                .thenThrow(new NotFoundException("Событие не найдено"));

        mockMvc.perform(get("/events/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.reason").value("Требуемый объект не найден."));
    }
}
