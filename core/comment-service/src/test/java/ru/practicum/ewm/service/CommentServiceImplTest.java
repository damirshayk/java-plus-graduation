package ru.practicum.ewm.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.practicum.ewm.client.user.UserDirectory;
import ru.practicum.ewm.dto.comment.CommentDto;
import ru.practicum.ewm.dto.comment.NewCommentDto;
import ru.practicum.ewm.dto.comment.UpdateCommentRequest;
import ru.practicum.ewm.dto.user.UserShortDto;
import ru.practicum.ewm.exception.NotFoundException;
import ru.practicum.ewm.mapper.CommentMapper;
import ru.practicum.ewm.model.Comment;
import ru.practicum.ewm.client.event.EventDirectory;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.exception.ConflictException;
import ru.practicum.ewm.model.EventState;
import ru.practicum.ewm.repository.CommentRepository;
import ru.practicum.ewm.service.impl.CommentServiceImpl;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CommentServiceImplTest {
    @Mock
    private CommentRepository commentRepository;
    @Mock
    private UserDirectory userDirectory;
    @Mock
    private EventDirectory eventDirectory;
    @Mock
    private CommentMapper commentMapper;
    @Mock
    private CommentDataGuard dataGuard;
    @Mock
    private PlatformTransactionManager transactionManager;
    private CommentServiceImpl commentService;
    private UserShortDto author;

    @BeforeEach
    void setUp() {
        author = new UserShortDto();
        author.setId(1L);
        author.setName("Автор");
        commentService = new CommentServiceImpl(commentRepository, userDirectory, eventDirectory,
                commentMapper, dataGuard, transactionManager);
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        when(userDirectory.require(1L)).thenReturn(author);
    }

    @Test
    void getCommentShouldRejectCommentFromAnotherEventPath() {
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> commentService.getCommentById(1L, 10L, 100L));

        verify(commentRepository).findByCommentIdAndUserIdAndEventId(100L, 1L, 10L);
    }

    @Test
    void getCommentShouldReturnOwnCommentFromRequestedEvent() {
        Comment comment = ownComment();
        CommentDto dto = new CommentDto();
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L))
                .thenReturn(Optional.of(comment));
        when(commentMapper.toDto(comment, author)).thenReturn(dto);

        assertSame(dto, commentService.getCommentById(1L, 10L, 100L));

        verify(userDirectory).require(1L);
        verifyNoMoreInteractions(userDirectory);
    }

    @Test
    void updateCommentShouldRejectCommentFromAnotherEventPath() {
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L)).thenReturn(Optional.empty());
        UpdateCommentRequest update = new UpdateCommentRequest();
        update.setText("Обновлённый комментарий");

        assertThrows(NotFoundException.class, () -> commentService.updateComment(1L, 10L, 100L, update));

        verify(commentRepository).findByCommentIdAndUserIdAndEventId(100L, 1L, 10L);
        verify(commentRepository, never()).save(any());
    }

    @Test
    void deleteCommentShouldRejectCommentFromAnotherEventPath() {
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L)).thenReturn(Optional.empty());

        assertThrows(NotFoundException.class, () -> commentService.deleteComment(1L, 10L, 100L));

        verify(commentRepository).findByCommentIdAndUserIdAndEventId(100L, 1L, 10L);
        verify(commentRepository, never()).delete(any());
    }

    @Test
    void updateCommentShouldSaveOwnCommentAndReuseCheckedAuthor() {
        Comment comment = ownComment();
        CommentDto dto = new CommentDto();
        UpdateCommentRequest update = new UpdateCommentRequest();
        update.setText("Обновлённый комментарий");
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L))
                .thenReturn(Optional.of(comment));
        when(commentRepository.save(comment)).thenReturn(comment);
        when(commentMapper.toDto(comment, author)).thenReturn(dto);

        assertSame(dto, commentService.updateComment(1L, 10L, 100L, update));

        verify(commentMapper).updateCommentFromDto(update, comment);
        verify(commentRepository).save(argThat(saved -> saved.getAuthorId().equals(1L)
                && saved.getEventId().equals(10L) && saved.getUpdatedOn() != null));
        verify(transactionManager).commit(any());
    }

    @Test
    void deleteCommentShouldDeleteOwnCommentFromRequestedEvent() {
        Comment comment = ownComment();
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L))
                .thenReturn(Optional.of(comment));

        commentService.deleteComment(1L, 10L, 100L);

        verify(commentRepository).delete(comment);
        verify(transactionManager).commit(any());
    }

    @Test
    void eventLookupMustFinishBeforeStartingLocalTransaction() {
        NewCommentDto input = new NewCommentDto();
        input.setText("Комментарий");
        Comment comment = new Comment();
        when(eventDirectory.require(10L)).thenReturn(new EventInfoDto(10L, 1L, EventState.PUBLISHED, 0, true));
        when(commentMapper.toComment(input)).thenReturn(comment);
        when(commentRepository.save(comment)).thenReturn(comment);

        commentService.createComment(1L, 10L, input);

        var order = inOrder(eventDirectory, transactionManager);
        order.verify(eventDirectory).require(10L);
        order.verify(transactionManager).getTransaction(any());
    }

    @Test
    void createShouldCheckRemoteUserBeforeStartingLocalTransaction() {
        NewCommentDto input = new NewCommentDto();
        input.setText("Комментарий");
        Comment comment = new Comment();
        CommentDto result = new CommentDto();
        when(eventDirectory.require(10L)).thenReturn(new EventInfoDto(10L, 1L, EventState.PUBLISHED, 0, true));
        when(commentMapper.toComment(input)).thenReturn(comment);
        when(commentRepository.save(comment)).thenReturn(comment);
        when(commentMapper.toDto(comment, author)).thenReturn(result);

        assertSame(result, commentService.createComment(1L, 10L, input));

        var order = inOrder(userDirectory, transactionManager, dataGuard, eventDirectory,
                commentRepository, commentMapper);
        order.verify(userDirectory).require(1L);
        order.verify(eventDirectory).require(10L);
        order.verify(transactionManager).getTransaction(any());
        order.verify(dataGuard).lockForWrite(1L, 10L);
        order.verify(commentMapper).toComment(input);
        order.verify(commentRepository).save(argThat(saved -> saved.getAuthorId().equals(1L)
                && saved.getEventId().equals(10L) && saved.getCreatedOn() != null));
        order.verify(commentMapper).toDto(comment, author);
        order.verify(transactionManager).commit(any());
    }

    @Test
    void createShouldRejectDeletingUserBeforeSavingComment() {
        when(eventDirectory.require(10L)).thenReturn(new EventInfoDto(10L, 1L, EventState.PUBLISHED, 0, true));
        doThrow(new NotFoundException("Пользователь удаляется")).when(dataGuard).lockForWrite(1L, 10L);

        assertThrows(NotFoundException.class,
                () -> commentService.createComment(1L, 10L, new NewCommentDto()));

        verifyNoInteractions(commentRepository, commentMapper);
        verify(transactionManager).rollback(any());
    }

    @Test
    void commentListShouldReuseOneCheckedAuthorForEveryComment() {
        EventInfoDto event = new EventInfoDto(10L, 1L, EventState.PUBLISHED, 0, true);
        Comment first = new Comment();
        first.setId(100L);
        first.setAuthorId(1L);
        first.setEventId(event.id());
        Comment second = new Comment();
        second.setId(200L);
        second.setAuthorId(1L);
        second.setEventId(event.id());
        CommentDto firstDto = new CommentDto();
        CommentDto secondDto = new CommentDto();
        when(eventDirectory.require(10L)).thenReturn(event);
        when(commentRepository.findByEventIdAndUserId(eq(10L), eq(1L), any()))
                .thenReturn(new PageImpl<>(List.of(first, second)));
        when(commentMapper.toDto(first, author)).thenReturn(firstDto);
        when(commentMapper.toDto(second, author)).thenReturn(secondDto);

        assertEquals(List.of(firstDto, secondDto), commentService.getCommentsByEvent(1L, 10L, 0, 10));

        verify(userDirectory).require(1L);
        verifyNoMoreInteractions(userDirectory);
        verify(commentMapper).toDto(same(first), same(author));
        verify(commentMapper).toDto(same(second), same(author));
    }

    @Test
    void createMustRejectUnpublishedEventBeforeTransaction() {
        when(eventDirectory.require(10L)).thenReturn(new EventInfoDto(10L, 1L, EventState.PENDING, 0, true));
        assertThrows(ConflictException.class, () -> commentService.createComment(1L, 10L, new NewCommentDto()));
        verifyNoInteractions(transactionManager, dataGuard, commentRepository, commentMapper);
    }

    @Test
    void createMustPreserveMissingEventAs404() {
        when(eventDirectory.require(10L)).thenThrow(new NotFoundException("Событие не найдено"));
        assertThrows(NotFoundException.class, () -> commentService.createComment(1L, 10L, new NewCommentDto()));
        verifyNoInteractions(transactionManager, dataGuard, commentRepository, commentMapper);
    }

    @Test
    void updateMustLockUserAndEventBeforeLoadingComment() {
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class,
                () -> commentService.updateComment(1L, 10L, 100L, new UpdateCommentRequest()));
        var order = inOrder(dataGuard, commentRepository);
        order.verify(dataGuard).lockForWrite(1L, 10L);
        order.verify(commentRepository).findByCommentIdAndUserIdAndEventId(100L, 1L, 10L);
        verifyNoInteractions(eventDirectory);
    }

    @Test
    void deleteMustLockUserAndEventBeforeLoadingComment() {
        when(commentRepository.findByCommentIdAndUserIdAndEventId(100L, 1L, 10L)).thenReturn(Optional.empty());
        assertThrows(NotFoundException.class, () -> commentService.deleteComment(1L, 10L, 100L));
        var order = inOrder(dataGuard, commentRepository);
        order.verify(dataGuard).lockForWrite(1L, 10L);
        order.verify(commentRepository).findByCommentIdAndUserIdAndEventId(100L, 1L, 10L);
        verifyNoInteractions(eventDirectory);
    }

    private Comment ownComment() {
        Comment comment = new Comment();
        comment.setId(100L);
        comment.setAuthorId(1L);
        comment.setEventId(10L);
        return comment;
    }
}
