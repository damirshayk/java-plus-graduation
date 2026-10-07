package ru.practicum.ewm.client.event;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.model.EventState;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class EventDirectoryTest {
    private final EventClient client = mock(EventClient.class);
    private final EventDirectory directory = new EventDirectory(client);

    @Test
    void shouldReturnLightweightEvent() {
        EventInfoDto event = new EventInfoDto(10L, 1L, EventState.PUBLISHED, 0, true);
        when(client.get(10L)).thenReturn(event);
        assertThat(directory.require(10L)).isSameAs(event);
    }

    @Test
    void shouldTranslateNotFoundAndNonPositiveIds() {
        for (long id : new long[]{10, 0, -1}) {
            when(client.get(id)).thenThrow(error(404));
            assertThatThrownBy(() -> directory.require(id)).isInstanceOf(NotFoundException.class);
        }
    }

    @Test
    void shouldTranslateOnlyConnectionAndServerFailures() {
        for (int status : new int[]{-1, 500, 503}) {
            doThrow(error(status)).when(client).get(10L);
            assertThatThrownBy(() -> directory.require(10L)).isInstanceOf(ServiceUnavailableException.class);
        }
    }

    @Test
    void shouldPreserveDecodeAndProgrammingErrors() {
        DecodeException decode = new DecodeException(200, "Некорректный ответ", request());
        when(client.get(10L)).thenThrow(decode);
        assertThatThrownBy(() -> directory.require(10L)).isSameAs(decode);
        IllegalStateException programming = new IllegalStateException("Ошибка клиента");
        doThrow(programming).when(client).get(10L);
        assertThatThrownBy(() -> directory.require(10L)).isSameAs(programming);
    }

    @Test
    void shouldPreserveOtherHttpFailures() {
        FeignException failure = error(400);
        when(client.get(10L)).thenThrow(failure);
        assertThatThrownBy(() -> directory.require(10L)).isSameAs(failure);
    }

    private FeignException error(int status) {
        return FeignException.errorStatus("EventClient#get", Response.builder().status(status)
                .request(request()).headers(Map.of()).build());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://event-service/internal/events/10",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
