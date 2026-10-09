package ru.practicum.ewm.cleanup;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SharedOutboxJdbc {
    private final JdbcTemplate jdbc;
    private final CleanupEventCodec codec;
    private final TransactionTemplate confirmationTransaction;

    public SharedOutboxJdbc(JdbcTemplate jdbc, CleanupEventCodec codec, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.codec = codec;
        confirmationTransaction = new TransactionTemplate(manager);
        confirmationTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    public void enqueue(CommonCleanupEvent event) {
        enqueueAll(List.of(event));
    }

    public void enqueueAll(List<CommonCleanupEvent> events) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Запись outbox требует транзакцию удаления данных");
        }
        if (events.isEmpty()) {
            return;
        }
        jdbc.batchUpdate("INSERT INTO cleanup_outbox(event_id, user_id, payload) VALUES (?, ?, ?)",
                events, events.size(), (statement, event) -> {
                    statement.setString(1, event.eventId().toString());
                    statement.setLong(2, event.userId());
                    statement.setString(3, codec.encode(event));
                });
    }

    public List<PendingMessage> pending(int limit) {
        return jdbc.query("""
                SELECT event_id, user_id, payload
                FROM cleanup_outbox
                ORDER BY created_at, event_id
                LIMIT ?
                """, (row, index) -> new PendingMessage(UUID.fromString(row.getString("event_id")),
                row.getLong("user_id"), row.getString("payload")), limit);
    }

    public void deleteConfirmed(List<UUID> eventIds) {
        if (eventIds.isEmpty()) {
            return;
        }
        String parameters = String.join(", ", Collections.nCopies(eventIds.size(), "?"));
        confirmationTransaction.executeWithoutResult(status ->
                jdbc.update("DELETE FROM cleanup_outbox WHERE event_id IN (" + parameters + ")",
                        eventIds.stream().map(UUID::toString).toArray()));
    }

    public record PendingMessage(UUID eventId, Long userId, String payload) {
    }
}
