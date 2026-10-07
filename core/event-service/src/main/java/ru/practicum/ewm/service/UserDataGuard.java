package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import ru.practicum.ewm.exception.NotFoundException;

@Component
@RequiredArgsConstructor
public class UserDataGuard {
    private final JdbcTemplate jdbcTemplate;

    public void lockForCreate(Long userId) {
        if (lock(userId)) {
            throw new NotFoundException("Пользователь с id=" + userId + " удалён или удаляется");
        }
    }

    public void markDeleting(Long userId) {
        lock(userId);
        jdbcTemplate.update("UPDATE user_data_guard SET deleting = true WHERE user_id = ?", userId);
    }

    private boolean lock(Long userId) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Защита данных пользователя требует локальной транзакции");
        }
        jdbcTemplate.update("INSERT INTO user_data_guard(user_id) VALUES (?) ON CONFLICT DO NOTHING", userId);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
                SELECT deleting
                FROM user_data_guard
                WHERE user_id = ?
                FOR UPDATE
                """, Boolean.class, userId));
    }
}
