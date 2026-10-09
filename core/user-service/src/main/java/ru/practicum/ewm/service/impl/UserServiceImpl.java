package ru.practicum.ewm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.cleanup.CommonCleanupEvent;
import ru.practicum.ewm.cleanup.CleanupEventType;
import ru.practicum.ewm.cleanup.SharedOutboxJdbc;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.UserMapper;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.UserService;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final SharedOutboxJdbc outbox;

    @Override
    @Transactional
    public UserDto registerUser(NewUserRequest newUserRequest) {
        log.info("Регистрация пользователя: {}", newUserRequest);

        if (userRepository.existsByEmail(newUserRequest.getEmail())) {
            throw new ConflictException("Пользователь с таким адресом электронной почты уже существует");
        }

        User user = userMapper.toUser(newUserRequest);
        User saved = userRepository.save(user);
        log.info("Пользователь зарегистрирован с id={}", saved.getId());

        return userMapper.toDto(saved);
    }

    @Override
    public List<UserDto> getUsers(List<Long> ids, Integer from, Integer size) {
        log.info("Получение пользователей: ids={}, from={}, size={}", ids, from, size);

        List<User> users;

        if (ids != null && !ids.isEmpty()) {
            users = userRepository.findAllByIdIn(ids);
        } else {
            Pageable pageable = PageRequest.of(from / size, size, Sort.by("id"));
            users = userRepository.findAll(pageable).getContent();
        }

        return users.stream()
                .map(userMapper::toDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteUser(Long userId) {
        log.info("Удаление пользователя с id={}", userId);

        if (userRepository.deleteUserById(userId) == 0) {
            throw new NotFoundException(
                    String.format("User with id %d was not found", userId)
            );
        }

        outbox.enqueue(new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_DELETED, userId, List.of()));
        log.info("Пользователь с id={} удален", userId);
    }

    @Override
    public UserShortDto getUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("Пользователь с id=" + userId + " не найден"));
        return userMapper.toShortDto(user);
    }

    @Override
    public List<UserShortDto> findUsers(List<Long> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return userRepository.findAllByIdIn(ids.stream().distinct().toList()).stream()
                .map(userMapper::toShortDto)
                .toList();
    }
}
