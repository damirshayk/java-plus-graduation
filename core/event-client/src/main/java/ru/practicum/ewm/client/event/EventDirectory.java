package ru.practicum.ewm.client.event;

import feign.FeignException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.exception.ServiceUnavailableException;
import ru.practicum.ewm.dto.event.EventInfoDto;

@RequiredArgsConstructor
public class EventDirectory {
    private final EventClient client;

    public EventInfoDto require(Long id) {
        try {
            return client.get(id);
        } catch (FeignException exception) {
            if (exception instanceof DecodeException) {
                throw exception;
            }
            if (exception.status() == 404) {
                throw new NotFoundException("Событие с id=" + id + " не найдено");
            }
            if (exception.status() == -1 || exception.status() >= 500) {
                throw new ServiceUnavailableException("Сервис событий временно недоступен");
            }
            throw exception;
        }
    }
}
