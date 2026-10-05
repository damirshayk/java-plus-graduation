package ru.practicum.ewm.controller.priv;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.dto.comment.CommentDto;
import ru.practicum.ewm.dto.comment.NewCommentDto;
import ru.practicum.ewm.dto.comment.UpdateCommentRequest;
import ru.practicum.ewm.service.CommentService;

import java.util.List;

@RestController
@RequestMapping("/users/{userId}/events/{eventId}/comments")
@RequiredArgsConstructor
@Validated
public class PrivateCommentController {
    private final CommentService commentService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CommentDto createComment(
            @PathVariable Long userId,
            @PathVariable Long eventId,
            @Valid @RequestBody NewCommentDto newCommentDto) {

        return commentService.createComment(userId, eventId, newCommentDto);
    }


    @GetMapping
    @ResponseStatus(HttpStatus.OK)
    public List<CommentDto> getCommentsByEvent(
            @PathVariable Long userId,
            @PathVariable Long eventId,
            @RequestParam(defaultValue = "0") @PositiveOrZero int from,
            @RequestParam(defaultValue = "10") @Positive int size) {

        return commentService.getCommentsByEvent(userId, eventId, from, size);
    }

    @GetMapping("/{commentId}")
    @ResponseStatus(HttpStatus.OK)
    public CommentDto getCommentById(
            @PathVariable Long userId,
            @PathVariable Long eventId,
            @PathVariable Long commentId) {

        return commentService.getCommentById(userId, eventId, commentId);
    }

    @PatchMapping("/{commentId}")
    @ResponseStatus(HttpStatus.OK)
    public CommentDto updateComment(
            @PathVariable Long userId,
            @PathVariable Long eventId,
            @PathVariable Long commentId,
            @Valid @RequestBody UpdateCommentRequest updateRequest) {

        return commentService.updateComment(userId, eventId, commentId, updateRequest);
    }

    @DeleteMapping("/{commentId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteComment(
            @PathVariable Long userId,
            @PathVariable Long eventId,
            @PathVariable Long commentId) {

        commentService.deleteComment(userId, eventId, commentId);
    }
}
