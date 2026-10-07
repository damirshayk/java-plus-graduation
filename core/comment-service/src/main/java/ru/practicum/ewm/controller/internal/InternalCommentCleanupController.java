package ru.practicum.ewm.controller.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.service.CommentDataCleanupService;

import java.util.List;

@RestController
@RequestMapping("/internal/users")
@RequiredArgsConstructor
@Validated
public class InternalCommentCleanupController {
    private final CommentDataCleanupService cleanup;

    @PostMapping("/{userId}/comments/cleanup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cleanup(@PathVariable Long userId,
                        @Valid @RequestBody @NotNull List<@NotNull @Positive Long> eventIds) {
        cleanup.deleteUserData(userId, eventIds);
    }
}
