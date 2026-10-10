package ru.practicum.ewm.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.model.RequestStatus;
import ru.practicum.ewm.repository.RequestRepository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class JpaConfirmedRequestCounter {
    private final RequestRepository requestRepository;

    public boolean hasConfirmedRequest(Long userId, Long eventId) {
        return requestRepository.existsByRequesterIdAndEventIdAndStatus(userId, eventId, RequestStatus.CONFIRMED);
    }

    public long count(Long eventId) {
        return requestRepository.countByEventIdAndStatus(eventId, RequestStatus.CONFIRMED);
    }

    public Map<Long, Long> countAll(List<Long> eventIds) {
        if (eventIds.isEmpty()) return Map.of();
        return requestRepository.countByEventIdsAndStatus(eventIds, RequestStatus.CONFIRMED).stream()
                .collect(Collectors.toMap(row -> (Long) row[0], row -> (Long) row[1]));
    }
}
