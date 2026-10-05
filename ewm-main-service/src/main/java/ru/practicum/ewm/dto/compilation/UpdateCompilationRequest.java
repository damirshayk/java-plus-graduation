package ru.practicum.ewm.dto.compilation;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
public class UpdateCompilationRequest {

    private Set<Long> events;

    @JsonProperty("pinned")
    private Boolean isPinned;

    @Size(min = 1, max = 50)
    private String title;
}
