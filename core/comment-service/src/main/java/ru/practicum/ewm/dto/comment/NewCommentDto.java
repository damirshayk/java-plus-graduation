package ru.practicum.ewm.dto.comment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
public class NewCommentDto {
    @NotBlank(message = "Текст комментария не должен быть пустым")
    @Size(max = 2000, message = "Текст комментария должен содержать не более 2000 символов")
    private String text;
}
