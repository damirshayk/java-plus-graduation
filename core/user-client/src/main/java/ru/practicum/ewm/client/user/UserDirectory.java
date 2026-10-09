package ru.practicum.ewm.client.user;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.FeignExceptionMapper;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class UserDirectory {

    private final UserClient client;

    public UserShortDto require(Long id) {
        try {
            return client.get(id);
        } catch (FeignException exception) {
            throw FeignExceptionMapper.translate(exception, "Сервис пользователей временно недоступен",
                    "Пользователь с id=" + id + " не найден");
        }
    }

    public Map<Long, UserShortDto> findAll(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<Long> distinctIds = ids.stream().distinct().toList();
        List<UserShortDto> users;
        try {
            users = client.batch(distinctIds);
        } catch (FeignException exception) {
            throw FeignExceptionMapper.translate(exception, "Сервис пользователей временно недоступен",
                    "Пользователи не найдены");
        }
        Map<Long, UserShortDto> byId = new HashMap<>();
        for (UserShortDto user : users) {
            byId.put(user.getId(), user);
        }
        Map<Long, UserShortDto> result = new LinkedHashMap<>();
        for (Long id : distinctIds) {
            if (byId.containsKey(id)) {
                result.put(id, byId.get(id));
            }
        }
        return result;
    }

}
