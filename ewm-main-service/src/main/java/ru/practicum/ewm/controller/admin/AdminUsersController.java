package ru.practicum.ewm.controller.admin;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.dto.user.NewUserRequest;
import ru.practicum.ewm.dto.user.UserDto;
import ru.practicum.ewm.service.UserService;

import java.util.List;

@Slf4j
@RestController
@RequiredArgsConstructor
@RequestMapping("/admin/users")
public class AdminUsersController {

    private final UserService userService;

    @GetMapping
    public List<UserDto> getUsers(@RequestParam(required = false) List<Long> ids,
                                  @RequestParam(defaultValue = "0") Integer from,
                                  @RequestParam(defaultValue = "10") Integer size) {
        log.info("Получение пользователей: ids={}, from={}, size={}", ids, from, size);
        return userService.getUsers(ids, from, size);
    }

    @PostMapping
    public ResponseEntity<UserDto> registerUser(@Valid @RequestBody NewUserRequest newUserRequest) {
        log.info("Регистрация нового пользователя: {}", newUserRequest);
        UserDto createdUser = userService.registerUser(newUserRequest);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);
    }

    @DeleteMapping("/{userId}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long userId) {
        log.info("Удаление пользователя с id: {}", userId);
        userService.deleteUser(userId);
        return ResponseEntity.noContent().build();
    }
}