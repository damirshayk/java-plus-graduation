package ru.practicum.ewm.controller;

import jakarta.validation.Validation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.cloud.client.loadbalancer.LoadBalancerClient;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import ru.practicum.ewm.EndpointHitRequestDto;
import ru.practicum.ewm.StatsClient;
import ru.practicum.ewm.controller.admin.AdminEventController;
import ru.practicum.ewm.controller.priv.PrivateEventController;
import ru.practicum.ewm.controller.publ.PublicEventController;
import ru.practicum.ewm.dto.event.EventFullDto;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.exception.ErrorHandler;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.service.EventService;

import java.util.List;

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
    void publicEventsShouldReturnOkWhenStatsServiceHasNoInstances() throws Exception {
        MockMvc publicMockMvc = publicMvcWithoutStatsService();
        EventShortDto event = new EventShortDto();
        event.setId(10L);
        event.setTitle("Опубликованное событие");
        when(eventService.getPublicEvents(null, null, null, null, null, false, null, 0, 10))
                .thenReturn(List.of(event));

        publicMockMvc.perform(get("/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10))
                .andExpect(jsonPath("$[0].title").value("Опубликованное событие"));

        verify(eventService).getPublicEvents(null, null, null, null, null, false, null, 0, 10);
    }

    @Test
    void publicEventShouldReturnOkWhenStatsServiceHasNoInstances() throws Exception {
        MockMvc publicMockMvc = publicMvcWithoutStatsService();
        EventFullDto event = new EventFullDto();
        event.setId(10L);
        event.setTitle("Опубликованное событие");
        when(eventService.getPublicEvent(10L)).thenReturn(event);

        publicMockMvc.perform(get("/events/10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10))
                .andExpect(jsonPath("$.title").value("Опубликованное событие"));

        verify(eventService).getPublicEvent(10L);
    }

    @Test
    void missingPublicEventShouldReturnNotFoundWhenStatsServiceHasNoInstances() throws Exception {
        MockMvc publicMockMvc = publicMvcWithoutStatsService();
        when(eventService.getPublicEvent(404L))
                .thenThrow(new NotFoundException("Событие не найдено"));

        publicMockMvc.perform(get("/events/404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"))
                .andExpect(jsonPath("$.reason").value("Требуемый объект не найден."));

        verify(eventService).getPublicEvent(404L);
    }

    private MockMvc publicMvcWithoutStatsService() {
        LoadBalancerClient loadBalancerClient = mock(LoadBalancerClient.class);
        when(loadBalancerClient.choose("stats-server")).thenReturn(null);
        StatsClient realStatsClient = new StatsClient("stats-server", loadBalancerClient,
                new RestTemplateBuilder()
                        .additionalCustomizers(rest -> MockRestServiceServer.bindTo(rest).build()));
        return MockMvcBuilders.standaloneSetup(
                validated(new PublicEventController(eventService, realStatsClient))
        ).setControllerAdvice(new ErrorHandler()).build();
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
