package ru.practicum.ewm.service;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class RemoteConfirmedRequestCounterTest {
    private final RequestClient client = mock(RequestClient.class);
    private final ConfirmedRequestCounter counter = new RemoteConfirmedRequestCounter(client);

    @Test
    void countMustUseSingletonPostAndSparseMissingMustMeanZero() {
        when(client.confirmedCounts(List.of(10L))).thenReturn(Map.of(10L, 3L)).thenReturn(Map.of());
        assertThat(counter.count(10L)).isEqualTo(3);
        assertThat(counter.count(10L)).isZero();
        verify(client, times(2)).confirmedCounts(List.of(10L));
        verifyNoMoreInteractions(client);
    }

    @Test
    void countAllMustDeduplicateAndUseOnlyOnePostWithoutPerEventCalls() {
        when(client.confirmedCounts(List.of(10L, 20L))).thenReturn(Map.of(10L, 2L));
        assertThat(counter.countAll(List.of(10L, 20L, 10L))).isEqualTo(Map.of(10L, 2L));
        verify(client).confirmedCounts(List.of(10L, 20L));
        verifyNoMoreInteractions(client);
    }

    @Test
    void emptyIdsMustSkipHttp() {
        assertThat(counter.countAll(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void nullNegativeMissingValueAndForeignKeyMustNeverBecomeFakeZero() {
        Map<Long, Long> nullValue = new HashMap<>();
        nullValue.put(10L, null);
        Map<Long, Long> nullKey = new HashMap<>();
        nullKey.put(null, 1L);
        for (Map<Long, Long> invalid : java.util.Arrays.asList(null, Map.of(10L, -1L), nullValue, nullKey, Map.of(11L, 1L))) {
            when(client.confirmedCounts(List.of(10L))).thenReturn(invalid);
            assertThatThrownBy(() -> counter.count(10L)).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void transportAndServerErrorsMustBecome503WithoutAutomaticPostRetry() {
        for (int status : new int[]{-1, 500, 503}) {
            clearInvocations(client);
            doThrow(failure(status)).when(client).confirmedCounts(List.of(10L));
            assertThatThrownBy(() -> counter.count(10L)).isInstanceOf(ServiceUnavailableException.class);
            verify(client).confirmedCounts(List.of(10L));
            verifyNoMoreInteractions(client);
        }
    }

    @Test
    void decodeAndOtherHttpFailuresMustNotBeHiddenAs503() {
        DecodeException decode = new DecodeException(500, "Ошибка декодирования", request());
        when(client.confirmedCounts(List.of(10L))).thenThrow(decode);
        assertThatThrownBy(() -> counter.count(10L)).isSameAs(decode);
        FeignException notFound = failure(404);
        doThrow(notFound).when(client).confirmedCounts(List.of(10L));
        assertThatThrownBy(() -> counter.count(10L)).isSameAs(notFound);
    }

    @Test
    void successfulMapMustNotExposeMutableClientState() {
        Map<Long, Long> response = new HashMap<>();
        response.put(10L, 2L);
        when(client.confirmedCounts(List.of(10L))).thenReturn(response);
        Map<Long, Long> result = counter.countAll(List.of(10L));
        response.put(10L, 100L);
        assertThat(result).isEqualTo(Map.of(10L, 2L));
        assertThatThrownBy(() -> result.put(10L, 5L)).isInstanceOf(UnsupportedOperationException.class);
    }

    private FeignException failure(int status) {
        return FeignException.errorStatus("RequestClient#confirmedCounts",
                Response.builder().status(status).request(request()).headers(Map.of()).build());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.POST, "http://request-service/internal/requests/confirmed-counts",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
