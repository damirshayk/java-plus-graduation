package ru.practicum.ewm.service;

import feign.FeignException;
import feign.codec.DecodeException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.ewm.client.RequestClient;
import ru.practicum.ewm.exception.ServiceUnavailableException;

import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class RemoteConfirmedRequestCounter implements ConfirmedRequestCounter {
    private final RequestClient client;

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
            if (!(exception instanceof DecodeException) && (exception.status() == -1 || exception.status() >= 500)) {
                throw new ServiceUnavailableException("Сервис заявок временно недоступен");
            }
            throw exception;
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
