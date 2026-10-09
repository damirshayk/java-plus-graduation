package ru.practicum.ewm.client.user;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class UserDirectoryTest {

    private UserClient client;
    private UserDirectory directory;

    @BeforeEach
    void setUp() {
        client = mock(UserClient.class);
        directory = new UserDirectory(client);
    }

    @Test
    void shouldRequireUser() {
        UserShortDto user = user(3L);
        when(client.get(3L)).thenReturn(user);

        assertThat(directory.require(3L)).isSameAs(user);
    }

    @Test
    void shouldNotCallClientForEmptyIds() {
        assertThat(directory.findAll(List.of())).isEmpty();
        verifyNoInteractions(client);
    }

    @Test
    void shouldLoadDistinctIdsOnceAndPreserveRequestedOrder() {
        UserShortDto first = user(1L);
        UserShortDto third = user(3L);
        when(client.batch(List.of(3L, 1L, 2L))).thenReturn(List.of(first, third));

        Map<Long, UserShortDto> result = directory.findAll(List.of(3L, 1L, 3L, 2L));

        assertThat(result.keySet()).containsExactly(3L, 1L);
        assertThat(result).containsEntry(3L, third).containsEntry(1L, first);
        verify(client).batch(List.of(3L, 1L, 2L));
    }

    @Test
    void shouldTranslateMissingUserToNotFound() {
        when(client.get(9L)).thenThrow(error(404));

        assertThatThrownBy(() -> directory.require(9L)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldTranslateConnectionFailureToUnavailable() {
        FeignException failure = error(-1);
        when(client.get(9L)).thenThrow(failure);

        ServiceUnavailableException unavailable = assertThrows(ServiceUnavailableException.class,
                () -> directory.require(9L));
        assertThat(unavailable.getCause()).isSameAs(failure);
    }

    @Test
    void shouldTranslateServerFailureToUnavailable() {
        FeignException failure = error(503);
        when(client.batch(List.of(9L))).thenThrow(failure);

        ServiceUnavailableException unavailable = assertThrows(ServiceUnavailableException.class,
                () -> directory.findAll(List.of(9L)));
        assertThat(unavailable.getCause()).isSameAs(failure);
    }

    @Test
    void shouldTranslateBatchNotFound() {
        when(client.batch(List.of(9L))).thenThrow(error(404));

        assertThatThrownBy(() -> directory.findAll(List.of(9L))).isInstanceOf(NotFoundException.class);
    }

    @Test
    void shouldPreserveOtherClientErrors() {
        FeignException failure = error(400);
        when(client.get(9L)).thenThrow(failure);

        assertThatThrownBy(() -> directory.require(9L)).isSameAs(failure);
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 200, 404, 500})
    void shouldPreserveDecodeErrorsRegardlessOfStatus(int status) {
        DecodeException failure = new DecodeException(status, "Некорректный ответ", request());
        when(client.get(9L)).thenThrow(failure);
        when(client.batch(List.of(9L))).thenThrow(failure);

        assertThatThrownBy(() -> directory.require(9L)).isSameAs(failure);
        assertThatThrownBy(() -> directory.findAll(List.of(9L))).isSameAs(failure);
    }

    @Test
    void shouldPreserveProgrammingErrors() {
        IllegalStateException failure = new IllegalStateException("Ошибка клиента");
        when(client.get(9L)).thenThrow(failure);
        when(client.batch(List.of(9L))).thenThrow(failure);

        assertThatThrownBy(() -> directory.require(9L)).isSameAs(failure);
        assertThatThrownBy(() -> directory.findAll(List.of(9L))).isSameAs(failure);
    }

    private UserShortDto user(Long id) {
        UserShortDto user = new UserShortDto();
        user.setId(id);
        user.setName("Пользователь " + id);
        return user;
    }

    private FeignException error(int status) {
        return FeignException.errorStatus("UserClient#get", Response.builder()
                .status(status)
                .reason("Ошибка")
                .request(request())
                .headers(Map.of())
                .build());
    }

    private Request request() {
        return Request.create(Request.HttpMethod.GET, "http://user-service/internal/users/9",
                Map.of(), null, StandardCharsets.UTF_8, null);
    }
}
