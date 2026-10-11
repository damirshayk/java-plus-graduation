package ru.practicum.ewm.controller;

import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.http.HttpMethod;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.MethodValidationInterceptor;
import ru.practicum.ewm.controller.publ.PublicEventController;
import ru.practicum.ewm.dto.event.EventFullDto;
import ru.practicum.ewm.dto.event.EventShortDto;
import ru.practicum.ewm.exception.ErrorHandler;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.exception.ValidationException;
import ru.practicum.ewm.service.EventService;
import ru.practicum.ewm.stats.client.CollectorClient;
import ru.practicum.ewm.stats.proto.ActionTypeProto;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class Stage3PublicEventControllerTest {
    @Mock
    private EventService service;
    @Mock
    private CollectorClient collector;
    private MockMvc mvc;
    private ValidatorFactory validatorFactory;

    @BeforeEach
    void setUp() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        ProxyFactory proxyFactory = new ProxyFactory(new PublicEventController(service, collector));
        proxyFactory.setProxyTargetClass(true);
        proxyFactory.addAdvice(new MethodValidationInterceptor(validatorFactory));
        mvc = MockMvcBuilders.standaloneSetup(proxyFactory.getProxy())
                .setControllerAdvice(new ErrorHandler()).build();
    }

    @AfterEach
    void closeValidatorFactory() {
        validatorFactory.close();
    }

    @ParameterizedTest
    @CsvSource({"GET,/events/10", "GET,/events/recommendations", "PUT,/events/10/like"})
    void missingUserHeaderMustReturnBadRequestWithoutAnyAction(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"))
                .andExpect(jsonPath("$.reason").value("Некорректный запрос."));

        verifyNoInteractions(service, collector);
    }

    @ParameterizedTest
    @CsvSource({
            "GET,/events/10,0", "GET,/events/10,-5", "GET,/events/10,нечисло",
            "GET,/events/10,9223372036854775808",
            "GET,/events/recommendations,0", "GET,/events/recommendations,-5",
            "GET,/events/recommendations,нечисло", "GET,/events/recommendations,9223372036854775808",
            "PUT,/events/10/like,0", "PUT,/events/10/like,-5", "PUT,/events/10/like,нечисло",
            "PUT,/events/10/like,9223372036854775808"
    })
    void invalidUserHeaderMustReturnBadRequestWithoutAnyAction(String method, String path, String userId)
            throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).header("X-EWM-USER-ID", userId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));

        verifyNoInteractions(service, collector);
    }

    @ParameterizedTest
    @CsvSource({"GET,/events/0", "PUT,/events/0/like", "GET,/events/recommendations?size=0"})
    void invalidPathOrSizeMustNotReachServices(String method, String path) throws Exception {
        mvc.perform(request(HttpMethod.valueOf(method), path).header("X-EWM-USER-ID", 5L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));

        verifyNoInteractions(service, collector);
    }

    @Test
    void successfulEventReadMustPublishViewAfterLoadingEvent() throws Exception {
        EventFullDto event = new EventFullDto();
        event.setId(10L);
        event.setRating(1.4);
        when(service.getPublicEvent(10L)).thenReturn(event);

        mvc.perform(get("/events/10").header("X-EWM-USER-ID", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(10L))
                .andExpect(jsonPath("$.rating").value(1.4))
                .andExpect(jsonPath("$.views").doesNotExist());

        var order = inOrder(service, collector);
        order.verify(service).getPublicEvent(10L);
        order.verify(collector).collect(5L, 10L, ActionTypeProto.ACTION_VIEW);
    }

    @Test
    void missingEventMustNotPublishView() throws Exception {
        when(service.getPublicEvent(10L)).thenThrow(new NotFoundException("Событие не найдено"));

        mvc.perform(get("/events/10").header("X-EWM-USER-ID", 5L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));

        verifyNoInteractions(collector);
    }

    @Test
    void recommendationsMustUseDefaultSizeAndNotPublishActions() throws Exception {
        EventShortDto event = new EventShortDto();
        event.setId(10L);
        event.setRating(0.4);
        when(service.getRecommendations(5L, 10)).thenReturn(List.of(event));

        mvc.perform(get("/events/recommendations").header("X-EWM-USER-ID", 5L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(10L))
                .andExpect(jsonPath("$[0].rating").value(0.4));

        verify(service).getRecommendations(5L, 10);
        verifyNoMoreInteractions(service);
        verifyNoInteractions(collector);
    }

    @Test
    void successfulLikeMustPublishAfterAttendanceValidation() throws Exception {
        mvc.perform(put("/events/10/like").header("X-EWM-USER-ID", 5L)).andExpect(status().isOk());

        var order = inOrder(service, collector);
        order.verify(service).validateLike(5L, 10L);
        order.verify(collector).collect(5L, 10L, ActionTypeProto.ACTION_LIKE);
    }

    @Test
    void nonAttendeeMustNotPublishLike() throws Exception {
        doThrow(new ValidationException("Нет подтверждённого участия")).when(service).validateLike(5L, 10L);

        mvc.perform(put("/events/10/like").header("X-EWM-USER-ID", 5L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value("BAD_REQUEST"));

        verifyNoInteractions(collector);
    }

    @Test
    void unavailableAttendanceCheckMustReturn503WithoutPublishingLike() throws Exception {
        doThrow(new ServiceUnavailableException("Сервис заявок временно недоступен"))
                .when(service).validateLike(5L, 10L);

        mvc.perform(put("/events/10/like").header("X-EWM-USER-ID", 5L))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("SERVICE_UNAVAILABLE"))
                .andExpect(jsonPath("$.reason").value("Зависимый сервис временно недоступен."));

        verifyNoInteractions(collector);
    }

    @Test
    void missingEventMustNotPublishLike() throws Exception {
        doThrow(new NotFoundException("Событие не найдено")).when(service).validateLike(5L, 10L);

        mvc.perform(put("/events/10/like").header("X-EWM-USER-ID", 5L))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value("NOT_FOUND"));

        verifyNoInteractions(collector);
    }
}
