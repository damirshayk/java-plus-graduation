package ru.practicum.ewm.client.user;

import feign.FeignException;
import feign.Request;
import feign.Response;
import feign.codec.DecodeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
        when(client.get(9L)).thenThrow(error(-1));

        assertThatThrownBy(() -> directory.require(9L)).isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void shouldTranslateServerFailureToUnavailable() {
        when(client.batch(List.of(9L))).thenThrow(error(503));

        assertThatThrownBy(() -> directory.findAll(List.of(9L)))
                .isInstanceOf(ServiceUnavailableException.class);
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

    @Test
    void shouldPreserveDecodeErrors() {
        DecodeException failure = new DecodeException(200, "Некорректный ответ", request());
        when(client.batch(List.of(9L))).thenThrow(failure);

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
