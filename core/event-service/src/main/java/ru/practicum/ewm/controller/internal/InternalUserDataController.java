package ru.practicum.ewm.controller.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.ewm.service.UserDataCleanupService;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/users")
public class InternalUserDataController {
    private final UserDataCleanupService cleanupService;

    @DeleteMapping("/{userId}/data")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserData(@PathVariable Long userId) {
        cleanupService.deleteUserData(userId);
    }
}
