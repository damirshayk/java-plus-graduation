package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.ewm.cleanup.CleanupEventType;
import ru.practicum.ewm.cleanup.CommonCleanupEvent;
import ru.practicum.ewm.cleanup.SharedOutboxJdbc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserDataCleanupService {
    private static final int SNAPSHOT_CHUNK_SIZE = 1000;
    private final UserDataGuard userDataGuard;
    private final JdbcTemplate jdbc;
    private final SharedOutboxJdbc outbox;

    @Transactional
    public void deleteUserData(CommonCleanupEvent event) {
        if (event.type() != CleanupEventType.USER_DELETED) {
            throw new IllegalArgumentException("Сервис событий обрабатывает только USER_DELETED");
        }
        int inserted = jdbc.update("INSERT INTO cleanup_inbox(event_id) VALUES (?) ON CONFLICT DO NOTHING",
                event.eventId().toString());
        if (inserted == 0) {
            return;
        }
        Long userId = event.userId();
        userDataGuard.markDeleting(userId);
        List<Long> eventIds = jdbc.queryForList("SELECT id FROM events WHERE initiator_id = ? ORDER BY id", Long.class, userId);
        List<CommonCleanupEvent> snapshots = new ArrayList<>();
        if (eventIds.isEmpty()) {
            snapshots.add(new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_EVENTS_DELETED, userId, List.of()));
        } else {
            for (int offset = 0; offset < eventIds.size(); offset += SNAPSHOT_CHUNK_SIZE) {
                snapshots.add(new CommonCleanupEvent(UUID.randomUUID(), CleanupEventType.USER_EVENTS_DELETED, userId,
                        eventIds.subList(offset, Math.min(offset + SNAPSHOT_CHUNK_SIZE, eventIds.size()))));
            }
        }
        outbox.enqueueAll(snapshots);
        jdbc.update("DELETE FROM events WHERE initiator_id = ?", userId);
    }
}
