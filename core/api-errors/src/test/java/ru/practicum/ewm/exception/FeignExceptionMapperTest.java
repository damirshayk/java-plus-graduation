package ru.practicum.ewm.exception;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class FeignExceptionMapperTest {

    private static final String UNAVAILABLE_MESSAGE = "Зависимый сервис временно недоступен";
    private static final String NOT_FOUND_MESSAGE = "Запрошенный объект не найден";

    @ParameterizedTest
    @ValueSource(ints = {-1, 200, 404, 500})
    void shouldPreserveDecodeErrorsBeforeInspectingStatus(int status) {
        DecodeException failure = new DecodeException(status, "Некорректный ответ", request());

        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE)).isSameAs(failure);
        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE, NOT_FOUND_MESSAGE))
                .isSameAs(failure);
    }

    @Test
    void shouldTranslate404OnlyWithNotFoundContext() {
        FeignException failure = failure(404);

        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE, NOT_FOUND_MESSAGE))
                .isInstanceOf(NotFoundException.class)
                .hasMessage(NOT_FOUND_MESSAGE);
    }

    @Test
    void shouldPreserve404WithoutNotFoundContext() {
        FeignException failure = failure(404);

        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE)).isSameAs(failure);
        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE, null)).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 500, 503, 599})
    void shouldTranslateTransportAnd5xxErrorsKeepingCause(int status) {
        FeignException failure = failure(status);

        RuntimeException withoutContext = FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE);
        RuntimeException withContext = FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE, NOT_FOUND_MESSAGE);

        assertThat(withoutContext).isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE_MESSAGE);
        assertThat(withoutContext.getCause()).isSameAs(failure);
        assertThat(withContext).isInstanceOf(ServiceUnavailableException.class).hasMessage(UNAVAILABLE_MESSAGE);
        assertThat(withContext.getCause()).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(ints = {-2, 200, 400, 401, 403, 409, 429, 499, 600})
    void shouldPreserveAllOtherStatuses(int status) {
        FeignException failure = failure(status);

        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE)).isSameAs(failure);
        assertThat(FeignExceptionMapper.translate(failure, UNAVAILABLE_MESSAGE, NOT_FOUND_MESSAGE))
                .isSameAs(failure);
    }

    private FeignException failure(int status) {
        return FeignException.errorStatus("Directory#get", Response.builder().status(status)
                .request(request()).headers(Map.of()).build());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://dependent-service/internal/objects/9",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
