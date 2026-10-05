package ru.practicum.ewm.dto.category;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NewCategoryDto {
    @NotBlank
    @Size(min = 1, max = 50)
    private String name;
}
