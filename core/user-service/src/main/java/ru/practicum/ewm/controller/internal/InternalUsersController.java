package ru.practicum.ewm.controller.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.service.UserService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/users")
public class InternalUsersController {

    private final UserService userService;

    @GetMapping("/{id}")
    public UserShortDto getUser(@PathVariable Long id) {
        return userService.getUser(id);
    }

    @PostMapping("/lookup")
    public List<UserShortDto> findUsers(@RequestBody List<Long> ids) {
        return userService.findUsers(ids);
    }
}
