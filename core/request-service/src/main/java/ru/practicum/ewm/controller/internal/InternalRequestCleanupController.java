package ru.practicum.ewm.controller.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.ewm.service.RequestDataCleanupService;

import java.util.List;

@RestController
@RequiredArgsConstructor
@Validated
public class InternalRequestCleanupController {
    private final RequestDataCleanupService cleanup;

    @DeleteMapping("/internal/requests/users/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteUserData(
            @PathVariable @Positive(message = "Идентификатор пользователя должен быть положительным") Long userId,
            @Valid @RequestBody @NotNull(message = "Список событий не может быть null")
            List<@NotNull(message = "Идентификатор события не может быть null")
                    @Positive(message = "Идентификатор события должен быть положительным") Long> ownedEventIds) {
        cleanup.deleteUserData(userId, ownedEventIds.stream().distinct().sorted().toList());
    }
}
