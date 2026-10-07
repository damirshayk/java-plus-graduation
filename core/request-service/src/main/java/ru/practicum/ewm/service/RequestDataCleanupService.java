package ru.practicum.ewm.service;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class RequestDataCleanupService {
    private final RequestDataGuard guard;
    private final JdbcTemplate jdbc;

    @Transactional
    public void deleteUserData(Long userId, List<Long> ownedEventIds) {
        List<Long> eventIds = guard.markDeleting(userId, ownedEventIds);
        if (eventIds.isEmpty()) {
            jdbc.update("DELETE FROM requests WHERE requester_id = ?", userId);
            return;
        }
        String parameters = String.join(", ", Collections.nCopies(eventIds.size(), "?"));
        List<Long> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.addAll(eventIds);
        jdbc.update("DELETE FROM requests WHERE requester_id = ? OR event_id IN (" + parameters + ")",
                arguments.toArray());
    }
}
