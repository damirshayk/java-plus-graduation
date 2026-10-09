package ru.practicum.ewm.client.event;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import ru.practicum.ewm.exception.FeignExceptionMapper;
import ru.practicum.ewm.dto.event.EventInfoDto;

@RequiredArgsConstructor
public class EventDirectory {
    private final EventClient client;

    public EventInfoDto require(Long id) {
        try {
            return client.get(id);
        } catch (FeignException exception) {
            throw FeignExceptionMapper.translate(exception, "Сервис событий временно недоступен",
                    "Событие с id=" + id + " не найдено");
        }
    }
}
