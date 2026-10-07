package ru.practicum.ewm.client.event;

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import ru.practicum.ewm.dto.event.EventInfoDto;

@FeignClient(name = "${ewm.event-service-id:event-service}", contextId = "eventClient")
public interface EventClient {
    @GetMapping("/internal/events/{id}")
    EventInfoDto get(@PathVariable("id") Long id);
}
