package ru.practicum.ewm.service;

import feign.FeignException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.exception.FeignExceptionMapper;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RemoteConfirmedRequestCounter implements ConfirmedRequestCounter {
    private final RequestClient client;

    @Override
    public boolean hasConfirmedRequest(Long userId, Long eventId) {
        try {
            Boolean confirmed = client.hasConfirmedRequest(userId, eventId);
            if (confirmed == null) {
                throw new IllegalStateException("Сервис заявок не вернул результат проверки участия");
            }
            return confirmed;
        } catch (FeignException exception) {
            throw FeignExceptionMapper.translate(exception, "Сервис заявок временно недоступен");
        }
    }

    @Override
    public long count(Long eventId) {
        return countAll(List.of(eventId)).getOrDefault(eventId, 0L);
    }

    @Override
    public Map<Long, Long> countAll(List<Long> eventIds) {
        if (eventIds.isEmpty()) {
            return Map.of();
        }
        List<Long> ids = eventIds.stream().distinct().toList();
        Map<Long, Long> counts;
        try {
            counts = client.confirmedCounts(ids);
        } catch (FeignException exception) {
            throw FeignExceptionMapper.translate(exception, "Сервис заявок временно недоступен");
        }
        Set<Long> requestedIds = Set.copyOf(ids);
        if (counts == null || counts.entrySet().stream().anyMatch(entry ->
                entry.getKey() == null || !requestedIds.contains(entry.getKey())
                || entry.getValue() == null || entry.getValue() < 0)) {
            throw new IllegalStateException("Сервис заявок вернул некорректные счётчики подтверждений");
        }
        return Map.copyOf(counts);
    }
}
