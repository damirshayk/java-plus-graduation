package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.practicum.ewm.exception.NotFoundException;

import java.util.Collections;
import java.util.List;
import java.util.TreeSet;

@Component
@RequiredArgsConstructor
public class RequestDataGuard {
    private final JdbcTemplate jdbc;

    public void lockForWrite(Long userId, Long eventId) {
        if (lockUser(userId)) {
            throw new NotFoundException("Пользователь с id=" + userId + " удалён или удаляется");
        }
        jdbc.update("INSERT INTO request_event_guard(event_id) VALUES (?) ON CONFLICT DO NOTHING", eventId);
        boolean deleting = Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleting FROM request_event_guard WHERE event_id = ? FOR UPDATE", Boolean.class, eventId));
        if (deleting) {
            throw new NotFoundException("Событие с id=" + eventId + " удалено или удаляется");
        }
    }

    public List<Long> markDeleting(Long userId, List<Long> ownedEventIds) {
        lockUser(userId);
        jdbc.update("UPDATE request_user_guard SET deleting = true WHERE user_id = ?", userId);
        List<Long> ownedIds = ownedEventIds.stream().distinct().sorted().toList();
        TreeSet<Long> eventIds = new TreeSet<>(ownedIds);
        eventIds.addAll(jdbc.queryForList(
                "SELECT DISTINCT event_id FROM requests WHERE requester_id = ?", Long.class, userId));
        if (!eventIds.isEmpty()) {
            String values = String.join(", ", Collections.nCopies(eventIds.size(), "(?)"));
            jdbc.update("INSERT INTO request_event_guard(event_id) VALUES " + values + " ON CONFLICT DO NOTHING",
                    eventIds.toArray());
            String parameters = String.join(", ", Collections.nCopies(eventIds.size(), "?"));
            jdbc.queryForList("""
                    SELECT event_id FROM request_event_guard
                    WHERE event_id IN (%s)
                    ORDER BY event_id
                    FOR UPDATE
                    """.formatted(parameters), Long.class, eventIds.toArray());
        }
        if (!ownedIds.isEmpty()) {
            String parameters = String.join(", ", Collections.nCopies(ownedIds.size(), "?"));
            jdbc.update("UPDATE request_event_guard SET deleting = true WHERE event_id IN (" + parameters + ")",
                    ownedIds.toArray());
        }
        return ownedIds;
    }

    private boolean lockUser(Long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Защита заявок требует локальной транзакции");
        }
        jdbc.update("INSERT INTO request_user_guard(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT deleting FROM request_user_guard WHERE user_id = ? FOR UPDATE", Boolean.class, userId));
    }
}
