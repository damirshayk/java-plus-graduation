package ru.practicum.ewm.client;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@FeignClient(name = "request-service", contextId = "requestClient")
public interface RequestClient {
    @PostMapping("/internal/requests/confirmed-counts")
    Map<Long, Long> confirmedCounts(@RequestBody List<Long> eventIds);

    @GetMapping("/internal/requests/users/{userId}/events/{eventId}/confirmed")
    Boolean hasConfirmedRequest(@PathVariable Long userId, @PathVariable Long eventId);
}
