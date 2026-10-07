package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.Collections;
import java.util.List;

@Component
@RequiredArgsConstructor
public class CommentDataGuard {
    private final JdbcTemplate jdbc;

    public void lockForWrite(Long userId, Long eventId) {
        if (lockUser(userId)) {
            throw new NotFoundException("Пользователь с id=" + userId + " удалён или удаляется");
        }
        jdbc.update("INSERT INTO comment_event_guard(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId);
        boolean deleting = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT deleting FROM comment_event_guard WHERE event_id = ? FOR UPDATE
                """, Boolean.class, eventId));
        if (deleting) {
            throw new NotFoundException("Событие с id=" + eventId + " удалено или удаляется");
        }
    }

    public List<Long> markDeleting(Long userId, List<Long> eventIds) {
        lockUser(userId);
        jdbc.update("UPDATE comment_user_guard SET deleting = true WHERE user_id = ?", userId);
        List<Long> distinctIds = eventIds.stream().distinct().sorted().toList();
        if (distinctIds.isEmpty()) {
            return distinctIds;
        }
        String values = String.join(", ", Collections.nCopies(distinctIds.size(), "(?)"));
        jdbc.update("INSERT INTO comment_event_guard(event_id) VALUES " + values + " ON CONFLICT DO NOTHING",
                distinctIds.toArray());
        String parameters = String.join(", ", Collections.nCopies(distinctIds.size(), "?"));
        jdbc.queryForList("""
                SELECT event_id FROM comment_event_guard
                WHERE event_id IN (%s)
                ORDER BY event_id
                FOR UPDATE
                """.formatted(parameters), Long.class, distinctIds.toArray());
        jdbc.update("UPDATE comment_event_guard SET deleting = true WHERE event_id IN (" + parameters + ")",
                distinctIds.toArray());
        return distinctIds;
    }

    private boolean lockUser(Long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Защита комментариев требует локальной транзакции");
        }
        jdbc.update("INSERT INTO comment_user_guard(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT deleting FROM comment_user_guard WHERE user_id = ? FOR UPDATE
                """, Boolean.class, userId));
    }
}
