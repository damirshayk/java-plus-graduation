package ru.practicum.ewm.controller.internal;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import ru.practicum.ewm.dto.event.EventInfoDto;
import ru.practicum.ewm.service.EventService;

@RestController
@RequestMapping("/internal/events")
@RequiredArgsConstructor
public class InternalEventController {
    private final EventService eventService;

    @GetMapping("/{id}")
    public EventInfoDto get(@PathVariable Long id) {
        return eventService.getInternalEvent(id);
    }
}
