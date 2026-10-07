package ru.practicum.ewm.service;

import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.dto.user.UserShortDto;

import java.util.List;

public interface UserService {

    UserDto registerUser(NewUserRequest newUserRequest);

    List<UserDto> getUsers(List<Long> ids, Integer from, Integer size);

    void deleteUser(Long userId);

    UserShortDto getUser(Long userId);

    List<UserShortDto> findUsers(List<Long> ids);
}
