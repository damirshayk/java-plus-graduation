package ru.practicum.ewm.service.impl;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.dto.comment.CommentDto;
import ru.practicum.ewm.dto.comment.NewCommentDto;
import ru.practicum.ewm.dto.comment.UpdateCommentRequest;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CommentMapper;
import ru.practicum.ewm.model.Comment;
import ru.practicum.ewm.model.Event;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.model.User;
import ru.practicum.ewm.repository.CommentRepository;
import ru.practicum.ewm.repository.EventRepository;
import ru.practicum.ewm.repository.UserRepository;
import ru.practicum.ewm.service.CommentService;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final UserRepository userRepository;
    private final EventRepository eventRepository;
    private final CommentMapper commentMapper;

    @Override
    @Transactional
    public CommentDto createComment(Long userId, Long eventId, NewCommentDto newCommentDto) {
        log.info("Создание комментария пользователем id={} к событию id={}", userId, eventId);

        User author = requireUser(userId);
        Event event = requireEvent(eventId);

        if (event.getState() != EventState.PUBLISHED) {
            throw new ConflictException("Комментировать можно только опубликованные события");
        }

        Comment comment = commentMapper.toComment(newCommentDto);
        comment.setAuthor(author);
        comment.setEvent(event);
        comment.setCreatedOn(LocalDateTime.now());

        Comment saved = commentRepository.save(comment);
        log.info("Комментарий создан с id={}", saved.getId());

        return commentMapper.toDto(saved);
    }

    @Override
    public List<CommentDto> getCommentsByEvent(Long userId, Long eventId, int from, int size) {
        log.info("Получение комментариев события id={} пользователем id={}", eventId, userId);

        requireUser(userId);
        requireEvent(eventId);

        Pageable pageable = PageRequest.of(from / size, size);
        List<Comment> comments = commentRepository.findByEventIdAndUserId(eventId, userId, pageable).getContent();

        return commentMapper.toDtoList(comments);
    }

    @Override
    public CommentDto getCommentById(Long userId, Long eventId, Long commentId) {
        log.info("Получение комментария id={} пользователем id={}", commentId, userId);

        requireUser(userId);
        requireEvent(eventId);

        Comment comment = commentRepository.findByCommentIdAndUserId(commentId, userId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Комментарий с id=%d не найден", commentId)
                ));

        return commentMapper.toDto(comment);
    }

    @Override
    @Transactional
    public CommentDto updateComment(Long userId, Long eventId, Long commentId,
                                    UpdateCommentRequest updateRequest) {
        log.info("Обновление комментария id={} события id={} пользователем id={}", commentId, eventId, userId);

        requireUser(userId);
        requireEvent(eventId);

        Comment comment = commentRepository.findByCommentIdAndUserId(commentId, userId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Комментарий с id=%d для события id=%d не найден", commentId, eventId)
                ));

        if (!comment.getAuthor().getId().equals(userId)) {
            throw new ConflictException("Изменить комментарий может только его автор");
        }

        commentMapper.updateCommentFromDto(updateRequest, comment);
        comment.setUpdatedOn(LocalDateTime.now());

        Comment updated = commentRepository.save(comment);
        log.info("Комментарий с id={} обновлён", commentId);

        return commentMapper.toDto(updated);
    }

    @Override
    @Transactional
    public void deleteComment(Long userId, Long eventId, Long commentId) {
        log.info("Удаление комментария id={} события id={} пользователем id={}", commentId, eventId, userId);

        requireUser(userId);
        requireEvent(eventId);

        Comment comment = commentRepository.findByCommentIdAndUserId(commentId, userId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Комментарий с id=%d для события id=%d не найден", commentId, eventId)
                ));

        if (!comment.getAuthor().getId().equals(userId)) {
            throw new ConflictException("Удалить комментарий может только его автор");
        }

        commentRepository.delete(comment);
        log.info("Комментарий с id={} удалён", commentId);
    }

    private User requireUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Пользователь с id=%d не найден", userId)
                ));
    }

    private Event requireEvent(Long eventId) {
        return eventRepository.findById(eventId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Событие с id=%d не найдено", eventId)
                ));
    }
}