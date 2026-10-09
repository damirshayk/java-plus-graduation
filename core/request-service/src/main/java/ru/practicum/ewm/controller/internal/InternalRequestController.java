package ru.practicum.ewm.controller.internal;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.ewm.service.impl.JpaConfirmedRequestCounter;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/internal/requests")
@RequiredArgsConstructor
@Validated
public class InternalRequestController {
    private final JpaConfirmedRequestCounter counter;

    @PostMapping("/confirmed-counts")
    public Map<Long, Long> countAll(@Valid @RequestBody @NotNull List<@NotNull @Positive Long> eventIds) {
        return counter.countAll(eventIds.stream().distinct().toList());
    }
}
