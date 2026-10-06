package ru.practicum.ewm.service;

import ru.practicum.ewm.dto.comment.CommentDto;
import ru.practicum.ewm.dto.comment.NewCommentDto;
import ru.practicum.ewm.dto.comment.UpdateCommentRequest;

import java.util.List;

public interface CommentService {

    CommentDto createComment(Long userId, Long eventId, NewCommentDto newCommentDto);

    List<CommentDto> getCommentsByEvent(Long userId, Long eventId, int from, int size);

    CommentDto getCommentById(Long userId, Long eventId, Long commentId);

    CommentDto updateComment(Long userId, Long eventId, Long commentId, UpdateCommentRequest updateRequest);

    void deleteComment(Long userId, Long eventId, Long commentId);
}