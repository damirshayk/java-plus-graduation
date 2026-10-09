package ru.practicum.ewm.dto.compilation;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import ru.practicum.ewm.dto.event.EventShortDto;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class CompilationDto {
    private Long id;
    private Set<EventShortDto> events;
    @JsonProperty("pinned")
    private Boolean isPinned;
    private String title;
}
