package ru.practicum.ewm.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.comment.CommentDto;
import ru.practicum.ewm.dto.comment.NewCommentDto;
import ru.practicum.ewm.dto.comment.UpdateCommentRequest;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CommentMapper;
import ru.practicum.ewm.model.Comment;
import ru.practicum.ewm.client.event.EventDirectory;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.repository.CommentRepository;
import ru.practicum.ewm.service.CommentService;
import ru.practicum.ewm.service.CommentDataGuard;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class CommentServiceImpl implements CommentService {

    private final CommentRepository commentRepository;
    private final UserDirectory userDirectory;
    private final EventDirectory eventDirectory;
    private final CommentMapper commentMapper;
    private final CommentDataGuard dataGuard;
    private final TransactionTemplate localTransaction;

    public CommentServiceImpl(CommentRepository commentRepository,
                              UserDirectory userDirectory,
                              EventDirectory eventDirectory,
                              CommentMapper commentMapper,
                              CommentDataGuard dataGuard,
                              PlatformTransactionManager transactionManager) {
        this.commentRepository = commentRepository;
        this.userDirectory = userDirectory;
        this.eventDirectory = eventDirectory;
        this.commentMapper = commentMapper;
        this.dataGuard = dataGuard;
        this.localTransaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public CommentDto createComment(Long userId, Long eventId, NewCommentDto newCommentDto) {
        log.info("Создание комментария пользователем id={} к событию id={}", userId, eventId);
        UserShortDto author = userDirectory.require(userId);
        EventInfoDto event = eventDirectory.require(eventId);
        if (event.state() != EventState.PUBLISHED) {
            throw new ConflictException("Комментировать можно только опубликованные события");
        }
        return localTransaction.execute(transaction -> {
            dataGuard.lockForWrite(userId, eventId);

            Comment comment = commentMapper.toComment(newCommentDto);
            comment.setAuthorId(userId);
            comment.setEventId(eventId);
            comment.setCreatedOn(LocalDateTime.now());

            Comment saved = commentRepository.save(comment);
            log.info("Комментарий создан с id={}", saved.getId());
            return commentMapper.toDto(saved, author);
        });
    }

    @Override
    public List<CommentDto> getCommentsByEvent(Long userId, Long eventId, int from, int size) {
        log.info("Получение комментариев события id={} пользователем id={}", eventId, userId);
        UserShortDto author = userDirectory.require(userId);
        eventDirectory.require(eventId);

        Pageable pageable = PageRequest.of(from / size, size);
        List<Comment> comments = commentRepository.findByEventIdAndUserId(eventId, userId, pageable).getContent();
        return comments.stream().map(comment -> commentMapper.toDto(comment, author)).toList();
    }

    @Override
    public CommentDto getCommentById(Long userId, Long eventId, Long commentId) {
        log.info("Получение комментария id={} пользователем id={}", commentId, userId);
        UserShortDto author = userDirectory.require(userId);
        return commentMapper.toDto(requireComment(userId, eventId, commentId), author);
    }

    @Override
    public CommentDto updateComment(Long userId, Long eventId, Long commentId,
                                    UpdateCommentRequest updateRequest) {
        log.info("Обновление комментария id={} события id={} пользователем id={}", commentId, eventId, userId);
        UserShortDto author = userDirectory.require(userId);
        return localTransaction.execute(transaction -> {
            dataGuard.lockForWrite(userId, eventId);
            Comment comment = requireComment(userId, eventId, commentId);
            commentMapper.updateCommentFromDto(updateRequest, comment);
            comment.setUpdatedOn(LocalDateTime.now());

            Comment updated = commentRepository.save(comment);
            log.info("Комментарий с id={} обновлён", commentId);
            return commentMapper.toDto(updated, author);
        });
    }

    @Override
    public void deleteComment(Long userId, Long eventId, Long commentId) {
        log.info("Удаление комментария id={} события id={} пользователем id={}", commentId, eventId, userId);
        userDirectory.require(userId);
        localTransaction.executeWithoutResult(transaction -> {
            dataGuard.lockForWrite(userId, eventId);
            Comment comment = requireComment(userId, eventId, commentId);
            commentRepository.delete(comment);
            log.info("Комментарий с id={} удалён", commentId);
        });
    }

    private Comment requireComment(Long userId, Long eventId, Long commentId) {
        return commentRepository.findByCommentIdAndUserIdAndEventId(commentId, userId, eventId)
                .orElseThrow(() -> new NotFoundException(
                        String.format("Комментарий с id=%d для события id=%d не найден", commentId, eventId)));
    }

}
