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
public class CommentDataCleanupService {
    private final CommentDataGuard guard;
    private final JdbcTemplate jdbc;

    @Transactional
    public void deleteUserData(Long userId, List<Long> eventIds) {
        List<Long> distinctIds = guard.markDeleting(userId, eventIds);
        if (distinctIds.isEmpty()) {
            jdbc.update("DELETE FROM comments WHERE author_id = ?", userId);
            return;
        }
        String parameters = String.join(", ", Collections.nCopies(distinctIds.size(), "?"));
        List<Long> arguments = new ArrayList<>();
        arguments.add(userId);
        arguments.addAll(distinctIds);
        jdbc.update("DELETE FROM comments WHERE author_id = ? OR event_id IN (" + parameters + ")",
                arguments.toArray());
    }
}
