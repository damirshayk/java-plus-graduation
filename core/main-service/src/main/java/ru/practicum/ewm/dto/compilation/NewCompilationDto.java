package ru.practicum.ewm.dto.compilation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;

import java.util.Set;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NewCompilationDto {

    private Set<Long> events;

    @Builder.Default
    @JsonSetter(nulls = Nulls.SKIP)
    @JsonProperty("pinned")
    private Boolean isPinned = false;

    @NotBlank
    @Size(min = 1, max = 50)
    private String title;
}
